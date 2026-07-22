"""
Agent-Insight SDK 完整演示 — 多厂商 LLM + Tool + Trace API

本脚本演示：
  1. 对接真实 LLM API（自动识别 provider，非 mock）
  2. OpenAI / Anthropic / DeepSeek / Ollama 多厂商示例
  3. ToolSDK 装饰器自动埋点
  4. TraceAPI 显式链路控制
  5. SessionSDK 自动聚合 Session 生命周期

用法：
  1. 先在项目根目录创建 .env 文件，填入 API Key：
       OPENAI_API_KEY=sk-xxxx
       ANTHROPIC_API_KEY=sk-ant-xxxx
       DEEPSEEK_API_KEY=sk-xxxx

  2. 启动后端基础设施：
       docker-compose up -d
       cd backend && python -m uvicorn app.main:app --port 8000

  3. 运行本脚本：
       cd sdk && pip install -e . && python examples/example_sdk_demo.py

  API Key 未配置时自动回退到模拟模式（mock）
"""

import asyncio
import os
import sys
import time
from datetime import datetime
from typing import Any, Optional

# 尝试加载 .env 文件（如果安装了 python-dotenv）
try:
    from dotenv import load_dotenv
    load_dotenv(os.path.join(os.path.dirname(__file__), "..", "..", ".env"))
except ImportError:
    pass

from agent_insight_sdk import (
    TraceContext,
    LLMInterceptor,
    StreamMonitor,
    SessionSDK,
    ToolSDK,
    TraceAPI,
    AsyncBatchUploader,
    SpanData,
    set_current_context,
    get_current_context,
    clear_current_context,
)

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------

BACKEND_URL = os.getenv("AGENT_INSIGHT_BACKEND", "http://localhost:8000")
OPENAI_KEY = os.getenv("OPENAI_API_KEY", "")
ANTHROPIC_KEY = os.getenv("ANTHROPIC_API_KEY", "")
DEEPSEEK_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_BASE = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1")

HAS_OPENAI = bool(OPENAI_KEY)
HAS_ANTHROPIC = bool(ANTHROPIC_KEY)
HAS_DEEPSEEK = bool(DEEPSEEK_KEY)


# ===================================================================
# Demo 1: 统一 LLMInterceptor — 一行代码拦截任何厂商
# ===================================================================

async def demo_llm_interceptor_openai():
    """演示1：拦截 OpenAI 官方 SDK"""
    print("\n" + "=" * 60)
    print("演示1: LLMInterceptor + OpenAI 官方 SDK")
    print("=" * 60)

    uploader = AsyncBatchUploader(backend_url=BACKEND_URL, batch_size=5)
    await uploader.start()

    interceptor = LLMInterceptor(uploader)

    # 创建真实 OpenAI 客户端
    from openai import OpenAI
    client = OpenAI(api_key=OPENAI_KEY)
    client = interceptor.wrap(client)  # ← 一行代码完成拦截

    # 创建根 Trace
    root = TraceContext(name="openai_real_call")
    set_current_context(root)

    for question in ["1+1 等于几？", "什么是 AI Agent？"]:
        response = client.chat.completions.create(
            model="gpt-5.4-mini",
            messages=[{"role": "user", "content": question}],
            stream=False,
        )
        answer = response.choices[0].message.content
        print(f"\nQ: {question}")
        print(f"A: {answer[:80]}...")

    # 流式调用
    print("\n--- 流式调用 ---")
    stream = client.chat.completions.create(
        model="gpt-5.4-mini",
        messages=[{"role": "user", "content": "用一句话介绍 Rust 语言"}],
        stream=True,
    )
    for chunk in stream:
        delta = chunk.choices[0].delta
        if delta and delta.content:
            print(delta.content, end="", flush=True)
    print()

    clear_current_context()
    await asyncio.sleep(0.5)
    await uploader.stop()
    print("✅ 完成，数据已上报到后端")


# ===================================================================
# Demo 2: Anthropic Claude
# ===================================================================

