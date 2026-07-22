"""
TraceContext 单元测试
"""

import asyncio

from agent_insight_sdk import (
    TraceContext,
    clear_current_context,
    get_current_context,
    set_current_context,
)


def test_trace_context_default_ids():
    ctx = TraceContext(name="root")
    assert ctx.trace_id
    assert ctx.span_id
    assert ctx.parent_span_id is None
    assert ctx.name == "root"


def test_create_child_inherits_trace_id():
    parent = TraceContext(name="parent")
    child = parent.create_child("child")

    assert child.trace_id == parent.trace_id
    assert child.parent_span_id == parent.span_id
    assert child.span_id != parent.span_id
    assert child.name == "child"


def test_set_get_clear_context():
    ctx1 = TraceContext(name="ctx1")
    ctx2 = TraceContext(name="ctx2")

    set_current_context(ctx1)
    assert get_current_context().trace_id == ctx1.trace_id

    set_current_context(ctx2)
    assert get_current_context().trace_id == ctx2.trace_id

    clear_current_context()
    assert get_current_context() is None


def test_context_vars_isolation_across_asyncio_tasks():
    """contextvars 的核心价值：不同 asyncio.Task 间上下文隔离"""
    results = {}

    async def task(name: str):
        ctx = TraceContext(name=name)
        set_current_context(ctx)
        # 让出控制权，让其他 Task 也设置自己的上下文
        await asyncio.sleep(0.01)
        # 回到本 Task 后，上下文应仍然是本 Task 设置的
        current = get_current_context()
        results[name] = current.trace_id if current else None

    async def main():
        await asyncio.gather(task("A"), task("B"))

    asyncio.run(main())

    assert results["A"] != results["B"]
