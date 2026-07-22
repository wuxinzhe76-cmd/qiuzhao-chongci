"""
全链路连通性测试脚本

模拟一个复杂的 Agent 思考与工具调用过程：
1. Agent 开始任务
2. 触发向量检索（模拟工具调用）
3. 调用大模型（非流式）
4. 调用大模型（流式）
5. Agent 结束任务

验证 SDK 拦截 -> 后端接收 -> Kafka -> ClickHouse 全链路。
"""

import asyncio
import time
import uuid
from datetime import datetime

from agent_insight_sdk import (
    AsyncBatchUploader,
    OpenAIInterceptor,
    TraceContext,
    get_current_context,
    set_current_context,
)
from agent_insight_sdk.uploader import SpanData


async def simulate_tool_call(name: str, duration_ms: float, uploader: AsyncBatchUploader):
    """模拟工具调用 span"""
    ctx = get_current_context()
    tool_ctx = ctx.create_child(name)
    set_current_context(tool_ctx)

    start_time = datetime.utcnow()
    await asyncio.sleep(duration_ms / 1000.0)
    end_time = datetime.utcnow()

    span = SpanData(
        trace_id=tool_ctx.trace_id,
        span_id=tool_ctx.span_id,
        parent_span_id=tool_ctx.parent_span_id,
        name=name,
        start_time=start_time.isoformat(),
        end_time=end_time.isoformat(),
        span_type="trace",
        attributes={"tool": name, "status": "success"},
    )
    await uploader.submit(span)
    print(f"  [Tool] {name} completed in {duration_ms:.0f}ms")


async def simulate_llm_call_non_stream(
    model_name: str,
    input_tokens: int,
    output_tokens: int,
    duration_ms: float,
    uploader: AsyncBatchUploader,
):
    """模拟非流式 LLM 调用 span"""
    ctx = get_current_context()
    llm_ctx = ctx.create_child(f"llm_call_{model_name}")
    set_current_context(llm_ctx)

    start_time = datetime.utcnow()
    await asyncio.sleep(duration_ms / 1000.0)
    end_time = datetime.utcnow()

    prefill_ms = duration_ms * 0.3
    decode_ms = duration_ms * 0.7
    tps = output_tokens / (decode_ms / 1000.0) if decode_ms > 0 else 0

    # Trace span
    trace_span = SpanData(
        trace_id=llm_ctx.trace_id,
        span_id=llm_ctx.span_id,
        parent_span_id=llm_ctx.parent_span_id,
        name=f"llm_call_{model_name}",
        start_time=start_time.isoformat(),
        end_time=end_time.isoformat(),
        span_type="trace",
        attributes={"model": model_name, "stream": False},
    )
    await uploader.submit(trace_span)

    # Metrics span
    metrics_span = SpanData(
        trace_id=llm_ctx.trace_id,
        span_id=llm_ctx.span_id,
        parent_span_id=llm_ctx.parent_span_id,
        name="llm_metrics",
        start_time=start_time.isoformat(),
        end_time=end_time.isoformat(),
        span_type="llm_metrics",
        attributes={
            "model_name": model_name,
            "prefill_ms": prefill_ms,
            "decode_ms": decode_ms,
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "tps": tps,
        },
    )
    await uploader.submit(metrics_span)
    print(f"  [LLM] {model_name} (non-stream) completed in {duration_ms:.0f}ms, TPS={tps:.1f}")


