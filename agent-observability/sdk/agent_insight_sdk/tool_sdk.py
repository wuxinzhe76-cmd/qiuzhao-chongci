"""
Tool SDK 模块 - 自动记录 Tool 调用

封装 Tool 调用，自动记录输入、输出、异常、耗时。
业务代码无需手动传递 TraceId/SpanId。

提供三种装饰器：
- instrument(): 通用工具调用
- instrument_mcp(): MCP 协议工具调用
- instrument_rag(): RAG 检索调用
"""

import asyncio
import functools
import time
from datetime import datetime
from typing import Any, Callable, Dict, Optional

from .context import TraceContext, get_current_context, set_current_context
from .uploader import AsyncBatchUploader, SpanData


class ToolSDK:
    """Tool 调用自动埋点装饰器"""

    def __init__(self, uploader: AsyncBatchUploader):
        self._uploader = uploader

    # ------------------------------------------------------------------
    # 通用工具装饰器
    # ------------------------------------------------------------------

    def instrument(self, name: str = "", tool_type: str = "generic"):
        """
        装饰器：自动记录通用 Tool 调用

        用法：
            @tool_sdk.instrument(name="calculator", tool_type="math")
            def calculator(expression: str) -> float:
                return eval(expression)
        """
        return self._make_decorator(name, tool_type, extra_attributes={"tool_subtype": "normal"})

    # ------------------------------------------------------------------
    # MCP 工具装饰器
    # ------------------------------------------------------------------

    def instrument_mcp(
        self,
        server: str = "",
        tool: str = "",
        name: str = "",
        protocol_version: str = "",
    ):
        """
        装饰器：自动记录 MCP 协议工具调用

        在 attributes 中自动填充 tool_subtype="mcp"、mcp_server、mcp_tool 等字段。

        用法：
            @tool_sdk.instrument_mcp(server="github", tool="create_issue")
            def create_github_issue(title: str):
                return mcp_client.call("github", "create_issue", title=title)
        """
        def decorator(func: Callable) -> Callable:
            tool_name = name or func.__name__
            attrs: Dict[str, Any] = {
                "tool_subtype": "mcp",
                "mcp_server": server,
                "mcp_tool": tool or tool_name,
            }
            if protocol_version:
                attrs["mcp_protocol_version"] = protocol_version
            return self._make_decorator(tool_name, "mcp", extra_attributes=attrs)(func)

        return decorator

    # ------------------------------------------------------------------
    # RAG 检索装饰器
    # ------------------------------------------------------------------

    def instrument_rag(
        self,
        vector_db: str = "",
        name: str = "",
        top_k: int = 0,
    ):
        """
        装饰器：自动记录 RAG 检索调用

        在 attributes 中自动填充 tool_subtype="rag"、rag_vector_db、rag_top_k 等字段。
        如果函数返回 list/dict，还会自动提取 rag_recall_count。

        用法：
            @tool_sdk.instrument_rag(vector_db="pinecone", top_k=5)
            def retrieve_context(query: str):
                return vector_db.search(embed(query), top_k=5)
        """
        def decorator(func: Callable) -> Callable:
            tool_name = name or func.__name__
            attrs: Dict[str, Any] = {
                "tool_subtype": "rag",
                "rag_vector_db": vector_db,
                "rag_top_k": top_k,
            }
            return self._make_decorator(tool_name, "rag", extra_attributes=attrs, extract_rag_stats=True)(func)

        return decorator

    # ------------------------------------------------------------------
    # 内部：构造装饰器
    # ------------------------------------------------------------------

    def _make_decorator(
        self,
        name: str,
        tool_type: str,
        extra_attributes: Dict[str, Any],
        extract_rag_stats: bool = False,
    ):
        """构造装饰器，支持 extra_attributes 和 RAG 统计提取"""
        def decorator(func: Callable) -> Callable:
            tool_name = name or func.__name__

            @functools.wraps(func)
            def sync_wrapper(*args, **kwargs):
                return self._record_sync(
                    func, tool_name, tool_type, args, kwargs,
                    extra_attributes=extra_attributes,
                    extract_rag_stats=extract_rag_stats,
                )

            @functools.wraps(func)
            async def async_wrapper(*args, **kwargs):
                return await self._record_async(
                    func, tool_name, tool_type, args, kwargs,
                    extra_attributes=extra_attributes,
                    extract_rag_stats=extract_rag_stats,
                )

            if asyncio.iscoroutinefunction(func):
                return async_wrapper
            return sync_wrapper

        return decorator

    # ------------------------------------------------------------------
    # 内部：同步/异步记录
    # ------------------------------------------------------------------

    def _record_sync(
        self,
        func: Callable,
        tool_name: str,
        tool_type: str,
        args: tuple,
        kwargs: dict,
        extra_attributes: Optional[Dict[str, Any]] = None,
        extract_rag_stats: bool = False,
    ) -> Any:
        """记录同步 Tool 调用"""
        parent_ctx = get_current_context()
        if parent_ctx:
            ctx = parent_ctx.create_child(f"tool:{tool_name}")
        else:
            ctx = TraceContext(name=f"tool:{tool_name}")

        set_current_context(ctx)

        start_time = datetime.utcnow()
        perf_start = time.perf_counter()

        # 记录输入
        input_data = self._safe_serialize({"args": args, "kwargs": kwargs})

        try:
            result = func(*args, **kwargs)
            end_time = datetime.utcnow()
            duration_ms = (time.perf_counter() - perf_start) * 1000

            # 记录输出
            output_data = self._safe_serialize(result)

            # RAG: 从返回值提取召回数量
            attrs = dict(extra_attributes) if extra_attributes else {}
            if extract_rag_stats:
                recall_count = self._extract_recall_count(result)
                if recall_count is not None:
                    attrs["rag_recall_count"] = recall_count

            self._report_tool_span(
                ctx, tool_name, tool_type,
                start_time, end_time, duration_ms,
                input_data, output_data, status="success",
                extra_attributes=attrs,
            )

            return result

        except Exception as e:
            end_time = datetime.utcnow()
            duration_ms = (time.perf_counter() - perf_start) * 1000

            self._report_tool_span(
                ctx, tool_name, tool_type,
                start_time, end_time, duration_ms,
                input_data, str(e), status="error",
                error=str(e),
                extra_attributes=extra_attributes,
            )
            raise

    async def _record_async(
        self,
        func: Callable,
        tool_name: str,
        tool_type: str,
        args: tuple,
        kwargs: dict,
        extra_attributes: Optional[Dict[str, Any]] = None,
        extract_rag_stats: bool = False,
    ) -> Any:
        """记录异步 Tool 调用"""
        parent_ctx = get_current_context()
        if parent_ctx:
            ctx = parent_ctx.create_child(f"tool:{tool_name}")
        else:
            ctx = TraceContext(name=f"tool:{tool_name}")

        set_current_context(ctx)

        start_time = datetime.utcnow()
        perf_start = time.perf_counter()

        input_data = self._safe_serialize({"args": args, "kwargs": kwargs})

        try:
            result = await func(*args, **kwargs)
            end_time = datetime.utcnow()
            duration_ms = (time.perf_counter() - perf_start) * 1000

            output_data = self._safe_serialize(result)

            attrs = dict(extra_attributes) if extra_attributes else {}
            if extract_rag_stats:
                recall_count = self._extract_recall_count(result)
                if recall_count is not None:
                    attrs["rag_recall_count"] = recall_count

            self._report_tool_span(
                ctx, tool_name, tool_type,
                start_time, end_time, duration_ms,
                input_data, output_data, status="success",
                extra_attributes=attrs,
            )

            return result

        except Exception as e:
            end_time = datetime.utcnow()
            duration_ms = (time.perf_counter() - perf_start) * 1000

            self._report_tool_span(
                ctx, tool_name, tool_type,
                start_time, end_time, duration_ms,
                input_data, str(e), status="error",
                error=str(e),
                extra_attributes=extra_attributes,
            )
            raise

    # ------------------------------------------------------------------
    # 内部：上报 & 工具方法
    # ------------------------------------------------------------------

    def _report_tool_span(
        self,
        ctx: TraceContext,
        tool_name: str,
        tool_type: str,
        start_time: datetime,
        end_time: datetime,
        duration_ms: float,
        input_data: str,
        output_data: str,
        status: str,
        error: str = "",
        extra_attributes: Optional[Dict[str, Any]] = None,
    ) -> None:
        """上报 Tool 调用 span"""
        span = SpanData(
            trace_id=ctx.trace_id,
            span_id=ctx.span_id,
            parent_span_id=ctx.parent_span_id,
            name=f"tool:{tool_name}",
            start_time=start_time.isoformat(),
            end_time=end_time.isoformat(),
            span_type="tool_call",
            tool_name=tool_name,
            tool_type=tool_type,
            input_data=input_data,
            output_data=output_data,
            duration_ms=duration_ms,
            status=status,
            error=error,
            attributes=extra_attributes or {},
        )

        try:
            loop = asyncio.get_running_loop()
            loop.create_task(self._uploader.submit(span))
        except RuntimeError:
            asyncio.run(self._uploader.submit(span))

    @staticmethod
    def _extract_recall_count(result: Any) -> Optional[int]:
        """从 RAG 返回值中提取召回数量"""
        if isinstance(result, list):
            return len(result)
        if isinstance(result, dict):
            # 常见 key: results, documents, chunks, hits, matches
            for key in ("results", "documents", "chunks", "hits", "matches"):
                if key in result and isinstance(result[key], (list, tuple)):
                    return len(result[key])
            # 如果有 count 字段
            if "count" in result and isinstance(result["count"], int):
                return result["count"]
        return None

    @staticmethod
    def _safe_serialize(data: Any) -> str:
        """安全序列化数据为 JSON 字符串"""
        try:
            import json
            return json.dumps(data, ensure_ascii=False, default=str)
        except Exception:
            return str(data)
