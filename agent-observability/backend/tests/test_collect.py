"""数据收集 API (`POST /api/v1/collect`) 测试。

覆盖业务场景：
  - 健康检查
  - 5 种 span_type 的批量上报成功路径
  - 按 span_type 校验必填字段（缺失 → 400）
  - 缺省 span_type 回退为 trace
  - 未知 span_type 回退为 trace 的必填规则
  - 非对象元素校验
  - 空数组校验
  - Kafka 投递失败 → 500
"""

from unittest.mock import AsyncMock, patch

import pytest
from fastapi import HTTPException

from app.api.collect import REQUIRED_FIELDS, validate_item


# ----------------------------- 健康检查 -----------------------------


@pytest.mark.asyncio
async def test_health_check(api_client):
    resp = await api_client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


# ----------------------------- validate_item 单元测试 -----------------------------


def test_validate_item_accepts_valid_trace():
    item = {
        "trace_id": "t1",
        "span_id": "s1",
        "name": "root",
        "start_time": "2026-07-16T00:00:00",
        "end_time": "2026-07-16T00:00:01",
        "span_type": "trace",
    }
    # 不抛异常即通过
    validate_item(item)


@pytest.mark.parametrize("span_type, required", list(REQUIRED_FIELDS.items()))
def test_required_fields_table_covers_all_span_types(span_type, required):
    """每种 span_type 的必填字段定义应非空且为列表"""
    assert isinstance(required, list) and required


def test_validate_item_rejects_non_dict():
    with pytest.raises(HTTPException) as exc:
        validate_item("not-a-dict")  # type: ignore[arg-type]
    assert exc.value.status_code == 400
    assert "JSON object" in exc.value.detail


def test_validate_item_missing_fields_for_trace():
    with pytest.raises(HTTPException) as exc:
        validate_item({"span_id": "s1", "span_type": "trace"})  # 缺 trace_id/name/时间
    assert exc.value.status_code == 400
    detail = exc.value.detail
    assert "trace" in detail
    assert "trace_id" in detail


def test_validate_item_missing_fields_for_session():
    # session 必填 session_id 与 trace_id
    with pytest.raises(HTTPException) as exc:
        validate_item({"span_type": "session", "trace_id": "t1"})  # 缺 session_id
    assert exc.value.status_code == 400
    assert "session_id" in exc.value.detail


def test_validate_item_missing_fields_for_llm_metrics():
    with pytest.raises(HTTPException) as exc:
        validate_item({"span_type": "llm_metrics", "trace_id": "t1"})  # 缺 span_id
    assert exc.value.status_code == 400
    assert "span_id" in exc.value.detail


def test_validate_item_default_span_type_is_trace():
    """未提供 span_type 时按 trace 校验必填字段"""
    with pytest.raises(HTTPException) as exc:
        validate_item({"span_id": "s1", "name": "n"})  # 缺 trace_id/时间，无 span_type
    assert exc.value.status_code == 400
    assert "trace" in exc.value.detail


def test_validate_item_unknown_span_type_falls_back_to_trace():
    """未知 span_type 走 trace 的必填规则"""
    with pytest.raises(HTTPException) as exc:
        validate_item({"span_type": "weird-type", "span_id": "s1"})
    assert exc.value.status_code == 400
    # 回退到 trace，应要求 trace_id
    assert "trace_id" in exc.value.detail


# ----------------------------- /collect 端到端 -----------------------------


