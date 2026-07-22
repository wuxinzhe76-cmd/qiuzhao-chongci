"""
ToolSDK 进阶示例 — MCP 协议工具 & RAG 检索自动埋点

本脚本演示 ToolSDK 的三种装饰器，重点展示新增的：
  - instrument_mcp():  MCP 协议工具调用（自动填充 mcp_server / mcp_tool 元数据）
  - instrument_rag():  RAG 检索调用（自动填充 rag_vector_db / rag_top_k / rag_recall_count）

这些元数据会写入 tool_calls 表的 attributes 列，前端 Prompt 回放页面会展示。

无需真实 LLM API Key，无需启动后端即可运行（数据上报失败会被静默忽略）。
如需查看上报数据，启动后端后再运行本脚本。
"""

import asyncio
import os

from agent_insight_sdk import (
    AsyncBatchUploader,
    SessionSDK,
    ToolSDK,
    TraceContext,
    set_current_context,
    clear_current_context,
)

BACKEND_URL = os.getenv("AGENT_INSIGHT_BACKEND", "http://localhost:8000")


# ===================================================================
# 1. 通用 Tool（基础用法，回顾）
# ===================================================================

def make_calculator(tool_sdk: ToolSDK):
    """通用计算器：使用 instrument 装饰器"""

    @tool_sdk.instrument(name="calculator", tool_type="math")
    def calc(expression: str) -> float:
        """同步计算器，异常会被自动捕获并上报为 error 状态"""
        return eval(expression)  # 简化示例，生产环境用安全解析器

    return calc


# ===================================================================
# 2. MCP Tool — 模拟 MCP 协议工具调用
# ===================================================================

def make_mcp_tools(tool_sdk: ToolSDK):
    """MCP 协议工具：使用 instrument_mcp 装饰器

    自动在 attributes 中填充：
      - tool_subtype: "mcp"
      - mcp_server:   服务端名称
      - mcp_tool:     工具名称
      - mcp_protocol_version (可选)
    """

    @tool_sdk.instrument_mcp(server="github", tool="create_issue", protocol_version="2024-11-05")
    def create_github_issue(title: str, body: str = "") -> dict:
        """模拟通过 MCP 调用 GitHub 创建 Issue"""
        # 实际场景：mcp_client.call("github", "create_issue", ...)
        return {"issue_number": 42, "url": f"https://github.com/x/y/issues/42", "title": title}

    @tool_sdk.instrument_mcp(server="slack", tool="send_message")
    def send_slack_message(channel: str, text: str) -> dict:
        """模拟通过 MCP 调用 Slack 发送消息"""
        return {"ok": True, "channel": channel, "ts": "1700000000.000100"}

    return create_github_issue, send_slack_message


# ===================================================================
# 3. RAG Tool — 模拟向量检索
# ===================================================================

def make_rag_tools(tool_sdk: ToolSDK):
    """RAG 检索工具：使用 instrument_rag 装饰器

    自动在 attributes 中填充：
      - tool_subtype:    "rag"
      - rag_vector_db:   向量库名称
      - rag_top_k:       检索 Top K
      - rag_recall_count: 实际召回数量（从返回值自动提取）

    recall_count 提取规则：
      - 返回 list  → len(list)
      - 返回 dict  → 取 results/documents/chunks/hits/matches 之一的长度，或 count 字段
    """

    # 模拟知识库
    KNOWLEDGE_BASE = [
        {"id": 1, "content": "Python 是一种解释型语言", "score": 0.95},
        {"id": 2, "content": "GIL 是 Python 的全局解释器锁", "score": 0.88},
        {"id": 3, "content": "asyncio 是 Python 的异步 IO 库", "score": 0.82},
        {"id": 4, "content": "TypeScript 是 JavaScript 的超集", "score": 0.91},
        {"id": 5, "content": "Vite 是新一代前端构建工具", "score": 0.86},
    ]

    @tool_sdk.instrument_rag(vector_db="pinecone", name="retrieve_docs", top_k=3)
    def retrieve_documents(query: str) -> list:
        """模拟向量检索，返回 list → recall_count = len(list)"""
        # 简单关键词匹配模拟语义检索
        results = [doc for doc in KNOWLEDGE_BASE if any(w in doc["content"] for w in query.split())]
        return results[:3]  # 返回最多 3 条

    @tool_sdk.instrument_rag(vector_db="milvus", name="retrieve_chunks", top_k=5)
    def retrieve_chunks_with_count(query: str) -> dict:
        """返回 dict → recall_count 取 results 字段长度"""
        results = [doc for doc in KNOWLEDGE_BASE if any(w in doc["content"] for w in query.split())]
        return {
            "results": results[:5],
            "query": query,
            "total_matches": len(results),
        }

    return retrieve_documents, retrieve_chunks_with_count


