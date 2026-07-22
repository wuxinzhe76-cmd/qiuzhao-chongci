"""
集成测试 — 端到端验证多模块协作

场景1: SessionSDK + LLMInterceptor + ToolSDK 联合工作
       验证 session 聚合的 spans/tokens/cost 正确
场景2: 跨模块上下文传播 TraceAPI → ToolSDK → LLMInterceptor
       验证 parent_span_id 链路完整
"""

import asyncio
from unittest.mock import MagicMock

import pytest

from agent_insight_sdk import (
    LLMInterceptor,
    SessionSDK,
    ToolSDK,
    TraceAPI,
    clear_current_context,
    get_current_context,
    set_current_context,
)
from agent_insight_sdk.context import TraceContext
from agent_insight_sdk.uploader import SpanData


class _FakeOpenAIClient:
    def __init__(self, response):
        self.chat = MagicMock()
        self.chat.completions = MagicMock()
        self.chat.completions.create = MagicMock(return_value=response)


@pytest.mark.asyncio
async def test_end_to_end_session_with_llm_and_tool(fake_uploader):
    """端到端：一次 session 内包含 LLM 调用 + Tool 调用，验证聚合结果"""
    # 准备 LLM mock 响应
    llm_response = MagicMock()
    llm_response.usage.prompt_tokens = 50
    llm_response.usage.completion_tokens = 100
    llm_response.choices = [MagicMock(message=MagicMock(content="result"))]

    client = _FakeOpenAIClient(llm_response)
    interceptor = LLMInterceptor(fake_uploader)
    wrapped_client = interceptor.wrap(client)

    tool_sdk = ToolSDK(fake_uploader)
    session_sdk = SessionSDK(fake_uploader)

    with session_sdk.session(
        name="e2e_query",
        agent_name="math_agent",
        user_input="calculate 2+3",
    ) as sess:
        # 1. Tool 调用
        @tool_sdk.instrument(name="calculator", tool_type="math")
        def calculate(expr):
            return eval(expr)

        tool_result = calculate("2 + 3")

        # 2. LLM 调用
        llm_result = wrapped_client.chat.completions.create(
            model="gpt-5.4-mini",
            messages=[{"role": "user", "content": "summarize"}],
        )

        # 等待 create_task 调度的 submit 完成，确保 observer 在 session 结束前聚合
        await asyncio.sleep(0.1)

    await asyncio.sleep(0.1)

    assert tool_result == 5
    assert llm_result is llm_response

    # 验证 session 聚合
    session_spans = [s for s in fake_uploader.spans if s["span_type"] == "session"]
    assert len(session_spans) == 1
    s = session_spans[0]

    assert s["status"] == "completed"
    # 1 个 tool_call + 3 个 llm spans (trace + llm_metrics + prompt) = 4
    assert s["total_spans"] == 4
    # 50 input + 100 output = 150
    assert s["total_tokens"] == 150
    # gpt-5.4-mini: 50 * 0.75/1M + 100 * 4.50/1M = 0.0004875
    assert abs(s["total_cost_usd"] - (50 * 0.75 / 1_000_000 + 100 * 4.50 / 1_000_000)) < 1e-9

    interceptor.unwrap()
    session_sdk.close()
    clear_current_context()


@pytest.mark.asyncio
async def test_cross_module_context_propagation(fake_uploader):
    """跨模块上下文传播：TraceAPI → ToolSDK → LLMInterceptor

    验证 parent_span_id 链路完整：
      root (TraceAPI)
        └── tool span (ToolSDK)
              └── llm span (LLMInterceptor)
    """
    # 准备 LLM mock
    llm_response = MagicMock()
    llm_response.usage.prompt_tokens = 10
    llm_response.usage.completion_tokens = 20
    llm_response.choices = [MagicMock(message=MagicMock(content="ok"))]

    client = _FakeOpenAIClient(llm_response)
    interceptor = LLMInterceptor(fake_uploader)
    wrapped_client = interceptor.wrap(client)

    tool_sdk = ToolSDK(fake_uploader)
    trace_api = TraceAPI(fake_uploader)

    # 1. 开始 root trace
    root_ctx = trace_api.start_trace("agent_workflow")

    # 2. Tool 调用（应成为 root 的子 span）
    @tool_sdk.instrument(name="fetch_data", tool_type="api")
    def fetch_data():
        # 3. Tool 内部发起 LLM 调用（应成为 tool span 的子 span）
        return wrapped_client.chat.completions.create(
            model="gpt-5.4",
            messages=[{"role": "user", "content": "process"}],
        )

    fetch_data()
    # 等待 create_task 调度的 submit 完成
    await asyncio.sleep(0.1)
    trace_api.end_trace()

    await asyncio.sleep(0.1)

    # 验证 parent_span_id 链路
    all_spans = fake_uploader.spans
    root_spans = [s for s in all_spans if s["name"] == "agent_workflow"]
    tool_spans = [s for s in all_spans if s["span_type"] == "tool_call"]
    llm_trace_spans = [s for s in all_spans if s["name"] == "llm_call" and s["span_type"] == "trace"]

    assert len(root_spans) == 1
    assert len(tool_spans) == 1
    assert len(llm_trace_spans) == 1

    root_span_id = root_spans[0]["span_id"]
    tool_span_id = tool_spans[0]["span_id"]
    llm_span_id = llm_trace_spans[0]["span_id"]

    # root 的 parent 为空
    assert root_spans[0]["parent_span_id"] == ""
    # tool 的 parent 是 root
    assert tool_spans[0]["parent_span_id"] == root_span_id
    # llm 的 parent 是 tool
    assert llm_trace_spans[0]["parent_span_id"] == tool_span_id

    # 所有 span 共享同一个 trace_id
    trace_ids = {s["trace_id"] for s in all_spans}
    assert len(trace_ids) == 1

    interceptor.unwrap()
    clear_current_context()


@pytest.mark.asyncio
async def test_session_with_trace_api_and_tool(fake_uploader):
    """SessionSDK + TraceAPI + ToolSDK 联合使用

    验证 session 内通过 TraceAPI 创建的 span 也被正确聚合
    """
    trace_api = TraceAPI(fake_uploader)
    tool_sdk = ToolSDK(fake_uploader)
    session_sdk = SessionSDK(fake_uploader)

    with session_sdk.session(name="mixed", agent_name="agent") as sess:
        # 保存 session 上下文（TraceAPI.end_span 会清空全局上下文）
        session_ctx = get_current_context()

        # 用 TraceAPI 创建 span
        span = trace_api.start_span("retrieval", attributes={"query": "test"})
        trace_api.end_span(span, attributes={"results": 5})

        # 恢复 session 上下文，使后续 ToolSDK span 共享同一 trace_id
        set_current_context(session_ctx)

        # 用 ToolSDK 创建 span
        @tool_sdk.instrument(name="compute")
        def compute(x):
            return x + 1

        compute(41)

        # 等待 create_task 调度的 submit 完成
        await asyncio.sleep(0.1)

    await asyncio.sleep(0.1)

    session_spans = [s for s in fake_uploader.spans if s["span_type"] == "session"]
    assert len(session_spans) == 1
    s = session_spans[0]

    # 2 个子 span: retrieval(trace) + compute(tool_call)
    assert s["total_spans"] == 2

    session_sdk.close()
    clear_current_context()
