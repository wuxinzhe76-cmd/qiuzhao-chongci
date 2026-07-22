"""
LLM 拦截器模块 - 非侵入式拦截 OpenAI 客户端调用

.. deprecated::
    此文件已废弃，请使用 LLMInterceptor（来自 providers 模块）。
    OpenAIInterceptor 已在 __init__.py 中别名到 LLMInterceptor。
"""

import asyncio
import time
from datetime import datetime
from typing import Any

from .context import TraceContext, get_current_context, set_current_context
from .stream_monitor import MonitoredStream, StreamMonitor
from .uploader import AsyncBatchUploader, SpanData

import warnings


class OpenAIInterceptor:
    """OpenAI 客户端拦截器 (已废弃)

    .. deprecated::
        请使用 ``LLMInterceptor(uploader).wrap(client)`` 替代
        ``OpenAIInterceptor(uploader).patch(client)``。
    """

    def __init__(self, uploader: AsyncBatchUploader):
        self._uploader = uploader
        self._original_create = None

    def patch(self, client: Any) -> None:
        """对 OpenAI 客户端进行打桩拦截"""
        if hasattr(client.chat.completions, "create"):
            self._original_create = client.chat.completions.create
            client.chat.completions.create = self._wrapped_create

    def unpatch(self, client: Any) -> None:
        """恢复原始方法"""
        if self._original_create:
            client.chat.completions.create = self._original_create
            self._original_create = None

    def _wrapped_create(self, *args, **kwargs):
        """包装后的 create 方法"""
        # 获取或创建上下文
        parent_ctx = get_current_context()
        if parent_ctx:
            ctx = parent_ctx.create_child("llm_call")
        else:
            ctx = TraceContext(name="llm_call")

        set_current_context(ctx)

        start_time = datetime.utcnow()
        perf_start = time.perf_counter()

        # 提取参数
        model_name = kwargs.get("model", "unknown")
        is_stream = kwargs.get("stream", False)

        try:
            # 调用原始方法
            response = self._original_create(*args, **kwargs)

            if is_stream:
                # 流式响应处理
                return self._handle_stream_response(
                    response, ctx, model_name, start_time, perf_start, kwargs
                )
            else:
                # 非流式响应处理
                return self._handle_normal_response(
                    response, ctx, model_name, start_time, perf_start, kwargs
                )

        except Exception as e:
            # 记录错误 span
            end_time = datetime.utcnow()
            self._report_error_span(ctx, model_name, start_time, end_time, str(e))
            raise

    def _handle_normal_response(
        self,
        response: Any,
        ctx: TraceContext,
        model_name: str,
        start_time: datetime,
        perf_start: float,
        kwargs: dict,
    ) -> Any:
        """处理非流式响应"""
        end_time = datetime.utcnow()
        duration_ms = (time.perf_counter() - perf_start) * 1000

        # 提取 token 信息
        input_tokens = 0
        output_tokens = 0
        if hasattr(response, "usage"):
            input_tokens = getattr(response.usage, "prompt_tokens", 0)
            output_tokens = getattr(response.usage, "completion_tokens", 0)

        # 上报 trace span
        trace_span = SpanData(
            trace_id=ctx.trace_id,
            span_id=ctx.span_id,
            parent_span_id=ctx.parent_span_id,
            name=ctx.name,
            start_time=start_time.isoformat(),
            end_time=end_time.isoformat(),
            span_type="trace",
            attributes={"model": model_name, "stream": False},
        )

        # 上报 llm_metrics span
        tps = output_tokens / (duration_ms / 1000.0) if duration_ms > 0 else 0
        metrics_span = SpanData(
            trace_id=ctx.trace_id,
            span_id=ctx.span_id,
            parent_span_id=ctx.parent_span_id,
            name="llm_metrics",
            start_time=start_time.isoformat(),
            end_time=end_time.isoformat(),
            span_type="llm_metrics",
            attributes={
                "model_name": model_name,
                "prefill_ms": duration_ms * 0.2,  # 估算：假设 prefill 占 20%
                "decode_ms": duration_ms * 0.8,   # 估算：假设 decode 占 80%
                "input_tokens": input_tokens,
                "output_tokens": output_tokens,
                "tps": tps,
            },
        )

        # 异步提交
        self._submit_spans([trace_span, metrics_span])

        return response

    def _handle_stream_response(
        self,
        stream: Any,
        ctx: TraceContext,
        model_name: str,
        start_time: datetime,
        perf_start: float,
        kwargs: dict,
    ) -> Any:
        """处理流式响应"""
        monitor = StreamMonitor()
        monitor.record_start()

        # 包装流式响应
        monitored_stream = MonitoredStream(stream, monitor)

        # 返回包装后的流，在迭代结束时上报数据
        return self._StreamWrapper(
            monitored_stream, monitor, ctx, model_name, start_time, self._uploader
        )

    def _report_error_span(
        self,
        ctx: TraceContext,
        model_name: str,
        start_time: datetime,
        end_time: datetime,
        error: str,
    ) -> None:
        """上报错误 span"""
        span = SpanData(
            trace_id=ctx.trace_id,
            span_id=ctx.span_id,
            parent_span_id=ctx.parent_span_id,
            name=ctx.name,
            start_time=start_time.isoformat(),
            end_time=end_time.isoformat(),
            span_type="trace",
            attributes={"model": model_name, "error": error},
        )

        self._submit_spans([span])

    def _submit_spans(self, spans: list) -> None:
        """提交多个 span 到上报器"""
        try:
            loop = asyncio.get_running_loop()
            for span in spans:
                loop.create_task(self._uploader.submit(span))
        except RuntimeError:
            async def _submit_all():
                for span in spans:
                    await self._uploader.submit(span)
            asyncio.run(_submit_all())

    class _StreamWrapper:
        """流式响应包装器，在迭代结束时上报数据"""

        def __init__(
            self,
            stream: MonitoredStream,
            monitor: StreamMonitor,
            ctx: TraceContext,
            model_name: str,
            start_time: datetime,
            uploader: AsyncBatchUploader,
        ):
            self._stream = stream
            self._monitor = monitor
            self._ctx = ctx
            self._model_name = model_name
            self._start_time = start_time
            self._uploader = uploader

        def __iter__(self):
            return self

        def __next__(self):
            try:
                return next(self._stream)
            except StopIteration:
                # 流结束，上报数据
                self._report_metrics()
                raise

        def _report_metrics(self) -> None:
            """上报流式指标"""
            end_time = datetime.utcnow()
            metrics = self._monitor.get_metrics()

            # 上报 trace span
            trace_span = SpanData(
                trace_id=self._ctx.trace_id,
                span_id=self._ctx.span_id,
                parent_span_id=self._ctx.parent_span_id,
                name=self._ctx.name,
                start_time=self._start_time.isoformat(),
                end_time=end_time.isoformat(),
                span_type="trace",
                attributes={"model": self._model_name, "stream": True},
            )

            # 上报 llm_metrics span
            metrics_span = SpanData(
                trace_id=self._ctx.trace_id,
                span_id=self._ctx.span_id,
                parent_span_id=self._ctx.parent_span_id,
                name="llm_metrics",
                start_time=self._start_time.isoformat(),
                end_time=end_time.isoformat(),
                span_type="llm_metrics",
                attributes={
                    "model_name": self._model_name,
                    "prefill_ms": metrics.prefill_ms,
                    "decode_ms": metrics.decode_ms,
                    "input_tokens": 0,  # 流式响应通常无法获取 input tokens
                    "output_tokens": metrics.output_tokens,
                    "tps": metrics.tps,
                },
            )

            try:
                loop = asyncio.get_running_loop()
                loop.create_task(self._uploader.submit(trace_span))
                loop.create_task(self._uploader.submit(metrics_span))
            except RuntimeError:
                asyncio.run(self._uploader.submit(trace_span))
                asyncio.run(self._uploader.submit(metrics_span))
