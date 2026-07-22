"""
端到端 RAG Agent 示例 — 无需真实 LLM API Key 的完整可观测 Agent

本示例模拟一个完整的 RAG（检索增强生成）Agent 流程：
  1. 接收用户问题
  2. 用 RAG 检索相关知识
  3. 调用 LLM 基于检索结果生成回答
  4. 调用 MCP 工具持久化结果

全程被 SDK 自动埋点，产生完整的 Trace 链路：
  session (根)
    ├── rag:retrieve_documents   (RAG 检索 span)
    ├── llm_call                 (LLM 调用 trace span)
    │   ├── llm_metrics          (LLM 性能指标)
    │   └── prompt_log           (Prompt/Response 日志)
    └── mcp:save_result          (MCP 工具调用 span)

特点：
  - 使用 Mock LLM，无需 API Key
  - 使用 Mock 向量库，无需真实 RAG 基础设施
  - 使用 Mock MCP 服务，无需真实 MCP 服务器
  - 但 SDK 埋点、Session 聚合、数据上报都是真实的
  - 启动后端后运行，可在 Dashboard 看到完整链路

运行：cd sdk && pip install -e . && python examples/example_rag_agent.py
"""

import asyncio
import json
import os
import time
from datetime import datetime

from agent_insight_sdk import (
    AsyncBatchUploader,
    LLMInterceptor,
    SessionSDK,
    SpanData,
    ToolSDK,
    TraceContext,
    set_current_context,
    clear_current_context,
)
from agent_insight_sdk.providers.base import BaseProviderAdapter, LLMCallRecord

BACKEND_URL = os.getenv("AGENT_INSIGHT_BACKEND", "http://localhost:8000")


# ===================================================================
# Mock 基础设施（替代真实的 LLM API / 向量库 / MCP 服务）
# ===================================================================

# 模拟知识库
KNOWLEDGE_BASE = [
    {"id": 1, "content": "Python 的 GIL 是全局解释器锁，同一时刻只允许一个线程执行 Python 字节码。", "topic": "python"},
    {"id": 2, "content": "asyncio 是 Python 的异步 IO 框架，使用 async/await 语法编写并发代码。", "topic": "python"},
    {"id": 3, "content": "RAG 通过检索外部知识增强 LLM 的回答准确性，缓解幻觉问题。", "topic": "rag"},
    {"id": 4, "content": "向量数据库存储文本的 embedding，支持相似度检索，如 Pinecone、Milvus。", "topic": "rag"},
    {"id": 5, "content": "MCP（Model Context Protocol）是 Anthropic 提出的模型上下文协议，统一工具调用接口。", "topic": "mcp"},
]


class MockLLMResponse:
    """模拟 OpenAI 风格的 LLM 响应"""
    class _Choice:
        class _Message:
            def __init__(self, content):
                self.content = content
        class _Delta:
            def __init__(self):
                self.content = None
        def __init__(self, content):
            self.message = self._Message(content)
            self.delta = self._Delta()
    class _Usage:
        def __init__(self, p, c):
            self.prompt_tokens = p
            self.completion_tokens = c

    def __init__(self, content: str, prompt_tokens: int, completion_tokens: int):
        self.choices = [self._Choice(content)]
        self.usage = self._Usage(prompt_tokens, completion_tokens)


