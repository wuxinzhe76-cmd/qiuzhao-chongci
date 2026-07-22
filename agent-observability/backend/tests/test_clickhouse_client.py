"""ClickHouse 客户端测试。

通过 mock `get_client` 隔离真实 ClickHouse，覆盖：
  - `_build_insert_rows`：应用默认值 / 必填列缺失抛 KeyError
  - `_bulk_insert`：空数据短路、SQL 与行构造、错误向上抛
  - `_retry_insert`：成功即返、重试后成功、重试耗尽丢弃不抛、空数据短路、退避 sleep
  - `_select`：返回 dict 列表、异常时返回 []
  - `_select_by_filter_or_recent`：过滤 ASC / 最近 DESC LIMIT 的 SQL 模板
  - `query_metrics_compare`：models 参数化 IN 子句构造
  - `query_leaderboard`：非法 metric 返回 []，合法 metric 走对应模板
"""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from clickhouse_driver import errors as ch_errors

from app.clickhouse import client as ch
from app.clickhouse.client import (
    _SPECS,
    _build_insert_rows,
    _bulk_insert,
    _retry_insert,
    _select,
    _select_by_filter_or_recent,
    get_client,
    query_leaderboard,
    query_metrics_compare,
)


# ----------------------------- _build_insert_rows -----------------------------


def test_build_insert_rows_applies_defaults_for_prompts():
    """prompts 表有大量默认值，缺失字段应被默认值填充"""
    spec = _SPECS["prompts"]
    data = [{"trace_id": "t", "span_id": "s"}]  # 其余全缺
    rows = _build_insert_rows(spec, data)
    assert len(rows) == 1
    row = rows[0]
    # 顺序与 insert_columns 一致
    assert row[0] == "t"          # trace_id
    assert row[1] == "s"          # span_id
    assert row[2] == "unknown"    # model_name 默认
    assert row[3] == ""           # prompt 默认
    assert row[7] == 0            # latency_ms 默认
    assert row[8] is False        # stream 默认
    assert row[9] == "success"    # status 默认


def test_build_insert_rows_uses_provided_values_over_defaults():
    spec = _SPECS["tool_calls"]
    data = [{"trace_id": "t", "span_id": "s", "tool_name": "calc", "duration_ms": 99}]
    rows = _build_insert_rows(spec, data)
    row = rows[0]
    # tool_name 与 duration_ms 取提供值，其余取默认
    assert row[2] == "calc"
    assert row[6] == 99
    assert row[3] == "generic"  # tool_type 默认


def test_build_insert_rows_missing_required_raises_keyerror():
    """traces 表无默认值，缺失必填列应抛 KeyError"""
    spec = _SPECS["traces"]
    data = [{"trace_id": "t", "span_id": "s"}]  # 缺 name/start_time/end_time/parent_span_id/attributes
    with pytest.raises(KeyError):
        _build_insert_rows(spec, data)


def test_build_insert_rows_multiple_rows_preserve_order():
    spec = _SPECS["sessions"]
    data = [
        {"session_id": "a", "trace_id": "t1"},
        {"session_id": "b", "trace_id": "t2", "agent_name": "x"},
    ]
    rows = _build_insert_rows(spec, data)
    assert len(rows) == 2
    assert rows[0][0] == "a"
    assert rows[1][0] == "b"
    assert rows[1][2] == "x"  # agent_name


# ----------------------------- _bulk_insert -----------------------------


@pytest.mark.asyncio
async def test_bulk_insert_empty_data_short_circuits():
    with patch("app.clickhouse.client.get_client") as gc:
        await _bulk_insert("traces", [])
    gc.assert_not_called()


@pytest.mark.asyncio
async def test_bulk_insert_builds_sql_and_rows_and_executes():
    fake_client = MagicMock()
    fake_client.execute = MagicMock(return_value=None)
    with patch("app.clickhouse.client.get_client", return_value=fake_client):
        await _bulk_insert("traces", [
            {
                "trace_id": "t", "span_id": "s", "parent_span_id": "",
                "name": "n", "start_time": "st", "end_time": "et",
                "attributes": "{}",
            }
        ])

    fake_client.execute.assert_called_once()
    sql, rows = fake_client.execute.call_args.args
    assert "INSERT INTO agent_traces" in sql
    assert "trace_id, span_id, parent_span_id, name, start_time, end_time, attributes" in sql
    assert len(rows) == 1
    assert rows[0][0] == "t"


@pytest.mark.asyncio
async def test_bulk_insert_reraises_clickhouse_error():
    fake_client = MagicMock()
    fake_client.execute = MagicMock(side_effect=ch_errors.NetworkError("ch down"))
    with patch("app.clickhouse.client.get_client", return_value=fake_client):
        with pytest.raises(ch_errors.Error):
            await _bulk_insert("traces", [
                {"trace_id": "t", "span_id": "s", "parent_span_id": "",
                 "name": "n", "start_time": "st", "end_time": "et", "attributes": "{}"}
            ])