async def demo_llm_interceptor_anthropic():
    """演示2：拦截 Anthropic Claude SDK"""
    print("\n" + "=" * 60)
    print("演示2: LLMInterceptor + Anthropic Claude SDK")
    print("=" * 60)

    if not HAS_ANTHROPIC:
        print("⚠️  跳过：未配置 ANTHROPIC_API_KEY")
        return

    uploader = AsyncBatchUploader(backend_url=BACKEND_URL, batch_size=5)
    await uploader.start()

    interceptor = LLMInterceptor(uploader)

    from anthropic import Anthropic
    client = Anthropic(api_key=ANTHROPIC_KEY)
    client = interceptor.wrap(client)  # ← 同一套 API

    root = TraceContext(name="anthropic_real_call")
    set_current_context(root)

    response = client.messages.create(
        model="claude-haiku-4-5",
        max_tokens=100,
        messages=[{"role": "user", "content": "什么是可观测性？请用一句话回答"}],
    )
    print(f"Claude: {response.content[0].text[:100]}")

    clear_current_context()
    await asyncio.sleep(0.5)
    await uploader.stop()
    print("✅ 完成")


# ===================================================================
# Demo 3: DeepSeek / vLLM / Ollama（OpenAI 兼容协议）
# ===================================================================

async def demo_llm_interceptor_deepseek():
    """演示3：拦截 DeepSeek（或其他 OpenAI 兼容接口）"""
    print("\n" + "=" * 60)
    print("演示3: LLMInterceptor + DeepSeek (OpenAI 兼容)")
    print("=" * 60)

    if not HAS_DEEPSEEK:
        print("⚠️  跳过：未配置 DEEPSEEK_API_KEY")
        return

    uploader = AsyncBatchUploader(backend_url=BACKEND_URL, batch_size=5)
    await uploader.start()

    interceptor = LLMInterceptor(uploader)

    from openai import OpenAI
    client = OpenAI(api_key=DEEPSEEK_KEY, base_url=DEEPSEEK_BASE)
    client = interceptor.wrap(client)  # ← 同样是 OpenAI SDK，自动识别

    root = TraceContext(name="deepseek_call")
    set_current_context(root)

    response = client.chat.completions.create(
        model="deepseek-chat",
        messages=[{"role": "user", "content": "简单解释一下什么是 Kubernetes"}],
        stream=False,
    )
    print(f"DeepSeek: {response.choices[0].message.content[:150]}")

    clear_current_context()
    await asyncio.sleep(0.5)
    await uploader.stop()
    print("✅ 完成")


# ===================================================================
# Demo 4: ToolSDK 装饰器自动埋点
# ===================================================================

def make_calculator(uploader):
    """返回一个被 ToolSDK 装饰的计算器"""
    ts = ToolSDK(uploader)

    @ts.instrument(name="calculator", tool_type="math")
    def calc(expression: str) -> float:
        return eval(expression)

    return calc


async def demo_tool_sdk():
    """演示4：ToolSDK 装饰器自动埋点"""
    print("\n" + "=" * 60)
    print("演示4: ToolSDK 装饰器自动埋点")
    print("=" * 60)

    uploader = AsyncBatchUploader(backend_url=BACKEND_URL, batch_size=5)
    await uploader.start()

    calc = make_calculator(uploader)

    root = TraceContext(name="agent_task_with_tool")
    set_current_context(root)

    results = [
        calc("2 + 3"),
        calc("10 * 5 + 2"),
        calc("100 / 4"),
    ]
    print(f"计算结果: {results}")

    clear_current_context()
    await asyncio.sleep(0.5)
    await uploader.stop()
    print("✅ 完成")


# ===================================================================
# Demo 5: TraceAPI 显式链路控制
# ===================================================================

