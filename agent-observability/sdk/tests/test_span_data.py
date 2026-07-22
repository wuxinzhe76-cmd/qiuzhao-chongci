"""
SpanData.to_dict() 序列化单元测试

验证各 span_type 的字段映射是否正确。
"""

from agent_insight_sdk.uploader import SpanData


def test_trace_span_dict():
    span = SpanData(
        trace_id="t1",
        span_id="s1",
        parent_span_id="p1",
        name="root",
        start_time="2026-01-01T00:00:00",
        end_time="2026-01-01T00:00:01",
        span_type="trace",
        attributes={"key": "value"},
    )
    d = span.to_dict()

    assert d["trace_id"] == "t1"
    assert d["span_id"] == "s1"
    assert d["parent_span_id"] == "p1"
    assert d["name"] == "root"
    assert d["span_type"] == "trace"
    assert d["attributes"] == {"key": "value"}
    # trace 类型不应包含 tool/session/prompt 专属字段
    assert "tool_name" not in d
    assert "session_id" not in d
    assert "prompt" not in d


def test_prompt_span_dict():
    span = SpanData(
        trace_id="t1",
        span_id="s1",
        name="prompt_log",
        start_time="2026-01-01T00:00:00",
        end_time="2026-01-01T00:00:01",
        span_type="prompt",
        model_name="gpt-5.4",
        prompt="hi",
        response="hello",
        input_tokens=10,
        output_tokens=20,
        latency_ms=100.0,
        stream=False,
        status="success",
        error="",
    )
    d = span.to_dict()

    assert d["model_name"] == "gpt-5.4"
    assert d["prompt"] == "hi"
    assert d["response"] == "hello"
    assert d["input_tokens"] == 10
    assert d["output_tokens"] == 20
    assert d["latency_ms"] == 100.0
    assert d["stream"] is False
    assert d["status"] == "success"
    assert "tool_name" not in d
    assert "session_id" not in d


def test_tool_call_span_dict():
    span = SpanData(
        trace_id="t1",
        span_id="s1",
        name="tool:calc",
        start_time="2026-01-01T00:00:00",
        end_time="2026-01-01T00:00:01",
        span_type="tool_call",
        tool_name="calc",
        tool_type="math",
        input_data='{"args": []}',
        output_data="42",
        duration_ms=5.0,
        status="success",
        error="",
    )
    d = span.to_dict()

    assert d["tool_name"] == "calc"
    assert d["tool_type"] == "math"
    assert d["input_data"] == '{"args": []}'
    assert d["output_data"] == "42"
    assert d["duration_ms"] == 5.0
    assert d["status"] == "success"
    assert "prompt" not in d
    assert "session_id" not in d


def test_session_span_dict():
    span = SpanData(
        trace_id="t1",
        span_id="t1",
        name="session",
        start_time="2026-01-01T00:00:00",
        end_time="2026-01-01T00:00:01",
        span_type="session",
        session_id="t1",
        agent_name="agent",
        user_input="hi",
        final_response="bye",
        total_spans=3,
        total_tokens=100,
        total_cost_usd=0.001,
        duration_ms=1000.0,
        status="completed",
    )
    d = span.to_dict()

    assert d["session_id"] == "t1"
    assert d["agent_name"] == "agent"
    assert d["user_input"] == "hi"
    assert d["final_response"] == "bye"
    assert d["total_spans"] == 3
    assert d["total_tokens"] == 100
    assert d["total_cost_usd"] == 0.001
    assert d["duration_ms"] == 1000.0
    assert d["status"] == "completed"
    assert "tool_name" not in d
    assert "prompt" not in d


def test_span_data_attributes_default_to_dict():
    """attributes 默认 None 时 __post_init__ 应初始化为 {}"""
    span = SpanData(
        trace_id="t1",
        span_id="s1",
        span_type="trace",
    )
    assert span.attributes == {}
    assert span.to_dict()["attributes"] == {}


def test_span_data_parent_span_id_empty_when_none():
    """parent_span_id 为 None 时 to_dict 返回空字符串"""
    span = SpanData(
        trace_id="t1",
        span_id="s1",
        parent_span_id=None,
        span_type="trace",
    )
    assert span.to_dict()["parent_span_id"] == ""
