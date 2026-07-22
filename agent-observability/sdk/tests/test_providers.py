"""
Provider Adapter / LLMInterceptor 单元测试
"""

import asyncio
from unittest.mock import MagicMock

import pytest

from agent_insight_sdk import LLMInterceptor, clear_current_context, set_current_context
from agent_insight_sdk.context import TraceContext


class _FakeOpenAIClient:
    """模拟 OpenAI 兼容客户端"""

    def __init__(self, response):
        self.chat = MagicMock()
        self.chat.completions = MagicMock()
        self.chat.completions.create = MagicMock(return_value=response)


class _FakeAnthropicClient:
    """模拟 Anthropic 客户端"""

    def __init__(self, response):
        self.messages = MagicMock()
        self.messages.create = MagicMock(return_value=response)


@pytest.mark.asyncio
async def test_openai_compatible_interceptor_non_stream(fake_uploader):
    response = MagicMock()
    response.usage.prompt_tokens = 10
    response.usage.completion_tokens = 20
    response.choices = [MagicMock(message=MagicMock(content="hello"))]

    client = _FakeOpenAIClient(response)
    interceptor = LLMInterceptor(fake_uploader)
    wrapped = interceptor.wrap(client)

    root = TraceContext(name="agent_task")
    set_current_context(root)

    result = wrapped.chat.completions.create(
        model="gpt-5.4-mini",
        messages=[{"role": "user", "content": "hi"}],
    )

    await asyncio.sleep(0.05)

    assert result is response

    trace_spans = [s for s in fake_uploader.spans if s["span_type"] == "trace"]
    metrics_spans = [s for s in fake_uploader.spans if s["span_type"] == "llm_metrics"]
    prompt_spans = [s for s in fake_uploader.spans if s["span_type"] == "prompt"]

    assert len(trace_spans) == 1
    assert trace_spans[0]["attributes"]["model"] == "gpt-5.4-mini"

    assert len(metrics_spans) == 1
    attrs = metrics_spans[0]["attributes"]
    assert attrs["input_tokens"] == 10
    assert attrs["output_tokens"] == 20
    assert attrs["model_name"] == "gpt-5.4-mini"

    assert len(prompt_spans) == 1
    assert prompt_spans[0]["prompt"] == "hi"

    interceptor.unwrap()
    clear_current_context()


@pytest.mark.asyncio
async def test_anthropic_interceptor_non_stream(fake_uploader):
    response = MagicMock()
    response.usage.input_tokens = 5
    response.usage.output_tokens = 15
    response.content = [MagicMock(text="hello from claude")]

    client = _FakeAnthropicClient(response)
    interceptor = LLMInterceptor(fake_uploader)
    wrapped = interceptor.wrap(client)

    root = TraceContext(name="agent_task")
    set_current_context(root)

    result = wrapped.messages.create(
        model="claude-haiku-4-5",
        max_tokens=100,
        messages=[{"role": "user", "content": "hi"}],
    )

    await asyncio.sleep(0.05)

    assert result is response

    metrics_spans = [s for s in fake_uploader.spans if s["span_type"] == "llm_metrics"]
    assert len(metrics_spans) == 1
    attrs = metrics_spans[0]["attributes"]
    assert attrs["input_tokens"] == 5
    assert attrs["output_tokens"] == 15
    assert attrs["model_name"] == "claude-haiku-4-5"

    interceptor.unwrap()
    clear_current_context()


@pytest.mark.asyncio
async def test_openai_stream_interceptor(fake_uploader):
    chunks = [
        MagicMock(
            choices=[MagicMock(delta=MagicMock(content="hello"))],
            usage=None,
        ),
        MagicMock(
            choices=[MagicMock(delta=MagicMock(content=" world"))],
            usage=None,
        ),
    ]

    client = MagicMock()
    client.chat = MagicMock()
    client.chat.completions = MagicMock()
    client.chat.completions.create = MagicMock(return_value=iter(chunks))

    interceptor = LLMInterceptor(fake_uploader)
    wrapped = interceptor.wrap(client)

    root = TraceContext(name="agent_task")
    set_current_context(root)

    stream = wrapped.chat.completions.create(
        model="gpt-5.4",
        messages=[{"role": "user", "content": "say hi"}],
        stream=True,
    )

    # 消费流
    content = ""
    for chunk in stream:
        if chunk.choices[0].delta.content:
            content += chunk.choices[0].delta.content

    await asyncio.sleep(0.05)

    assert content == "hello world"

    trace_spans = [s for s in fake_uploader.spans if s["span_type"] == "trace"]
    assert len(trace_spans) == 1
    assert trace_spans[0]["attributes"]["stream"] is True

    metrics_spans = [s for s in fake_uploader.spans if s["span_type"] == "llm_metrics"]
    assert len(metrics_spans) == 1
    attrs = metrics_spans[0]["attributes"]
    assert attrs["model_name"] == "gpt-5.4"
    assert attrs["output_tokens"] > 0

    interceptor.unwrap()
    clear_current_context()