async def demo_trace_api():
    """演示5：TraceAPI 显式 startTrace/startSpan/endSpan"""
    print("\n" + "=" * 60)
    print("演示5: TraceAPI 显式链路控制")
    print("=" * 60)

    uploader = AsyncBatchUploader(backend_url=BACKEND_URL, batch_size=5)
    await uploader.start()

    trace = TraceAPI(uploader)

    # 开始一个完整 Trace
    ctx = trace.start_trace(name="agent_complete_flow")

    # LLM 调用 Span
    llm_span = trace.start_span(name="llm_reasoning", attributes={"model": "gpt-5.4"})
    time.sleep(0.3)  # 模拟 LLM 调用
    trace.end_span(llm_span, span_type="trace")

    # Tool 调用 Span
    tool_span = trace.start_span(name="tool_calculator", attributes={"tool": "calculator"})
    time.sleep(0.1)  # 模拟 Tool 调用
    trace.end_span(tool_span, span_type="trace")

    # 结束 Trace
    trace.end_span(ctx, span_type="trace")

    clear_current_context()
    await asyncio.sleep(0.5)
    await uploader.stop()
    print("✅ 完成")


# ===================================================================
# Demo 6: Session 完整生命周期
# ===================================================================

async def demo_session_lifecycle():
    """演示6：Session 完整生命周期自动聚合上报"""
    print("\n" + "=" * 60)
    print("演示6: Session 完整生命周期（SessionSDK）")
    print("=" * 60)

    uploader = AsyncBatchUploader(backend_url=BACKEND_URL, batch_size=5)
    await uploader.start()

    session_sdk = SessionSDK(uploader)
    trace = TraceAPI(uploader)

    # 显式开始 Session
    sess = session_sdk.start_session(
        name="user_session_demo",
        agent_name="demo-agent",
        user_input="演示用户输入",
    )

    # 模拟 3 轮对话
    for round_num in range(3):
        span = trace.start_span(
            name=f"round_{round_num + 1}",
            attributes={"round": round_num + 1, "session_id": sess.session_id},
        )
        time.sleep(0.15)
        trace.end_span(span)

    # 再模拟一次 LLM 调用，验证 token / 成本自动聚合
    llm_span = trace.start_span(name="llm_summary", attributes={"model": "gpt-5.4-mini"})
    metrics_span = SpanData(
        trace_id=sess.session_id,
        span_id=llm_span.span_id,
        parent_span_id=llm_span.parent_span_id,
        name="llm_metrics",
        start_time=datetime.utcnow().isoformat(),
        end_time=datetime.utcnow().isoformat(),
        span_type="llm_metrics",
        attributes={
            "model_name": "gpt-5.4-mini",
            "prefill_ms": 100.0,
            "decode_ms": 200.0,
            "input_tokens": 1000,
            "output_tokens": 500,
            "tps": 2500.0,
        },
    )
    await uploader.submit(metrics_span)
    trace.end_span(llm_span)

    # 结束 Session，自动聚合 span 数 / token / 成本 / 耗时
    session_sdk.end_session(
        sess,
        final_response="演示最终响应",
        status="completed",
    )

    await asyncio.sleep(0.5)
    await uploader.stop()
    print("✅ 完成")


# ===================================================================
# Main
# ===================================================================

async def main():
    print("=" * 60)
    print("  Agent-Insight SDK 完整演示  (v0.3.0)")
    print("=" * 60)
    print(f"\n🔧 后端地址: {BACKEND_URL}")
    print(f"🔑 OpenAI:   {'✅ 已配置' if HAS_OPENAI else '⚠️  未配置（将跳过）'}")
    print(f"🔑 Anthropic:{'✅ 已配置' if HAS_ANTHROPIC else '⚠️  未配置（将跳过）'}")
    print(f"🔑 DeepSeek: {'✅ 已配置' if HAS_DEEPSEEK else '⚠️  未配置（将跳过）'}")

    # 1. LLMInterceptor — 多厂商统一拦截
    await demo_llm_interceptor_openai()
    await demo_llm_interceptor_anthropic()
    await demo_llm_interceptor_deepseek()

    # 2. ToolSDK
    await demo_tool_sdk()

    # 3. TraceAPI
    await demo_trace_api()

    # 4. Session
    await demo_session_lifecycle()

    print()
    print("=" * 60)
    print("  全部演示完成！")
    print(f"  打开浏览器 http://localhost:3000 查看 Dashboard")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
