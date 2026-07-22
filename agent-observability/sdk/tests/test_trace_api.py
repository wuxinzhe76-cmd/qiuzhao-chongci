"""
TraceAPI 单元测试
"""

import asyncio

import pytest

from agent_insight_sdk import TraceAPI, clear_current_context, get_current_context


@pytest.mark.asyncio
async def test_trace_api_lifecycle(fake_uploader):
    api = TraceAPI(fake_uploader)

    ctx = api.start_trace("user_task")
    assert get_current_context().trace_id == ctx.trace_id

    span = api.start_span("step1", attributes={"key": "value"})
    assert span.parent_span_id == ctx.span_id

    api.end_span(span, attributes={"result": "ok"})
    api.end_trace(attributes={"status": "completed"})

    await asyncio.sleep(0.05)

    trace_spans = [s for s in fake_uploader.spans if s["span_type"] == "trace"]
    assert len(trace_spans) == 2  # step1 + user_task

    clear_current_context()


@pytest.mark.asyncio
async def test_trace_api_nested_spans(fake_uploader):
    api = TraceAPI(fake_uploader)

    root = api.start_trace("root")
    child = api.start_span("child")
    grandchild = api.start_span("grandchild")

    assert child.parent_span_id == root.span_id
    assert grandchild.parent_span_id == child.span_id

    api.end_span(grandchild)
    api.end_span(child)
    api.end_trace()

    await asyncio.sleep(0.05)

    names = [s["name"] for s in fake_uploader.spans if s["span_type"] == "trace"]
    assert "child" in names
    assert "grandchild" in names
    assert "root" in names

    clear_current_context()


@pytest.mark.asyncio
async def test_trace_api_end_span_without_context(fake_uploader):
    api = TraceAPI(fake_uploader)

    # 不传 ctx 且当前无上下文时不应抛错
    clear_current_context()
    api.end_span()

    await asyncio.sleep(0.05)
    trace_spans = [s for s in fake_uploader.spans if s["span_type"] == "trace"]
    assert len(trace_spans) == 0


@pytest.mark.asyncio
async def test_trace_api_end_trace_without_start(fake_uploader):
    """未 start_trace 时 end_trace 不应抛异常"""
    api = TraceAPI(fake_uploader)
    clear_current_context()

    api.end_trace(attributes={"status": "completed"})

    await asyncio.sleep(0.05)
    trace_spans = [s for s in fake_uploader.spans if s["span_type"] == "trace"]
    assert len(trace_spans) == 0


@pytest.mark.asyncio
async def test_trace_api_custom_trace_id(fake_uploader):
    """start_trace 传入自定义 trace_id 时应使用它"""
    api = TraceAPI(fake_uploader)

    custom_id = "my-custom-trace-id-12345"
    ctx = api.start_trace("custom", trace_id=custom_id)

    assert ctx.trace_id == custom_id
    assert get_current_context().trace_id == custom_id

    api.end_trace()
    await asyncio.sleep(0.05)

    trace_spans = [s for s in fake_uploader.spans if s["span_type"] == "trace"]
    assert trace_spans[0]["trace_id"] == custom_id

    clear_current_context()


@pytest.mark.asyncio
async def test_trace_api_span_restores_parent_context(fake_uploader):
    """end_span 后当前上下文应恢复为父 span"""
    api = TraceAPI(fake_uploader)

    root = api.start_trace("root")
    child = api.start_span("child")

    assert get_current_context().span_id == child.span_id

    api.end_span(child)

    # 结束 child 后，当前上下文应恢复为 root
    assert get_current_context().span_id == root.span_id

    api.end_trace()
    clear_current_context()
