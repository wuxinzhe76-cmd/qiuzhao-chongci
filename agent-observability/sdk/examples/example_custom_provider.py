"""
自定义 Provider Adapter 示例 — 接入一个假想的 LLM SDK

本示例演示如何通过继承 BaseProviderAdapter 接入 SDK 未内置的 LLM 厂商。

场景：假设有一个名为 "MyLLM" 的国产大模型 SDK，接口与 OpenAI 不同：
  - 调用方式：client.chat(query="...", model="...")
  - 返回格式：{"text": "...", "tokens_in": 100, "tokens_out": 50}

内置的 OpenAICompatibleAdapter 无法识别它，需要自定义 Adapter。

接入步骤：
  1. 继承 BaseProviderAdapter
  2. 实现 supports() / _wrap_call() / _unwrap_client()
  3. 重写 extract() 从自定义响应格式提取指标
  4. register_adapter() 注册（或传给 LLMInterceptor 构造函数）

无需真实 API Key，使用 Mock SDK 演示。
"""

import asyncio
import functools
import os
import time
from datetime import datetime
from typing import Any, Dict

from agent_insight_sdk import (
    AsyncBatchUploader,
    BaseProviderAdapter,
    LLMCallRecord,
    LLMInterceptor,
    register_adapter,
    TraceContext,
    set_current_context,
    clear_current_context,
)
from agent_insight_sdk.context import get_current_context

BACKEND_URL = os.getenv("AGENT_INSIGHT_BACKEND", "http://localhost:8000")


# ===================================================================
# 1. 假想的 MyLLM SDK（模拟第三方 LLM 客户端）
# ===================================================================

class MyLLMResponse:
    """MyLLM 的响应格式（与 OpenAI 不同）"""

    def __init__(self, text: str, tokens_in: int, tokens_out: int, latency_ms: float):
        self.text = text
        self.tokens_in = tokens_in
        self.tokens_out = tokens_out
        self.latency_ms = latency_ms


class MyLLMClient:
    """假想的 MyLLM SDK 客户端"""

    def __init__(self, api_key: str = ""):
        self.api_key = api_key
        # chat 是一个对象，上面挂 create 方法（模拟 OpenAI 的 client.chat.completions.create 结构）
        self.chat = self._ChatNamespace()

    class _ChatNamespace:
        def create(self, query: str, model: str = "myllm-7b", **kwargs) -> MyLLMResponse:
            """MyLLM 的调用接口：client.chat.create(query=..., model=...)"""
            start = time.perf_counter()
            time.sleep(0.3)  # 模拟推理延迟

            # 模拟响应（实际场景由 API 返回）
            latency = (time.perf_counter() - start) * 1000
            return MyLLMResponse(
                text=f"[MyLLM] 收到问题「{query}」，这是模拟回答。",
                tokens_in=len(query) // 3,   # 模拟 input tokens
                tokens_out=80,                # 模拟 output tokens
                latency_ms=latency,
            )


# ===================================================================
# 2. 自定义 Provider Adapter
# ===================================================================

