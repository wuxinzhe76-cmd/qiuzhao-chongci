"""
上下文管理模块 - 基于 contextvars 实现异步安全的链路追踪上下文
"""

import uuid
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Optional


@dataclass
class TraceContext:
    """链路追踪上下文"""
    trace_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    span_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    parent_span_id: Optional[str] = None
    name: str = ""

    def create_child(self, name: str) -> "TraceContext":
        """创建子 span 上下文"""
        return TraceContext(
            trace_id=self.trace_id,
            span_id=str(uuid.uuid4()),
            parent_span_id=self.span_id,
            name=name,
        )


# 使用 ContextVar 保证异步环境下的上下文隔离
_current_context: ContextVar[Optional[TraceContext]] = ContextVar(
    "current_trace_context", default=None
)


def get_current_context() -> Optional[TraceContext]:
    """获取当前上下文"""
    return _current_context.get()


def set_current_context(ctx: TraceContext) -> None:
    """设置当前上下文"""
    _current_context.set(ctx)


def clear_current_context() -> None:
    """清除当前上下文"""
    _current_context.set(None)