# ===================================================================
# 4. 异步 Tool — MCP 异步调用
# ===================================================================

def make_async_mcp_tool(tool_sdk: ToolSDK):
    """异步 MCP 工具：装饰器自动识别 async 函数"""

    @tool_sdk.instrument_mcp(server="filesystem", tool="read_file")
    async def read_file_async(path: str) -> str:
        """模拟异步读取文件"""
        await asyncio.sleep(0.1)  # 模拟 IO 延迟
        return f"[content of {path}]"

    return read_file_async


# ===================================================================
# 主流程：在 Session 上下文中执行各类 Tool
# ===================================================================

async def main():
    print("=" * 60)
    print("  ToolSDK 进阶示例：MCP & RAG 自动埋点")
    print("=" * 60)
    print(f"后端地址: {BACKEND_URL}（未启动则数据上报失败，不影响演示）\n")

    uploader = AsyncBatchUploader(backend_url=BACKEND_URL, batch_size=5)
    await uploader.start()

    tool_sdk = ToolSDK(uploader)
    session_sdk = SessionSDK(uploader)

    # 创建各类工具
    calc = make_calculator(tool_sdk)
    create_issue, send_slack = make_mcp_tools(tool_sdk)
    retrieve_documents, retrieve_chunks = make_rag_tools(tool_sdk)
    read_file = make_async_mcp_tool(tool_sdk)

    # 用 Session 包裹整个流程，自动聚合 span / token / 成本
    sess = session_sdk.start_session(
        name="mcp_rag_demo",
        agent_name="tool-demo-agent",
        user_input="演示 MCP 和 RAG 工具调用",
    )

    # --- 1. 通用计算器 ---
    print("【1】通用 Tool（instrument）")
    print(f"    2 + 3 * 4 = {calc('2 + 3 * 4')}")
    print(f"    100 / 4 = {calc('100 / 4')}")

    # --- 2. MCP 工具 ---
    print("\n【2】MCP Tool（instrument_mcp）")
    issue = create_issue("Fix login bug", "用户无法登录")
    print(f"    GitHub Issue: {issue}")
    msg = send_slack("#alerts", "部署完成")
    print(f"    Slack 消息: {msg}")

    # --- 3. RAG 检索 ---
    print("\n【3】RAG Tool（instrument_rag）")
    docs = retrieve_documents("Python 异步")
    print(f"    检索到 {len(docs)} 条文档（返回 list，recall_count 自动提取）")
    for d in docs:
        print(f"      - {d['content']}")

    chunks = retrieve_chunks("TypeScript 前端")
    print(f"    检索到 {len(chunks['results'])} 条 chunk（返回 dict，recall_count 从 results 提取）")

    # --- 4. 异步 MCP 工具 ---
    print("\n【4】异步 MCP Tool（async + instrument_mcp）")
    content = await read_file("/etc/config.yaml")
    print(f"    文件内容: {content}")

    # 结束 Session
    session_sdk.end_session(sess, final_response="演示完成", status="completed")

    await asyncio.sleep(0.5)
    await uploader.stop()

    print("\n" + "=" * 60)
    print("  完成！上报的 tool_calls 包含 attributes 元数据：")
    print("  - MCP: tool_subtype=mcp, mcp_server, mcp_tool")
    print("  - RAG: tool_subtype=rag, rag_vector_db, rag_top_k, rag_recall_count")
    print("  在前端 Prompt 回放页面可查看这些元数据")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