class MyLLMAdapter(BaseProviderAdapter):
    """接入 MyLLM SDK 的自定义 Adapter

    继承 BaseProviderAdapter，实现三个核心方法 + 重写 extract()
    """

    provider_name = "myllm"

    def supports(self, client: Any) -> bool:
        """判断是否能处理该客户端 — 通过 chat.create 方法签名识别"""
        try:
            return (
                hasattr(client, "chat")
                and hasattr(client.chat, "create")
                and not hasattr(client.chat, "completions")  # 排除 OpenAI 兼容客户端
            )
        except Exception:
            return False

    def _wrap_call(self, client: Any, interceptor: Any) -> Any:
        """包装 client.chat.create 方法"""
        self._original = client.chat.create
        self._client = client
        self._interceptor = interceptor

        @functools.wraps(self._original)
        def wrapper(*args, **kwargs):
            return self._handle(args, kwargs)

        client.chat.create = wrapper
        return client

    def _unwrap_client(self, wrapped: Any) -> None:
        """恢复原始方法"""
        if hasattr(self, "_original") and self._original:
            self._client.chat.create = self._original
            self._original = None

    def _handle(self, args: tuple, kwargs: dict) -> Any:
        """核心拦截逻辑（与 OpenAICompatibleAdapter._handle 类似）"""
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

        try:
            response = self._original(*args, **kwargs)
        except Exception as exc:
            record = self.extract(kwargs, None, perf_start, is_stream=False)
            record.error = str(exc)
            interceptor._report(ctx, record, start_time, datetime.utcnow())
            raise

        record = self.extract(kwargs, response, perf_start, is_stream=False)
        interceptor._report(ctx, record, start_time, datetime.utcnow())
        return response

    def extract(
        self,
        kwargs: Dict[str, Any],
        response: Any,
        perf_start: float,
        is_stream: bool,
    ) -> LLMCallRecord:
        """从 MyLLM 的自定义响应格式提取指标

        重写父类的 extract()，因为 MyLLM 响应格式与 OpenAI 不同：
          - response.text         → 回答文本
          - response.tokens_in    → 输入 token 数
          - response.tokens_out   → 输出 token 数
          - response.latency_ms   → 总延迟
        """
        latency_ms = (time.perf_counter() - perf_start) * 1000
        record = LLMCallRecord(
            model=kwargs.get("model", "myllm-7b"),
            provider=self.provider_name,
            is_stream=is_stream,
            latency_ms=latency_ms,
        )

        if response is not None:
            record.response_text = getattr(response, "text", "")
            record.input_tokens = getattr(response, "tokens_in", 0)
            record.output_tokens = getattr(response, "tokens_out", 0)
            # MyLLM 没有独立的 prefill/decode 指标，按经验拆分
            record.prefill_ms = latency_ms * 0.2
            record.decode_ms = latency_ms * 0.8
            if record.decode_ms > 0 and record.output_tokens > 0:
                record.tps = record.output_tokens / (record.decode_ms / 1000.0)

        # 提取 prompt（MyLLM 用 query 参数而非 messages）
        record.prompt_text = kwargs.get("query", "")

        return record


# ===================================================================
# 3. 注册 Adapter 并演示
# ===================================================================

async def main():
    print("=" * 60)
    print("  自定义 Provider Adapter 示例：接入 MyLLM SDK")
    print("=" * 60)
    print(f"后端地址: {BACKEND_URL}（未启动则数据上报失败，不影响演示）\n")

    # 方式一：全局注册（导入即生效，所有 LLMInterceptor 都能用）
    register_adapter(MyLLMAdapter())
    print("✅ 已注册 MyLLMAdapter")

    uploader = AsyncBatchUploader(backend_url=BACKEND_URL, batch_size=5)
    await uploader.start()

    # 方式二：传给单个 LLMInterceptor（只对该实例生效）
    # interceptor = LLMInterceptor(uploader, adapters=[MyLLMAdapter()])
    interceptor = LLMInterceptor(uploader)

    # 创建 MyLLM 客户端并拦截
    client = MyLLMClient(api_key="fake-key")
    print(f"✅ MyLLMAdapter.supports(client) = {interceptor._adapters[-1].supports(client)}")

    client = interceptor.wrap(client)  # ← 一行代码完成拦截

    # 创建根 Trace
    root = TraceContext(name="myllm_demo")
    set_current_context(root)

    # 调用 MyLLM（接口与 OpenAI 不同，但拦截器已适配）
    print("\n调用 MyLLM...")
    response = client.chat.create(
        query="什么是可观测性？",
        model="myllm-7b",
    )
    print(f"回答: {response.text}")
    print(f"Tokens: {response.tokens_in} in / {response.tokens_out} out")

    # 再调用一次，验证多次调用都被拦截
    print("\n第二次调用...")
    response2 = client.chat.create(
        query="解释一下 Trace 和 Span 的区别",
        model="myllm-13b",
    )
    print(f"回答: {response2.text}")

    clear_current_context()
    await asyncio.sleep(0.5)
    await uploader.stop()

    print("\n" + "=" * 60)
    print("  完成！上报的 llm_metrics 包含 provider='myllm'")
    print("  在前端模型效能对比页面可按 provider 筛选分析")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
