"""
Trace API 模块 - 提供 start_trace/start_span/end_span/end_trace 等显式 API

对外提供类似 OpenTelemetry 的 API，业务代码可手动控制 Trace 生命周期。

用法：
    api = TraceAPI(uploader)

    # 开始 Trace
    api.start_trace("user_query_123")

    # 开始 Span
    api.start_span("vector_search", attributes={"query": "machine learning"})
    # ... 执行操作 ...
    api.end_span(attributes={"results_count": 10, "status": "success"})

    # 结束 Trace
    api.end_trace(attributes={"status": "completed"})
"""

import asyncio
import time
from datetime import datetime
from typing import Any, Dict, List, Optional

from .context import TraceContext, get_current_context, set_current_context, clear_current_context
from .uploader import AsyncBatchUploader, SpanData


class TraceAPI:
    """显式 Trace API"""

    def __init__(self, uploader: AsyncBatchUploader):
        self._uploader = uploader
        # 记录每个 span 的开始时间
        self._span_start_times: Dict[str, datetime] = {}
        # 维护通过本 API 创建的 context 栈，便于 end_span/end_trace 恢复父上下文
        self._context_stack: List[TraceContext] = []

    def start_trace(self, name: str, trace_id: str = "") -> TraceContext:
        """
        开始一个新的 Trace

        用法：
            api = TraceAPI(uploader)
            ctx = api.start_trace("user_query_123")
        """
        ctx = TraceContext(name=name, trace_id=trace_id or None)
        self._context_stack = [ctx]
        set_current_context(ctx)
        self._span_start_times[ctx.span_id] = datetime.utcnow()
        return ctx

    def start_span(self, name: str, attributes: Dict[str, Any] = None) -> TraceContext:
        """
        开始一个新的 Span（作为当前 context 的子 span）

        用法：
            span_ctx = api.start_span("llm_call", attributes={"model": "gpt-5.4"})
        """
        parent_ctx = get_current_context()
        if parent_ctx:
            ctx = parent_ctx.create_child(name)
        else:
            ctx = TraceContext(name=name)

        self._context_stack.append(ctx)
        set_current_context(ctx)
        self._span_start_times[ctx.span_id] = datetime.utcnow()
        return ctx

    def end_span(
        self,
        ctx: Optional[TraceContext] = None,
        attributes: Dict[str, Any] = None,
        span_type: str = "trace",
    ) -> None:
        """
        结束当前 Span 并上报

        用法：
            api.end_span(span_ctx, attributes={"status": "success"})
        """
        if ctx is None and self._context_stack:
            ctx = self._context_stack[-1]

        if ctx is None:
            return

        end_time = datetime.utcnow()
        start_time = self._span_start_times.pop(ctx.span_id, end_time)

        span = SpanData(
            trace_id=ctx.trace_id,
            span_id=ctx.span_id,
            parent_span_id=ctx.parent_span_id,
            name=ctx.name,
            start_time=start_time.isoformat(),
            end_time=end_time.isoformat(),
            span_type=span_type,
            attributes=attributes or {},
        )

        self._submit_span(span)

        # 如果结束的是栈顶 span，恢复父上下文
        if self._context_stack and self._context_stack[-1] is ctx:
            self._context_stack.pop()
            if self._context_stack:
                set_current_context(self._context_stack[-1])
            else:
                clear_current_context()

    def end_trace(self, attributes: Dict[str, Any] = None) -> None:
        """
        结束当前 Trace（结束根 span 并清除上下文）

        用法：
            api.end_trace(attributes={"status": "completed"})
        """
        if not self._context_stack:
            return

        root = self._context_stack[0]
        self.end_span(root, attributes, span_type="trace")
        self._context_stack.clear()
        clear_current_context()

    def _submit_span(self, span: SpanData) -> None:
        """提交 span 到上报器"""
        try:
            loop = asyncio.get_running_loop()
            loop.create_task(self._uploader.submit(span))
        except RuntimeError:
            asyncio.run(self._uploader.submit(span))