async def simulate_llm_call_stream(
    model_name: str,
    input_tokens: int,
    output_tokens: int,
    prefill_ms: float,
    decode_ms: float,
    uploader: AsyncBatchUploader,
):
    """模拟流式 LLM 调用 span"""
    ctx = get_current_context()
    llm_ctx = ctx.create_child(f"llm_stream_{model_name}")
    set_current_context(llm_ctx)

    start_time = datetime.utcnow()
    total_ms = prefill_ms + decode_ms
    await asyncio.sleep(total_ms / 1000.0)
    end_time = datetime.utcnow()

    tps = output_tokens / (decode_ms / 1000.0) if decode_ms > 0 else 0

    # Trace span
    trace_span = SpanData(
        trace_id=llm_ctx.trace_id,
        span_id=llm_ctx.span_id,
        parent_span_id=llm_ctx.parent_span_id,
        name=f"llm_stream_{model_name}",
        start_time=start_time.isoformat(),
        end_time=end_time.isoformat(),
        span_type="trace",
        attributes={"model": model_name, "stream": True},
    )
    await uploader.submit(trace_span)

    # Metrics span
    metrics_span = SpanData(
        trace_id=llm_ctx.trace_id,
        span_id=llm_ctx.span_id,
        parent_span_id=llm_ctx.parent_span_id,
        name="llm_metrics",
        start_time=start_time.isoformat(),
        end_time=end_time.isoformat(),
        span_type="llm_metrics",
        attributes={
            "model_name": model_name,
            "prefill_ms": prefill_ms,
            "decode_ms": decode_ms,
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "tps": tps,
        },
    )
    await uploader.submit(metrics_span)
    print(f"  [LLM] {model_name} (stream) prefill={prefill_ms:.0f}ms, decode={decode_ms:.0f}ms, TPS={tps:.1f}")


async def main():
    """模拟完整的 Agent 执行流程"""
    backend_url = "http://localhost:8000"
    print(f"=== Agent-Insight 全链路测试 ===")
    print(f"后端地址: {backend_url}")
    print()

    # 初始化上报器
    uploader = AsyncBatchUploader(
        backend_url=backend_url,
        batch_size=5,       # 小批量便于测试
        flush_interval=0.5,
    )
    await uploader.start()

    # 创建根上下文
    root_ctx = TraceContext(name="agent_task")
    set_current_context(root_ctx)
    start_time = datetime.utcnow()

    print(f"[Agent] 任务开始: {root_ctx.trace_id}")
    print()

    # Step 1: 向量检索
    print("[Step 1] 向量检索...")
    await simulate_tool_call("vector_search", 120, uploader)

    # Step 2: 调用 GPT-5.4 (非流式)
    print("[Step 2] 调用 GPT-5.4 (非流式)...")
    await simulate_llm_call_non_stream("gpt-5.4", 1500, 800, 2500, uploader)

    # Step 3: 工具调用 - 代码执行
    print("[Step 3] 工具调用 - 代码执行...")
    await simulate_tool_call("code_executor", 350, uploader)

    # Step 4: 调用 Claude Opus 4.8 (流式)
    print("[Step 4] 调用 Claude-Opus-4.8 (流式)...")
    await simulate_llm_call_stream("claude-opus-4-8", 2000, 1200, 800, 3500, uploader)

    # Step 5: 调用 GPT-5.4-nano (流式)
    print("[Step 5] 调用 GPT-5.4-nano (流式)...")
    await simulate_llm_call_stream("gpt-5.4-nano", 800, 500, 200, 1200, uploader)

    # Step 6: 最终总结工具调用
    print("[Step 6] 工具调用 - 结果汇总...")
    await simulate_tool_call("result_aggregator", 80, uploader)

    # 上报 Agent 结束 span
    end_time = datetime.utcnow()
    agent_span = SpanData(
        trace_id=root_ctx.trace_id,
        span_id=root_ctx.span_id,
        parent_span_id=None,
        name="agent_task",
        start_time=start_time.isoformat(),
        end_time=end_time.isoformat(),
        span_type="trace",
        attributes={"status": "completed"},
    )
    await uploader.submit(agent_span)

    total_ms = (end_time - start_time).total_seconds() * 1000
    print()
    print(f"[Agent] 任务完成，总耗时: {total_ms:.0f}ms")
    print(f"[Agent] Trace ID: {root_ctx.trace_id}")
    print()

    # 等待上报完成
    print("等待数据上报...")
    await asyncio.sleep(2)
    await uploader.stop()

    print()
    print("=== 测试完成 ===")
    print(f"请查看前端面板 http://localhost:3000 查看链路瀑布图")
    print(f"请查看模型效能对比 http://localhost:3000/metrics")


if __name__ == "__main__":
    asyncio.run(main())
