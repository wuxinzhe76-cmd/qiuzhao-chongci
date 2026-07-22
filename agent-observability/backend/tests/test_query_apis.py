"""查询类 API 端点测试。

覆盖 6 个 GET 接口：
  - /traces, /sessions, /prompts, /tool-calls, /metrics/compare, /leaderboard
  - 默认参数 / 带过滤参数 / 参数边界校验（422）
  - 查询层异常时接口返回 status=error 且 data=[]（不 500）
  - models 逗号分隔解析、metric 透传
"""

from unittest.mock import AsyncMock, patch

import pytest


# ----------------------------- /traces -----------------------------


@pytest.mark.asyncio
async def test_get_traces_default(api_client):
    with patch(
        "app.api.traces.query_traces",
        new=AsyncMock(return_value=[{"trace_id": "t1"}]),
    ) as mocked:
        resp = await api_client.get("/api/v1/traces")

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "success"
    assert body["count"] == 1
    assert body["data"] == [{"trace_id": "t1"}]
    mocked.assert_awaited_once_with(trace_id=None, limit=100)


@pytest.mark.asyncio
async def test_get_traces_with_trace_id_and_limit(api_client):
    with patch(
        "app.api.traces.query_traces",
        new=AsyncMock(return_value=[]),
    ) as mocked:
        resp = await api_client.get(
            "/api/v1/traces", params={"trace_id": "trace-1", "limit": 5}
        )

    assert resp.status_code == 200
    mocked.assert_awaited_once_with(trace_id="trace-1", limit=5)


@pytest.mark.asyncio
async def test_get_traces_limit_lower_bound_422(api_client):
    """limit < 1 → 422"""
    resp = await api_client.get("/api/v1/traces", params={"limit": 0})
    assert resp.status_code == 422


@pytest.mark.asyncio
async def test_get_traces_limit_upper_bound_422(api_client):
    """limit > 1000 → 422"""
    resp = await api_client.get("/api/v1/traces", params={"limit": 1001})
    assert resp.status_code == 422


# ----------------------------- /sessions -----------------------------


@pytest.mark.asyncio
async def test_get_sessions_default(api_client):
    with patch(
        "app.api.traces.query_sessions",
        new=AsyncMock(return_value=[{"session_id": "s1"}]),
    ) as mocked:
        resp = await api_client.get("/api/v1/sessions")

    assert resp.status_code == 200
    body = resp.json()
    assert body["count"] == 1
    assert body["data"] == [{"session_id": "s1"}]
    mocked.assert_awaited_once_with(limit=100, agent_name=None)


@pytest.mark.asyncio
async def test_get_sessions_filter_by_agent(api_client):
    with patch("app.api.traces.query_sessions", new=AsyncMock(return_value=[])) as mocked:
        resp = await api_client.get(
            "/api/v1/sessions", params={"agent_name": "demo-agent", "limit": 10}
        )

    assert resp.status_code == 200
    mocked.assert_awaited_once_with(limit=10, agent_name="demo-agent")


# ----------------------------- /prompts -----------------------------


@pytest.mark.asyncio
async def test_get_prompts_with_trace_id(api_client):
    with patch(
        "app.api.prompts.query_prompts",
        new=AsyncMock(return_value=[{"prompt": "hi"}]),
    ) as mocked:
        resp = await api_client.get("/api/v1/prompts", params={"trace_id": "t9"})

    assert resp.status_code == 200
    mocked.assert_awaited_once_with(trace_id="t9", limit=100)


# ----------------------------- /tool-calls -----------------------------


@pytest.mark.asyncio
async def test_get_tool_calls_default(api_client):
    with patch(
        "app.api.prompts.query_tool_calls",
        new=AsyncMock(return_value=[{"tool_name": "calc"}]),
    ) as mocked:
        resp = await api_client.get("/api/v1/tool-calls")

    assert resp.status_code == 200
    body = resp.json()
    assert body["data"] == [{"tool_name": "calc"}]
    mocked.assert_awaited_once_with(trace_id=None, limit=100)


# ----------------------------- /metrics/compare -----------------------------