def test_unsupported_client(fake_uploader):
    interceptor = LLMInterceptor(fake_uploader)
    with pytest.raises(ValueError):
        interceptor.wrap(object())


@pytest.mark.asyncio
async def test_openai_interceptor_error_reports_span(fake_uploader):
    """LLM 调用异常时仍应上报带 error 的 span"""
    client = _FakeOpenAIClient(None)
    client.chat.completions.create = MagicMock(side_effect=RuntimeError("API down"))

    interceptor = LLMInterceptor(fake_uploader)
    wrapped = interceptor.wrap(client)

    root = TraceContext(name="agent_task")
    set_current_context(root)

    with pytest.raises(RuntimeError):
        wrapped.chat.completions.create(
            model="gpt-5.4",
            messages=[{"role": "user", "content": "hi"}],
        )

    await asyncio.sleep(0.05)

    prompt_spans = [s for s in fake_uploader.spans if s["span_type"] == "prompt"]
    assert len(prompt_spans) == 1
    assert prompt_spans[0]["status"] == "error"
    assert "API down" in prompt_spans[0]["error"]

    interceptor.unwrap()
    clear_current_context()


@pytest.mark.asyncio
async def test_openai_unwrap_restores_original(fake_uploader):
    """unwrap 后客户端应恢复原始 create 方法"""
    client = _FakeOpenAIClient(MagicMock())
    original_create = client.chat.completions.create

    interceptor = LLMInterceptor(fake_uploader)
    wrapped = interceptor.wrap(client)

    # wrap 后 create 被替换
    assert client.chat.completions.create is not original_create

    interceptor.unwrap()

    # unwrap 后 create 恢复
    assert client.chat.completions.create is original_create


@pytest.mark.asyncio
async def test_anthropic_stream_interceptor(fake_uploader):
    """Anthropic 流式响应应正确采集指标"""
    chunks = [
        MagicMock(
            choices=[MagicMock(delta=MagicMock(content="hello"))],
            usage=None,
        ),
        MagicMock(
            choices=[MagicMock(delta=MagicMock(content=" claude"))],
            usage=None,
        ),
    ]

    # 使用 _FakeAnthropicClient 避免 MagicMock 自动创建 chat 属性导致 OpenAI adapter 先匹配
    client = _FakeAnthropicClient(None)
    client.messages.create = MagicMock(return_value=iter(chunks))

    interceptor = LLMInterceptor(fake_uploader)
    wrapped = interceptor.wrap(client)

    root = TraceContext(name="agent_task")
    set_current_context(root)

    stream = wrapped.messages.create(
        model="claude-haiku-4-5",
        max_tokens=100,
        messages=[{"role": "user", "content": "hi"}],
        stream=True,
    )

    content = ""
    for chunk in stream:
        if chunk.choices and chunk.choices[0].delta.content:
            content += chunk.choices[0].delta.content

    await asyncio.sleep(0.05)

    assert content == "hello claude"

    metrics_spans = [s for s in fake_uploader.spans if s["span_type"] == "llm_metrics"]
    assert len(metrics_spans) == 1
    attrs = metrics_spans[0]["attributes"]
    assert attrs["model_name"] == "claude-haiku-4-5"
    assert attrs["output_tokens"] > 0

    interceptor.unwrap()
    clear_current_context()


@pytest.mark.asyncio
async def test_anthropic_multimodal_prompt(fake_uploader):
    """Anthropic 多模态格式 prompt（content 为 list）应正确提取文本"""
    response = MagicMock()
    response.usage.input_tokens = 5
    response.usage.output_tokens = 15
    response.content = [MagicMock(text="result")]

    client = _FakeAnthropicClient(response)
    interceptor = LLMInterceptor(fake_uploader)
    wrapped = interceptor.wrap(client)

    root = TraceContext(name="agent_task")
    set_current_context(root)

    wrapped.messages.create(
        model="claude-sonnet-5",
        max_tokens=100,
        messages=[
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "describe this image"},
                    {"type": "image", "source": {"url": "..."}},
                ],
            }
        ],
    )

    await asyncio.sleep(0.05)

    prompt_spans = [s for s in fake_uploader.spans if s["span_type"] == "prompt"]
    assert len(prompt_spans) == 1
    assert prompt_spans[0]["prompt"] == "describe this image"

    interceptor.unwrap()
    clear_current_context()


def test_custom_adapters(fake_uploader):
    """传入自定义 adapters 列表时应使用它而非内置列表"""
    from agent_insight_sdk.providers.base import BaseProviderAdapter

    class CustomAdapter(BaseProviderAdapter):
        provider_name = "custom"

        def supports(self, client):
            return hasattr(client, "custom_method")

        def _wrap_call(self, client, interceptor):
            return client

        def _unwrap_client(self, wrapped):
            pass

    custom = CustomAdapter()
    interceptor = LLMInterceptor(fake_uploader, adapters=[custom])

    class FakeClient:
        custom_method = lambda self: None

    wrapped = interceptor.wrap(FakeClient())
    assert interceptor._active_adapter is custom
