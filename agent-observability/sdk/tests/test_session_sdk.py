"""
SessionSDK 单元测试

验证 Session 生命周期、自动聚合、成本估算。
"""

import asyncio
from datetime import datetime

import pytest

from agent_insight_sdk import SessionSDK, TraceAPI, clear_current_context, get_current_context
from agent_insight_sdk.context import TraceContext
from agent_insight_sdk.session_sdk import SessionContext
from agent_insight_sdk.uploader import SpanData


@pytest.mark.asyncio
async def test_start_session_sets_context(fake_uploader):
    session_sdk = SessionSDK(fake_uploader)
    sess = session_sdk.start_session(name="test", agent_name="a", user_input="hi")

    assert sess.session_id is not None
    assert sess.session_id == sess.trace_context.trace_id
    assert get_current_context().trace_id == sess.session_id

    session_sdk.end_session(sess)
    clear_current_context()


@pytest.mark.asyncio
async def test_session_aggregation(fake_uploader):
    session_sdk = SessionSDK(fake_uploader)
    sess = session_sdk.start_session(name="test", agent_name="a", user_input="hi")

    # tool span
    await fake_uploader.submit(
        SpanData(
            trace_id=sess.session_id,
            span_id="span-tool",
            name="tool_call",
            start_time="2026-01-01T00:00:00",
            end_time="2026-01-01T00:00:01",
            span_type="tool_call",
        )
    )

    # llm metrics span
    await fake_uploader.submit(
        SpanData(
            trace_id=sess.session_id,
            span_id="span-llm",
            name="llm_metrics",
            start_time="2026-01-01T00:00:00",
            end_time="2026-01-01T00:00:01",
            span_type="llm_metrics",
            attributes={
                "model_name": "gpt-5.4-mini",
                "input_tokens": 1000,
                "output_tokens": 500,
            },
        )
    )

    session_sdk.end_session(sess, final_response="done", status="completed")
    await asyncio.sleep(0.05)

    session_spans = [s for s in fake_uploader.spans if s["span_type"] == "session"]
    assert len(session_spans) == 1
    s = session_spans[0]

    assert s["trace_id"] == sess.session_id
    assert s["session_id"] == sess.session_id
    assert s["agent_name"] == "a"
    assert s["user_input"] == "hi"
    assert s["final_response"] == "done"
    assert s["status"] == "completed"
    assert s["total_spans"] == 2
    assert s["total_tokens"] == 1500
    # gpt-5.4-mini: 1000 * 0.75/1M + 500 * 4.50/1M = 0.003
    assert abs(s["total_cost_usd"] - 0.003) < 1e-9
    assert s["duration_ms"] >= 0

    session_sdk.close()
    clear_current_context()


@pytest.mark.asyncio
async def test_session_context_manager(fake_uploader):
    session_sdk = SessionSDK(fake_uploader)

    with session_sdk.session(name="cm", agent_name="agent") as sess:
        assert get_current_context().trace_id == sess.session_id

    await asyncio.sleep(0.05)

    session_spans = [s for s in fake_uploader.spans if s["span_type"] == "session"]
    assert len(session_spans) == 1
    assert session_spans[0]["total_spans"] == 0

    session_sdk.close()
    clear_current_context()


@pytest.mark.asyncio
async def test_unknown_model_cost_zero(fake_uploader):
    """未配置价格的模型，cost 应返回 0.0"""
    session_sdk = SessionSDK(fake_uploader)
    sess = session_sdk.start_session(name="test")

    await fake_uploader.submit(
        SpanData(
            trace_id=sess.session_id,
            span_id="span-llm",
            name="llm_metrics",
            start_time="2026-01-01T00:00:00",
            end_time="2026-01-01T00:00:01",
            span_type="llm_metrics",
            attributes={
                "model_name": "unknown-model-xyz",
                "input_tokens": 9999,
                "output_tokens": 9999,
            },
        )
    )

    session_sdk.end_session(sess)
    await asyncio.sleep(0.05)

    s = [s for s in fake_uploader.spans if s["span_type"] == "session"][0]
    assert s["total_cost_usd"] == 0.0
    # token 仍然应该被统计
    assert s["total_tokens"] == 19998

    session_sdk.close()
    clear_current_context()


