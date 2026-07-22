"""
Anthropic 协议 Adapter

支持的 SDK: pip install anthropic

Anthropic 与 OpenAI 的差异：
  - 入口: client.messages.create (而非 chat.completions.create)
  - 响应: content[0].text (而非 choices[0].message.content)
  - Token: usage.input_tokens / usage.output_tokens
  - 参数: model / max_tokens / messages
"""

import functools
import time
from datetime import datetime
from typing import Any

from .base import BaseProviderAdapter, LLMCallRecord, register_adapter
from ..context import TraceContext, get_current_context, set_current_context


class AnthropicAdapter(BaseProviderAdapter):
    """适配 Anthropic Claude API"""

    provider_name = "anthropic"

    def supports(self, client: Any) -> bool:
        try:
            return hasattr(client, "messages") and hasattr(client.messages, "create")
        except Exception:
            return False

    # ------------------------------------------------------------------
    # 数据提取 — 覆盖基类以匹配 Anthropic 响应格式
    # ------------------------------------------------------------------

    def extract(
        self,
        kwargs: dict,
        response: Any,
        perf_start: float,
        is_stream: bool,
    ) -> LLMCallRecord:
        record = LLMCallRecord(
            model=kwargs.get("model", "unknown"),
            provider=self.provider_name,
            is_stream=is_stream,
            latency_ms=(time.perf_counter() - perf_start) * 1000,
        )
        if not is_stream and response is not None:
            record = self._extract_tokens(response, record)
            record = self._extract_prompt(kwargs, record)
            record = self._extract_response(response, record)
            record.prefill_ms = record.latency_ms * 0.25
            record.decode_ms = record.latency_ms * 0.75
        return record

    def _extract_tokens(self, response: Any, record: LLMCallRecord) -> LLMCallRecord:
        usage = getattr(response, "usage", None)
        if usage:
            record.input_tokens = getattr(usage, "input_tokens", 0)
            record.output_tokens = getattr(usage, "output_tokens", 0)
        if record.latency_ms > 0 and record.output_tokens > 0:
            record.tps = record.output_tokens / (record.latency_ms / 1000.0)
        return record

    def _extract_response(self, response: Any, record: LLMCallRecord) -> LLMCallRecord:
        try:
            content = response.content
            if isinstance(content, list) and len(content) > 0:
                record.response_text = getattr(content[0], "text", "") or ""
        except Exception:
            pass
        return record

    def _extract_prompt(self, kwargs: dict, record: LLMCallRecord) -> LLMCallRecord:
        messages = kwargs.get("messages", [])
        try:
            user_msgs = []
            for m in messages:
                if not isinstance(m, dict) or m.get("role") != "user":
                    continue
                content = m.get("content", "")
                if isinstance(content, list):
                    # Anthropic 多模态格式: [{"type": "text", "text": "..."}, ...]
                    text_parts = [
                        block.get("text", "")
                        for block in content
                        if isinstance(block, dict) and block.get("type") == "text"
                    ]
                    user_msgs.append(" ".join(text_parts))
                else:
                    user_msgs.append(str(content))
            record.prompt_text = user_msgs[-1] if user_msgs else ""
        except Exception:
            pass
        return record

    # ------------------------------------------------------------------
    # 客户端拦截
    # ------------------------------------------------------------------

    def _wrap_call(self, client: Any, interceptor: Any) -> Any:
        self._original = client.messages.create
        self._client = client
        self._interceptor = interceptor

        @functools.wraps(self._original)
        def wrapper(*args, **kwargs):
            return self._handle(args, kwargs)

        client.messages.create = wrapper
        return client

    def _unwrap_client(self, wrapped: Any) -> None:
        if hasattr(self, "_original") and self._original:
            self._client.messages.create = self._original
            self._original = None

    def _handle(self, args: tuple, kwargs: dict) -> Any:
        interceptor = self._interceptor
        parent_ctx = get_current_context()
        ctx = (
            parent_ctx.create_child("llm_call")
            if parent_ctx
            else TraceContext(name="llm_call")
        )
        set_current_context(ctx)
        start_time = datetime.utcnow()
        perf_start = time.perf_counter()
        is_stream = kwargs.get("stream", False)

        try:
            response = self._original(*args, **kwargs)
        except Exception as exc:
            record = self.extract(kwargs, None, perf_start, is_stream)
            record.error = str(exc)
            interceptor._report(ctx, record, start_time, datetime.utcnow())
            raise

        if is_stream:
            return self._wrap_stream(
                response, interceptor, ctx, kwargs, start_time, perf_start
            )

        record = self.extract(kwargs, response, perf_start, is_stream)
        interceptor._report(ctx, record, start_time, datetime.utcnow())
        return response

    # ---- 流式响应 ----

    def _wrap_stream(
        self, stream, interceptor, ctx, kwargs, start_time, perf_start
    ):
        """Anthropic 流式响应: 用 StreamMonitor/MonitoredStream 包装 stream，迭代结束时在本层提取指标并上报"""
        # Anthropic stream 事件是迭代器
        from ..stream_monitor import StreamMonitor, MonitoredStream

        monitor = StreamMonitor()
        monitor.record_start()
        monitored = MonitoredStream(stream, monitor)

        class AnthropicStreamWrapper:
            def __init__(s):
                s._s = monitored
                s._m = monitor
                s._itc = interceptor
                s._ctx = ctx
                s._kwargs = kwargs
                s._st = start_time
                s._ps = perf_start

            def __iter__(s):
                return s

            def __next__(s):
                try:
                    return next(s._s)
                except StopIteration:
                    s._finalize()
                    raise

            def __aiter__(s):
                return s

            async def __anext__(s):
                try:
                    return await s._s.__anext__()
                except StopAsyncIteration:
                    s._finalize()
                    raise

            def _finalize(s):
                m = s._m.get_metrics()
                r = AnthropicAdapter.extract(
                    s._itc._active_adapter, s._kwargs, None, s._ps, True
                )
                r.prefill_ms = m.prefill_ms
                r.decode_ms = m.decode_ms
                r.output_tokens = m.output_tokens
                r.tps = m.tps
                s._itc._report(s._ctx, r, s._st, datetime.utcnow())

        return AnthropicStreamWrapper()


# 注册到全局适配器表
register_adapter(AnthropicAdapter())