@pytest.mark.asyncio
async def test_metrics_compare_default(api_client):
    with patch(
        "app.api.metrics.query_metrics_compare",
        new=AsyncMock(return_value=[{"model_name": "gpt-5.4"}]),
    ) as mocked:
        resp = await api_client.get("/api/v1/metrics/compare")

    assert resp.status_code == 200
    body = resp.json()
    assert body["count"] == 1
    # 默认 models=None, hours=24
    mocked.assert_awaited_once_with(model_names=None, hours=24)


@pytest.mark.asyncio
async def test_metrics_compare_parses_csv_models(api_client):
    """models 逗号分隔字符串应解析为去空白后的列表"""
    with patch(
        "app.api.metrics.query_metrics_compare",
        new=AsyncMock(return_value=[]),
    ) as mocked:
        resp = await api_client.get(
            "/api/v1/metrics/compare",
            params={"models": "gpt-5.4, claude-opus-4-8", "hours": 48},
        )

    assert resp.status_code == 200
    mocked.assert_awaited_once_with(
        model_names=["gpt-5.4", "claude-opus-4-8"], hours=48
    )


@pytest.mark.asyncio
async def test_metrics_compare_hours_bounds(api_client):
    # hours < 1 → 422
    resp = await api_client.get("/api/v1/metrics/compare", params={"hours": 0})
    assert resp.status_code == 422
    # hours > 720 → 422
    resp = await api_client.get("/api/v1/metrics/compare", params={"hours": 721})
    assert resp.status_code == 422


# ----------------------------- /leaderboard -----------------------------


@pytest.mark.asyncio
async def test_leaderboard_default_metric(api_client):
    with patch(
        "app.api.leaderboard.query_leaderboard",
        new=AsyncMock(return_value=[{"tool_name": "search"}]),
    ) as mocked:
        resp = await api_client.get("/api/v1/leaderboard")

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "success"
    assert body["metric"] == "slowest_tool"
    assert body["count"] == 1
    # 默认 metric=slowest_tool, limit=10
    mocked.assert_awaited_once_with(metric="slowest_tool", limit=10)


@pytest.mark.asyncio
async def test_leaderboard_custom_metric_and_limit(api_client):
    with patch("app.api.leaderboard.query_leaderboard", new=AsyncMock(return_value=[])) as mocked:
        resp = await api_client.get(
            "/api/v1/leaderboard", params={"metric": "most_tokens", "limit": 20}
        )

    assert resp.status_code == 200
    body = resp.json()
    assert body["metric"] == "most_tokens"
    mocked.assert_awaited_once_with(metric="most_tokens", limit=20)


@pytest.mark.asyncio
async def test_leaderboard_limit_bounds(api_client):
    # limit < 1 → 422
    resp = await api_client.get("/api/v1/leaderboard", params={"limit": 0})
    assert resp.status_code == 422
    # limit > 50 → 422
    resp = await api_client.get("/api/v1/leaderboard", params={"limit": 51})
    assert resp.status_code == 422


# ----------------------------- 异常路径 -----------------------------


@pytest.mark.asyncio
async def test_traces_returns_error_status_on_query_exception(api_client):
    """查询层抛异常时，接口应吞掉异常并返回 status=error（而非 500）"""
    with patch(
        "app.api.traces.query_traces",
        new=AsyncMock(side_effect=RuntimeError("ch unreachable")),
    ):
        resp = await api_client.get("/api/v1/traces")

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "error"
    assert "ch unreachable" in body["message"]
    assert body["data"] == []


@pytest.mark.asyncio
async def test_leaderboard_returns_error_status_on_query_exception(api_client):
    with patch(
        "app.api.leaderboard.query_leaderboard",
        new=AsyncMock(side_effect=RuntimeError("boom")),
    ):
        resp = await api_client.get("/api/v1/leaderboard")

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "error"
    assert body["data"] == []


@pytest.mark.asyncio
async def test_metrics_compare_returns_error_status_on_query_exception(api_client):
    with patch(
        "app.api.metrics.query_metrics_compare",
        new=AsyncMock(side_effect=RuntimeError("boom")),
    ):
        resp = await api_client.get("/api/v1/metrics/compare")

    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "error"
    assert body["data"] == []