@pytest.mark.asyncio
async def test_end_session_unknown_id_is_safe(fake_uploader):
    """end_session 传入未知的 session_id 时应安全返回，不抛异常"""
    session_sdk = SessionSDK(fake_uploader)

    fake_ctx = SessionContext(
        session_id="nonexistent",
        name="ghost",
        agent_name="",
        user_input="",
        start_time=datetime.utcnow(),
        trace_context=TraceContext(name="ghost"),
    )
    # 不应抛异常
    session_sdk.end_session(fake_ctx)

    # 没有 session span 被上报
    session_spans = [s for s in fake_uploader.spans if s["span_type"] == "session"]
    assert len(session_spans) == 0

    session_sdk.close()


@pytest.mark.asyncio
async def test_close_stops_aggregation(fake_uploader):
    """close() 后新提交的 span 不应被聚合"""
    session_sdk = SessionSDK(fake_uploader)
    sess = session_sdk.start_session(name="test")

    # close 后 session 从内部字典中清除
    session_sdk.close()

    # 提交一条 llm_metrics span，不应被聚合（因为 session 已不在 _sessions 中）
    await fake_uploader.submit(
        SpanData(
            trace_id=sess.session_id,
            span_id="span-llm",
            name="llm_metrics",
            start_time="2026-01-01T00:00:00",
            end_time="2026-01-01T00:00:01",
            span_type="llm_metrics",
            attributes={
                "model_name": "gpt-5.4-mini",
                "input_tokens": 100,
                "output_tokens": 50,
            },
        )
    )

    # end_session 也不会上报（session 已被 close 清除）
    session_sdk.end_session(sess)
    await asyncio.sleep(0.05)

    session_spans = [s for s in fake_uploader.spans if s["span_type"] == "session"]
    assert len(session_spans) == 0

    clear_current_context()


@pytest.mark.asyncio
async def test_concurrent_sessions_no_cross_contamination(fake_uploader):
    """多个并发 session 的聚合互不干扰"""
    session_sdk = SessionSDK(fake_uploader)

    sess_a = session_sdk.start_session(name="a", agent_name="agent_a")
    sess_b = session_sdk.start_session(name="b", agent_name="agent_b")

    # 为 session A 提交 span
    await fake_uploader.submit(
        SpanData(
            trace_id=sess_a.session_id,
            span_id="a-llm",
            name="llm_metrics",
            start_time="2026-01-01T00:00:00",
            end_time="2026-01-01T00:00:01",
            span_type="llm_metrics",
            attributes={
                "model_name": "gpt-5.4",
                "input_tokens": 100,
                "output_tokens": 200,
            },
        )
    )

    # 为 session B 提交 span
    await fake_uploader.submit(
        SpanData(
            trace_id=sess_b.session_id,
            span_id="b-llm",
            name="llm_metrics",
            start_time="2026-01-01T00:00:00",
            end_time="2026-01-01T00:00:01",
            span_type="llm_metrics",
            attributes={
                "model_name": "claude-haiku-4-5",
                "input_tokens": 10,
                "output_tokens": 20,
            },
        )
    )

    session_sdk.end_session(sess_a)
    session_sdk.end_session(sess_b)
    await asyncio.sleep(0.05)

    sessions = {s["session_id"]: s for s in fake_uploader.spans if s["span_type"] == "session"}
    assert len(sessions) == 2

    sa = sessions[sess_a.session_id]
    sb = sessions[sess_b.session_id]

    assert sa["total_spans"] == 1
    assert sa["total_tokens"] == 300
    assert sa["agent_name"] == "agent_a"

    assert sb["total_spans"] == 1
    assert sb["total_tokens"] == 30
    assert sb["agent_name"] == "agent_b"

    session_sdk.close()
    clear_current_context()


@pytest.mark.asyncio
async def test_custom_pricing(fake_uploader):
    session_sdk = SessionSDK(
        fake_uploader,
        pricing={"my-model": {"input": 1.0, "output": 2.0}},
    )
    sess = session_sdk.start_session(name="test")

    await fake_uploader.submit(
        SpanData(
            trace_id=sess.session_id,
            span_id="span-llm",
            name="llm_metrics",
            start_time="2026-01-01T00:00:00",
            end_time="2026-01-01T00:00:01",
            span_type="llm_metrics",
            attributes={
                "model_name": "my-model",
                "input_tokens": 1_000_000,
                "output_tokens": 500_000,
            },
        )
    )

    session_sdk.end_session(sess)
    await asyncio.sleep(0.05)

    s = [s for s in fake_uploader.spans if s["span_type"] == "session"][0]
    assert abs(s["total_cost_usd"] - (1.0 + 1.0)) < 1e-9

    session_sdk.close()
    clear_current_context()
