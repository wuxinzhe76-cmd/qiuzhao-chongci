"""
ToolSDK 单元测试
"""

import asyncio
import json

import pytest

from agent_insight_sdk import ToolSDK, clear_current_context, set_current_context
from agent_insight_sdk.context import TraceContext


@pytest.mark.asyncio
async def test_tool_sdk_sync(fake_uploader):
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument(name="calculator", tool_type="math")
    def calculator(expression: str) -> float:
        return eval(expression)

    root = TraceContext(name="agent_task")
    set_current_context(root)

    result = calculator("2 + 3")
    await asyncio.sleep(0.05)

    assert result == 5
    tool_spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert len(tool_spans) == 1
    assert tool_spans[0]["tool_name"] == "calculator"
    assert tool_spans[0]["tool_type"] == "math"
    assert tool_spans[0]["status"] == "success"

    # 解析 input_data JSON 后验证结构，避免依赖序列化空格格式
    input_data = json.loads(tool_spans[0]["input_data"])
    assert input_data["args"] == ["2 + 3"]
    assert input_data["kwargs"] == {}

    clear_current_context()


@pytest.mark.asyncio
async def test_tool_sdk_async(fake_uploader):
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument(name="weather", tool_type="api")
    async def weather(city: str) -> dict:
        return {"city": city, "temp": 25}

    root = TraceContext(name="agent_task")
    set_current_context(root)

    result = await weather("Beijing")
    await asyncio.sleep(0.05)

    assert result == {"city": "Beijing", "temp": 25}
    tool_spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert len(tool_spans) == 1
    assert tool_spans[0]["tool_name"] == "weather"
    assert tool_spans[0]["status"] == "success"

    clear_current_context()


@pytest.mark.asyncio
async def test_tool_sdk_error(fake_uploader):
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument(name="fail", tool_type="generic")
    def fail_tool():
        raise ValueError("boom")

    root = TraceContext(name="agent_task")
    set_current_context(root)

    with pytest.raises(ValueError):
        fail_tool()

    await asyncio.sleep(0.05)

    tool_spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert len(tool_spans) == 1
    assert tool_spans[0]["status"] == "error"
    assert "boom" in tool_spans[0]["error"]

    clear_current_context()


def test_tool_sdk_default_name(fake_uploader):
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument()
    def my_tool():
        return 1

    root = TraceContext(name="agent_task")
    set_current_context(root)

    my_tool()
    tool_spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert tool_spans[0]["tool_name"] == "my_tool"

    clear_current_context()


@pytest.mark.asyncio
async def test_tool_sdk_no_parent_context(fake_uploader):
    """无父上下文时 ToolSDK 应独立创建 trace，不抛异常"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument(name="standalone", tool_type="util")
    def standalone_tool(x: int) -> int:
        return x * 2

    clear_current_context()
    result = standalone_tool(21)
    await asyncio.sleep(0.05)

    assert result == 42
    tool_spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert len(tool_spans) == 1
    assert tool_spans[0]["tool_name"] == "standalone"
    assert tool_spans[0]["parent_span_id"] == ""  # None → 空字符串

    clear_current_context()


@pytest.mark.asyncio
async def test_tool_sdk_kwargs_in_input(fake_uploader):
    """kwargs 应正确出现在 input_data 中"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument(name="search")
    def search(query: str, limit: int = 10, filter: str = "all") -> dict:
        return {"query": query, "limit": limit}

    root = TraceContext(name="agent_task")
    set_current_context(root)

    search("python", limit=5, filter="recent")
    await asyncio.sleep(0.05)

    tool_spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert len(tool_spans) == 1
    input_data = json.loads(tool_spans[0]["input_data"])
    assert input_data["args"] == ["python"]
    assert input_data["kwargs"] == {"limit": 5, "filter": "recent"}

    # output_data 也应正确序列化
    output_data = json.loads(tool_spans[0]["output_data"])
    assert output_data == {"query": "python", "limit": 5}

    clear_current_context()


@pytest.mark.asyncio
async def test_tool_sdk_output_serialization_fallback(fake_uploader):
    """无法 JSON 序列化的对象应 fallback 到 str"""
    tool_sdk = ToolSDK(fake_uploader)

    class NonSerializable:
        def __str__(self):
            return "<non-serializable>"

    @tool_sdk.instrument(name="weird")
    def weird_tool():
        return NonSerializable()

    root = TraceContext(name="agent_task")
    set_current_context(root)

    result = weird_tool()
    await asyncio.sleep(0.05)

    assert isinstance(result, NonSerializable)
    tool_spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert len(tool_spans) == 1
    assert tool_spans[0]["status"] == "success"
    # output_data 应该是 str fallback
    assert "<non-serializable>" in tool_spans[0]["output_data"]

    clear_current_context()