# ----------------------------- _retry_insert -----------------------------


@pytest.mark.asyncio
async def test_retry_insert_success_first_try(monkeypatch):
    monkeypatch.setattr(ch.asyncio, "sleep", AsyncMock())
    insert_fn = AsyncMock()
    await _retry_insert(insert_fn, [{"a": 1}], "traces")
    insert_fn.assert_awaited_once_with([{"a": 1}])
    ch.asyncio.sleep.assert_not_awaited()


@pytest.mark.asyncio
async def test_retry_insert_retries_then_succeeds(monkeypatch):
    monkeypatch.setattr(ch.asyncio, "sleep", AsyncMock())
    insert_fn = AsyncMock(
        side_effect=[ch_errors.NetworkError("fail1"), ch_errors.NetworkError("fail2"), None]
    )
    await _retry_insert(insert_fn, [{"a": 1}], "traces")
    assert insert_fn.await_count == 3
    # 两次失败间应触发两次退避 sleep（1s, 2s）
    assert ch.asyncio.sleep.await_count == 2


@pytest.mark.asyncio
async def test_retry_insert_exhausts_retries_and_discards_without_raising(monkeypatch):
    """重试 max_retries 次全失败 → 记录日志但不向上抛（避免拖垮消费循环）"""
    monkeypatch.setattr(ch.asyncio, "sleep", AsyncMock())
    insert_fn = AsyncMock(side_effect=ch_errors.NetworkError("always fails"))
    # 不应抛异常
    await _retry_insert(insert_fn, [{"a": 1}], "traces", max_retries=3)
    assert insert_fn.await_count == 3
    assert ch.asyncio.sleep.await_count == 2  # 最后一次失败不再 sleep


@pytest.mark.asyncio
async def test_retry_insert_empty_data_short_circuits(monkeypatch):
    monkeypatch.setattr(ch.asyncio, "sleep", AsyncMock())
    insert_fn = AsyncMock()
    await _retry_insert(insert_fn, [], "traces")
    insert_fn.assert_not_awaited()


@pytest.mark.asyncio
async def test_retry_insert_non_clickhouse_error_not_retried(monkeypatch):
    """非 ClickHouse 异常不应触发重试（直接传播）"""
    monkeypatch.setattr(ch.asyncio, "sleep", AsyncMock())
    insert_fn = AsyncMock(side_effect=ValueError("bug"))
    with pytest.raises(ValueError):
        await _retry_insert(insert_fn, [{"a": 1}], "traces")
    assert insert_fn.await_count == 1
    ch.asyncio.sleep.assert_not_awaited()


# ----------------------------- _select -----------------------------


@pytest.mark.asyncio
async def test_select_returns_list_of_dicts_zipped_with_columns():
    fake_client = MagicMock()
    # 模拟 ClickHouse 返回的 tuple 列表
    fake_client.execute = MagicMock(return_value=[("m1", 10), ("m2", 20)])
    with patch("app.clickhouse.client.get_client", return_value=fake_client):
        rows = await _select("SELECT a,b", None, ("a", "b"), "test")
    assert rows == [{"a": "m1", "b": 10}, {"a": "m2", "b": 20}]


@pytest.mark.asyncio
async def test_select_returns_empty_list_on_clickhouse_error():
    fake_client = MagicMock()
    fake_client.execute = MagicMock(side_effect=ch_errors.NetworkError("ch down"))
    with patch("app.clickhouse.client.get_client", return_value=fake_client):
        rows = await _select("SELECT a,b", None, ("a", "b"), "test")
    assert rows == []


# ----------------------------- _select_by_filter_or_recent -----------------------------


@pytest.mark.asyncio
async def test_filter_query_uses_asc_order_when_filtered():
    """传 trace_id 时按时间正序返回完整 span 树"""
    captured = {}

    async def fake_select(sql, params, columns, label):
        captured["sql"] = sql
        captured["params"] = params
        return []

    with patch("app.clickhouse.client._select", new=fake_select):
        await _select_by_filter_or_recent(
            "traces", "trace_id", "trace-1", 100, "traces", asc_when_filtered=True
        )
    assert "WHERE trace_id = %(fv)s" in captured["sql"]
    assert "ORDER BY start_time ASC" in captured["sql"]
    assert captured["params"] == {"fv": "trace-1"}


@pytest.mark.asyncio
async def test_recent_query_uses_desc_limit_when_not_filtered():
    captured = {}

    async def fake_select(sql, params, columns, label):
        captured["sql"] = sql
        captured["params"] = params
        return []

    with patch("app.clickhouse.client._select", new=fake_select):
        await _select_by_filter_or_recent(
            "traces", "trace_id", None, 50, "traces"
        )
    assert "ORDER BY start_time DESC" in captured["sql"]
    assert "LIMIT %(lim)s" in captured["sql"]
    assert captured["params"] == {"lim": 50}