def _full_batch():
    """构造覆盖 5 种 span_type 的合法批量数据"""
    return [
        {
            "trace_id": "trace-1",
            "span_id": "span-1",
            "parent_span_id": "",
            "name": "root",
            "start_time": "2026-07-16T10:00:00.000",
            "end_time": "2026-07-16T10:00:01.000",
            "span_type": "trace",
            "attributes": {"model": "gpt-5.4"},
        },
        {
            "trace_id": "trace-1",
            "span_id": "span-2",
            "span_type": "llm_metrics",
            "attributes": {
                "model_name": "gpt-5.4",
                "prefill_ms": 200,
                "decode_ms": 600,
                "input_tokens": 1000,
                "output_tokens": 500,
                "tps": 833.3,
            },
        },
        {
            "trace_id": "trace-1",
            "span_id": "span-3",
            "span_type": "prompt",
            "model_name": "gpt-5.4",
            "prompt": "hello",
            "response": "hi",
            "input_tokens": 10,
            "output_tokens": 5,
            "latency_ms": 800,
            "stream": False,
            "status": "success",
        },
        {
            "trace_id": "trace-1",
            "span_id": "span-4",
            "span_type": "tool_call",
            "tool_name": "calculator",
            "tool_type": "calc",
            "input_data": "{}",
            "output_data": "42",
            "duration_ms": 120,
            "status": "success",
        },
        {
            "session_id": "session-1",
            "trace_id": "trace-1",
            "span_type": "session",
            "agent_name": "demo-agent",
            "user_input": "hello",
            "final_response": "hi",
            "total_spans": 4,
            "total_tokens": 2300,
            "total_cost_usd": 0.05,
            "duration_ms": 3000,
            "status": "completed",
        },
    ]


@pytest.mark.asyncio
async def test_collect_accepts_full_batch(api_client):
    with patch("app.api.collect.send_batch", new=AsyncMock()) as mocked:
        resp = await api_client.post("/api/v1/collect", json=_full_batch())

    assert resp.status_code == 202
    body = resp.json()
    assert body["status"] == "accepted"
    assert body["count"] == 5
    assert "message" in body
    # send_batch 被调用一次，且收到完整数据
    mocked.assert_awaited_once()
    sent = mocked.await_args.args[0]
    assert len(sent) == 5
    assert {item["span_type"] for item in sent} == {
        "trace", "llm_metrics", "prompt", "tool_call", "session",
    }


@pytest.mark.asyncio
async def test_collect_rejects_empty_array(api_client):
    with patch("app.api.collect.send_batch", new=AsyncMock()) as mocked:
        resp = await api_client.post("/api/v1/collect", json=[])

    assert resp.status_code == 400
    assert "Empty" in resp.json()["detail"]
    mocked.assert_not_awaited()


@pytest.mark.asyncio
async def test_collect_rejects_missing_required_field(api_client):
    """第二条数据 llm_metrics 缺 span_id → 整批返回 400"""
    batch = _full_batch()
    batch[1].pop("span_id")

    with patch("app.api.collect.send_batch", new=AsyncMock()) as mocked:
        resp = await api_client.post("/api/v1/collect", json=batch)

    assert resp.status_code == 400
    assert "span_id" in resp.json()["detail"]
    mocked.assert_not_awaited()


@pytest.mark.asyncio
async def test_collect_default_span_type_accepted(api_client):
    """不传 span_type 但满足 trace 必填字段 → 202"""
    batch = [
        {
            "trace_id": "trace-x",
            "span_id": "span-x",
            "name": "root",
            "start_time": "2026-07-16T10:00:00.000",
            "end_time": "2026-07-16T10:00:01.000",
        }
    ]
    with patch("app.api.collect.send_batch", new=AsyncMock()) as mocked:
        resp = await api_client.post("/api/v1/collect", json=batch)

    assert resp.status_code == 202
    assert resp.json()["count"] == 1
    mocked.assert_awaited_once()


@pytest.mark.asyncio
async def test_collect_kafka_failure_returns_500(api_client):
    """send_batch 抛异常 → 返回 500，不向外暴露 202"""
    with patch(
        "app.api.collect.send_batch",
        new=AsyncMock(side_effect=RuntimeError("kafka down")),
    ):
        resp = await api_client.post("/api/v1/collect", json=_full_batch())

    assert resp.status_code == 500
    assert "kafka down" in resp.json()["detail"]


@pytest.mark.asyncio
async def test_collect_non_array_body_returns_422(api_client):
    """非数组请求体由 FastAPI 校验拦截 → 422（不到达 handler）"""
    with patch("app.api.collect.send_batch", new=AsyncMock()) as mocked:
        resp = await api_client.post("/api/v1/collect", json={"not": "an array"})

    assert resp.status_code == 422
    mocked.assert_not_awaited()