# ======================================================================
# instrument() 现在带 tool_subtype="normal"
# ======================================================================


@pytest.mark.asyncio
async def test_instrument_sets_tool_subtype_normal(fake_uploader):
    """instrument() 应在 attributes 中设置 tool_subtype='normal'"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument(name="calc", tool_type="math")
    def calc(x: int) -> int:
        return x * 2

    root = TraceContext(name="root")
    set_current_context(root)

    calc(3)
    await asyncio.sleep(0.05)

    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert len(spans) == 1
    assert spans[0]["attributes"]["tool_subtype"] == "normal"

    clear_current_context()


# ======================================================================
# instrument_mcp() 测试
# ======================================================================


@pytest.mark.asyncio
async def test_mcp_decorator_basic(fake_uploader):
    """instrument_mcp 应设置 mcp 相关 attributes"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument_mcp(server="github", tool="create_issue")
    def create_issue(title: str) -> dict:
        return {"id": 1, "title": title}

    root = TraceContext(name="root")
    set_current_context(root)

    result = create_issue("bug fix")
    await asyncio.sleep(0.05)

    assert result == {"id": 1, "title": "bug fix"}
    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert len(spans) == 1
    attrs = spans[0]["attributes"]
    assert attrs["tool_subtype"] == "mcp"
    assert attrs["mcp_server"] == "github"
    assert attrs["mcp_tool"] == "create_issue"
    assert spans[0]["tool_type"] == "mcp"
    assert spans[0]["tool_name"] == "create_issue"
    assert spans[0]["status"] == "success"

    clear_current_context()


@pytest.mark.asyncio
async def test_mcp_decorator_with_protocol_version(fake_uploader):
    """instrument_mcp 带 protocol_version 时应出现在 attributes 中"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument_mcp(server="slack", tool="send_message", protocol_version="2024-11-05")
    def send_msg(channel: str, text: str) -> bool:
        return True

    root = TraceContext(name="root")
    set_current_context(root)

    send_msg("#general", "hello")
    await asyncio.sleep(0.05)

    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    attrs = spans[0]["attributes"]
    assert attrs["mcp_protocol_version"] == "2024-11-05"

    clear_current_context()


@pytest.mark.asyncio
async def test_mcp_decorator_default_tool_name(fake_uploader):
    """instrument_mcp 未指定 tool 时应使用函数名"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument_mcp(server="filesystem")
    def read_file(path: str) -> str:
        return "content"

    root = TraceContext(name="root")
    set_current_context(root)

    read_file("/tmp/test.txt")
    await asyncio.sleep(0.05)

    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    attrs = spans[0]["attributes"]
    assert attrs["mcp_tool"] == "read_file"
    assert spans[0]["tool_name"] == "read_file"

    clear_current_context()


@pytest.mark.asyncio
async def test_mcp_decorator_async(fake_uploader):
    """instrument_mcp 应支持异步函数"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument_mcp(server="database", tool="query")
    async def db_query(sql: str) -> list:
        return [{"id": 1}]

    root = TraceContext(name="root")
    set_current_context(root)

    result = await db_query("SELECT 1")
    await asyncio.sleep(0.05)

    assert result == [{"id": 1}]
    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert len(spans) == 1
    assert spans[0]["attributes"]["tool_subtype"] == "mcp"
    assert spans[0]["attributes"]["mcp_server"] == "database"

    clear_current_context()


@pytest.mark.asyncio
async def test_mcp_decorator_error(fake_uploader):
    """instrument_mcp 异常时应记录 error 并保留 mcp attributes"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument_mcp(server="github", tool="delete_repo")
    def delete_repo(name: str):
        raise PermissionError("not allowed")

    root = TraceContext(name="root")
    set_current_context(root)

    with pytest.raises(PermissionError):
        delete_repo("my-repo")
    await asyncio.sleep(0.05)

    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert len(spans) == 1
    assert spans[0]["status"] == "error"
    assert "not allowed" in spans[0]["error"]
    assert spans[0]["attributes"]["tool_subtype"] == "mcp"
    assert spans[0]["attributes"]["mcp_server"] == "github"

    clear_current_context()


# ======================================================================
# instrument_rag() 测试
# ======================================================================