class MockOpenAIClient:
    """模拟 OpenAI 客户端，无需 API Key 即可演示拦截器

    接口与真实 OpenAI SDK 一致：client.chat.completions.create(...)
    OpenAICompatibleAdapter 能自动识别并拦截它。
    """
    def __init__(self):
        self.chat = self._ChatNamespace()

    class _ChatNamespace:
        def __init__(self):
            # _CompletionsNamespace 定义在外层 MockOpenAIClient 上，需通过类名引用
            self.completions = MockOpenAIClient._CompletionsNamespace()

    class _CompletionsNamespace:
        def create(self, messages, model="gpt-5.4-mini", stream=False, **kwargs):
            # 提取最后一条用户消息作为问题
            user_msg = ""
            for m in messages:
                if m.get("role") == "user":
                    user_msg = m.get("content", "")

            # 模拟推理延迟
            time.sleep(0.4)

            # 模拟回答
            answer = f"基于检索到的知识，回答「{user_msg[:30]}...」：这是一个模拟回答。"
            prompt_tokens = sum(len(m.get("content", "")) // 3 for m in messages)
            completion_tokens = len(answer) // 3

            return MockLLMResponse(answer, prompt_tokens, completion_tokens)


# ===================================================================
# RAG Agent 实现（全部用 SDK 装饰器埋点）
# ===================================================================

def create_rag_agent(uploader: AsyncBatchUploader):
    """创建一个被完整埋点的 RAG Agent"""

    tool_sdk = ToolSDK(uploader)
    interceptor = LLMInterceptor(uploader)
    llm_client = interceptor.wrap(MockOpenAIClient())  # 拦截 LLM 调用

    # --- RAG 检索工具 ---
    @tool_sdk.instrument_rag(vector_db="mock-vector-db", name="retrieve", top_k=3)
    def retrieve_knowledge(query: str) -> list:
        """向量检索：返回 list，recall_count 自动提取为 len(list)"""
        # 简单关键词匹配模拟语义检索
        keywords = query.lower().replace("？", "").replace("是什么", "").replace("什么是", "").split()
        results = [
            doc for doc in KNOWLEDGE_BASE
            if any(k in doc["content"] for k in keywords) or any(k in doc["topic"] for k in keywords)
        ]
        return results[:3]

    # --- MCP 持久化工具 ---
    @tool_sdk.instrument_mcp(server="database", tool="save_answer")
    def save_answer(question: str, answer: str) -> dict:
        """通过 MCP 协议保存问答记录"""
        return {"saved": True, "id": f"qa-{int(time.time())}"}

    # --- Agent 主流程 ---
    def rag_agent(user_input: str) -> str:
        """完整 RAG Agent 流程"""
        # 1. 检索知识
        docs = retrieve_knowledge(user_input)
        context = "\n".join(d["content"] for d in docs)

        # 2. 构造 Prompt 调用 LLM
        messages = [
            {"role": "system", "content": f"基于以下知识回答问题：\n{context}"},
            {"role": "user", "content": user_input},
        ]
        response = llm_client.chat.completions.create(
            model="gpt-5.4-mini",
            messages=messages,
            stream=False,
        )
        answer = response.choices[0].message.content

        # 3. 通过 MCP 保存结果
        save_result = save_answer(user_input, answer)

        return answer

    return rag_agent


# ===================================================================
# 主流程：用 Session 包裹多次问答
# ===================================================================

async def main():
    print("=" * 60)
    print("  端到端 RAG Agent 示例（Mock，无需 API Key）")
    print("=" * 60)
    print(f"后端地址: {BACKEND_URL}（未启动则数据上报失败，不影响演示）\n")

    uploader = AsyncBatchUploader(backend_url=BACKEND_URL, batch_size=5)
    await uploader.start()

    session_sdk = SessionSDK(uploader)
    rag_agent = create_rag_agent(uploader)

    questions = [
        "什么是 Python 的 GIL？",
        "RAG 是什么？",
        "MCP 协议有什么用？",
    ]

    # 用 Session 上下文管理器包裹整个对话
    with session_sdk.session(
        name="rag_agent_session",
        agent_name="rag-agent",
        user_input=questions[0],
    ) as sess:
        print(f"Session ID: {sess.session_id}\n")

        for i, question in enumerate(questions, 1):
            print(f"【问题 {i}】{question}")
            answer = rag_agent(question)
            print(f"【回答】{answer}\n")

        # Session 结束时自动聚合 span 数 / token 数 / 成本 / 耗时并上报

    await asyncio.sleep(1.0)  # 等待异步上报完成
    await uploader.stop()

    print("=" * 60)
    print("  完成！产生的 Trace 链路结构：")
    print("  session (根 span)")
    print("    ├── tool:retrieve    (RAG 检索，attributes 含 rag_* 元数据)")
    print("    ├── llm_call         (LLM 调用 trace span)")
    print("    │   ├── llm_metrics  (性能指标：prefill/decode/TPS/provider)")
    print("    │   └── prompt_log   (Prompt/Response 完整内容)")
    print("    └── tool:save_answer (MCP 工具，attributes 含 mcp_* 元数据)")
    print("\n  在前端可查看：")
    print("  - 链路跟踪：完整 span 树瀑布图")
    print("  - Prompt 回放：LLM 对话 + Tool 调用详情 + MCP/RAG 元数据")
    print("  - Session 会话：聚合的 token 数和成本")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
