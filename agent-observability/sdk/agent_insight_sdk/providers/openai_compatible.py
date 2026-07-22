"""
OpenAI 兼容协议 Adapter

支持的厂商（所有兼容 OpenAI chat/completions 接口的）：
  - OpenAI 官方 SDK (pip install openai)
  - DeepSeek
  - vLLM
  - Ollama
  - Groq
  - Together AI
  - 任何兼容 /v1/chat/completions 端点的自建服务
"""

import functools
import time
from datetime import datetime
from typing import Any

from .base import BaseProviderAdapter, register_adapter
from ..context import TraceContext, get_current_context, set_current_context
from ..stream_monitor import MonitoredStream, StreamMonitor


class OpenAICompatibleAdapter(BaseProviderAdapter):
    """适配所有 OpenAI 协议兼容接口"""

    provider_name = "openai-compatible"

    # 标识：客户端对象拥有 chat.completions.create 方法
    def supports(self, client: Any) -> bool:
        try:
            return hasattr(client, "chat") and hasattr(client.chat.completions, "create")
        except Exception:
            return False

    # ---- 公开入口 ----

    def _wrap_call(self, client: Any, interceptor: Any) -> Any:
        self._original = client.chat.completions.create
        self._client = client
        self._interceptor = interceptor

        @functools.wraps(self._original)
        def wrapper(*args, **kwargs):
            return self._handle(client, args, kwargs)

        client.chat.completions.create = wrapper
        return client

    def _unwrap_client(self, wrapped: Any) -> None:
        if hasattr(self, "_original") and self._original:
            self._client.chat.completions.create = self._original
            self._original = None

    # ---- 核心拦截逻辑 ----

    def _handle(self, client: Any, args: tuple, kwargs: dict) -> Any:
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

    # ---- 流式响应包装 ----

    def _wrap_stream(
        self,
        stream: Any,
        interceptor: Any,
        ctx: TraceContext,
        kwargs: dict,
        start_time: datetime,
        perf_start: float,
    ) -> Any:
        monitor = StreamMonitor()
        monitor.record_start()
        monitored = MonitoredStream(stream, monitor)

        class StreamWrapper:
            def __init__(wself):
                wself._stream = monitored
                wself._monitor = monitor
                wself._interceptor = interceptor
                wself._ctx = ctx
                wself._kwargs = kwargs
                wself._start_time = start_time
                wself._perf_start = perf_start

            def __iter__(wself):
                return wself

            def __next__(wself):
                try:
                    return next(wself._stream)
                except StopIteration:
                    wself._finalize()
                    raise

            def __aiter__(wself):
                return wself

            async def __anext__(wself):
                try:
                    return await wself._stream.__anext__()
                except StopAsyncIteration:
                    wself._finalize()
                    raise

            def _finalize(wself):
                metrics = wself._monitor.get_metrics()
                end_time = datetime.utcnow()
                record = OpenAICompatibleAdapter.extract(
                    wself._interceptor._active_adapter,
                    wself._kwargs, None, wself._perf_start, True,
                )
                record.prefill_ms = metrics.prefill_ms
                record.decode_ms = metrics.decode_ms
                record.output_tokens = metrics.output_tokens
                record.tps = metrics.tps
                wself._interceptor._report(wself._ctx, record, wself._start_time, end_time)

        return StreamWrapper()


# 注册到全局适配器表（优先级高于 Anthropic，因为更多厂商兼容 OpenAI 协议）
register_adapter(OpenAICompatibleAdapter())