@pytest.mark.asyncio
async def test_rag_decorator_basic(fake_uploader):
    """instrument_rag 应设置 rag 相关 attributes"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument_rag(vector_db="pinecone", top_k=5)
    def retrieve(query: str) -> list:
        return ["doc1", "doc2", "doc3"]

    root = TraceContext(name="root")
    set_current_context(root)

    result = retrieve("what is AI?")
    await asyncio.sleep(0.05)

    assert result == ["doc1", "doc2", "doc3"]
    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert len(spans) == 1
    attrs = spans[0]["attributes"]
    assert attrs["tool_subtype"] == "rag"
    assert attrs["rag_vector_db"] == "pinecone"
    assert attrs["rag_top_k"] == 5
    assert attrs["rag_recall_count"] == 3
    assert spans[0]["tool_type"] == "rag"
    assert spans[0]["status"] == "success"

    clear_current_context()


@pytest.mark.asyncio
async def test_rag_decorator_recall_count_from_dict(fake_uploader):
    """instrument_rag 应从 dict 返回值中提取 recall_count"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument_rag(vector_db="chroma", top_k=10)
    def retrieve(query: str) -> dict:
        return {"documents": ["d1", "d2"], "scores": [0.9, 0.8]}

    root = TraceContext(name="root")
    set_current_context(root)

    retrieve("test")
    await asyncio.sleep(0.05)

    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    attrs = spans[0]["attributes"]
    assert attrs["rag_recall_count"] == 2

    clear_current_context()


@pytest.mark.asyncio
async def test_rag_decorator_recall_count_from_dict_hits(fake_uploader):
    """instrument_rag 应识别 hits key"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument_rag(vector_db="elasticsearch")
    def search(query: str) -> dict:
        return {"hits": [{"id": 1}, {"id": 2}, {"id": 3}]}

    root = TraceContext(name="root")
    set_current_context(root)

    search("test")
    await asyncio.sleep(0.05)

    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert spans[0]["attributes"]["rag_recall_count"] == 3

    clear_current_context()


@pytest.mark.asyncio
async def test_rag_decorator_recall_count_from_dict_count_field(fake_uploader):
    """instrument_rag 应识别 count 字段"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument_rag(vector_db="milvus")
    def search(query: str) -> dict:
        return {"count": 7, "data": [...]}

    root = TraceContext(name="root")
    set_current_context(root)

    search("test")
    await asyncio.sleep(0.05)

    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert spans[0]["attributes"]["rag_recall_count"] == 7

    clear_current_context()


@pytest.mark.asyncio
async def test_rag_decorator_no_recall_count_for_unrecognized(fake_uploader):
    """返回值格式无法识别时不应设置 rag_recall_count"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument_rag(vector_db="faiss")
    def retrieve(query: str) -> dict:
        return {"embedding": [0.1, 0.2]}

    root = TraceContext(name="root")
    set_current_context(root)

    retrieve("test")
    await asyncio.sleep(0.05)

    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    attrs = spans[0]["attributes"]
    assert attrs["tool_subtype"] == "rag"
    assert attrs["rag_vector_db"] == "faiss"
    assert "rag_recall_count" not in attrs

    clear_current_context()


@pytest.mark.asyncio
async def test_rag_decorator_async(fake_uploader):
    """instrument_rag 应支持异步函数"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument_rag(vector_db="weaviate", top_k=3)
    async def async_retrieve(query: str) -> list:
        return ["doc1"]

    root = TraceContext(name="root")
    set_current_context(root)

    result = await async_retrieve("test")
    await asyncio.sleep(0.05)

    assert result == ["doc1"]
    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert len(spans) == 1
    assert spans[0]["attributes"]["tool_subtype"] == "rag"
    assert spans[0]["attributes"]["rag_recall_count"] == 1

    clear_current_context()


@pytest.mark.asyncio
async def test_rag_decorator_error(fake_uploader):
    """instrument_rag 异常时应记录 error 并保留 rag attributes"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument_rag(vector_db="pinecone", top_k=5)
    def retrieve(query: str):
        raise ConnectionError("db unreachable")

    root = TraceContext(name="root")
    set_current_context(root)

    with pytest.raises(ConnectionError):
        retrieve("test")
    await asyncio.sleep(0.05)

    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert len(spans) == 1
    assert spans[0]["status"] == "error"
    assert "db unreachable" in spans[0]["error"]
    assert spans[0]["attributes"]["tool_subtype"] == "rag"
    assert spans[0]["attributes"]["rag_vector_db"] == "pinecone"

    clear_current_context()


@pytest.mark.asyncio
async def test_rag_decorator_default_name(fake_uploader):
    """instrument_rag 未指定 name 时应使用函数名"""
    tool_sdk = ToolSDK(fake_uploader)

    @tool_sdk.instrument_rag(vector_db="qdrant")
    def my_retriever(query: str) -> list:
        return ["a"]

    root = TraceContext(name="root")
    set_current_context(root)

    my_retriever("test")
    await asyncio.sleep(0.05)

    spans = [s for s in fake_uploader.spans if s["span_type"] == "tool_call"]
    assert spans[0]["tool_name"] == "my_retriever"

    clear_current_context()