@pytest.mark.asyncio
async def test_sessions_query_uses_desc_even_when_filtered():
    """sessions 按 agent_name 过滤时仍按时间倒序"""
    captured = {}

    async def fake_select(sql, params, columns, label):
        captured["sql"] = sql
        return []

    with patch("app.clickhouse.client._select", new=fake_select):
        await _select_by_filter_or_recent(
            "sessions", "agent_name", "agent-a", 100, "sessions",
            asc_when_filtered=False,
        )
    assert "WHERE agent_name = %(fv)s" in captured["sql"]
    assert "ORDER BY created_at DESC" in captured["sql"]


# ----------------------------- query_metrics_compare -----------------------------


@pytest.mark.asyncio
async def test_metrics_compare_without_models_uses_only_time_filter():
    captured = {}

    async def fake_select(sql, params, columns, label):
        captured["sql"] = sql
        captured["params"] = params
        captured["label"] = label
        return []

    with patch("app.clickhouse.client._select", new=fake_select):
        result = await query_metrics_compare(model_names=None, hours=12)
    assert result == []
    assert "now() - INTERVAL %(hours)s HOUR" in captured["sql"]
    assert "model_name IN" not in captured["sql"]
    assert captured["params"] == {"hours": 12}
    assert captured["label"] == "metrics"


@pytest.mark.asyncio
async def test_metrics_compare_with_models_builds_in_clause():
    captured = {}

    async def fake_select(sql, params, columns, label):
        captured["sql"] = sql
        captured["params"] = params
        return [{"model_name": "gpt-5.4"}]

    with patch("app.clickhouse.client._select", new=fake_select):
        result = await query_metrics_compare(
            model_names=["gpt-5.4", "claude-opus-4-8"], hours=24
        )
    assert result == [{"model_name": "gpt-5.4"}]
    assert "model_name IN (%(m0)s, %(m1)s)" in captured["sql"]
    assert captured["params"]["m0"] == "gpt-5.4"
    assert captured["params"]["m1"] == "claude-opus-4-8"
    assert captured["params"]["hours"] == 24


# ----------------------------- query_leaderboard -----------------------------


@pytest.mark.asyncio
async def test_leaderboard_invalid_metric_returns_empty_without_db():
    """未知 metric 直接返回 []，不应访问 ClickHouse"""
    with patch("app.clickhouse.client._select", new=AsyncMock()) as mocked:
        result = await query_leaderboard(metric="bogus", limit=10)
    assert result == []
    mocked.assert_not_awaited()


@pytest.mark.asyncio
async def test_leaderboard_slowest_tool_uses_avgMerge_template():
    captured = {}

    async def fake_select(sql, params, columns, label):
        captured["sql"] = sql
        captured["params"] = params
        captured["columns"] = columns
        captured["label"] = label
        return []

    with patch("app.clickhouse.client._select", new=fake_select):
        await query_leaderboard(metric="slowest_tool", limit=5)
    assert "avgMerge(duration_ms_state)" in captured["sql"]
    assert "ORDER BY avg_duration_ms DESC" in captured["sql"]
    assert captured["params"] == {"lim": 5}
    assert captured["label"] == "leaderboard/slowest_tool"
    assert "error_rate" in captured["columns"]


@pytest.mark.asyncio
async def test_leaderboard_most_tokens_uses_llm_metrics_template():
    captured = {}

    async def fake_select(sql, params, columns, label):
        captured["sql"] = sql
        captured["label"] = label
        return []

    with patch("app.clickhouse.client._select", new=fake_select):
        await query_leaderboard(metric="most_tokens", limit=10)
    assert "FROM llm_metrics" in captured["sql"]
    assert "ORDER BY total_tokens DESC" in captured["sql"]
    assert captured["label"] == "leaderboard/most_tokens"


@pytest.mark.asyncio
async def test_leaderboard_most_failed_has_having_clause():
    captured = {}

    async def fake_select(sql, params, columns, label):
        captured["sql"] = sql
        return []

    with patch("app.clickhouse.client._select", new=fake_select):
        await query_leaderboard(metric="most_failed", limit=10)
    assert "HAVING error_count > 0" in captured["sql"]
    assert "ORDER BY error_count DESC" in captured["sql"]


# ----------------------------- get_client 单例 -----------------------------


def test_get_client_caches_singleton(monkeypatch):
    ch._client = None  # 重置模块级缓存
    fake = MagicMock()
    monkeypatch.setattr(
        "app.clickhouse.client.SyncClient", lambda **kwargs: fake
    )
    c1 = get_client()
    c2 = get_client()
    assert c1 is c2 is fake
    # 复位避免污染其他用例
    ch._client = None
