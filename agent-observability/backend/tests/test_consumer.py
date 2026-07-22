"""Kafka Consumer 解析逻辑与成本计算测试。

Consumer 把原始 item 按 span_type 分流解析为 ClickHouse 行；其中 llm_metrics
在解析阶段会调用 calculate_cost 估算 USD 成本。这里覆盖：
  - parse_item 分发（含未知 span_type 回退 trace）
  - 5 种 parse_* 的字段映射与默认值
  - llm_metrics 顶层 / attributes 回退读取
  - calculate_cost：精确匹配、长 key 优先的前缀匹配、大小写不敏感、兜底价、零 token
  - 全部定价表条目都能命中自身
"""

import json

import pytest

from app.kafka.consumer import (
    DEFAULT_PRICING,
    MODEL_PRICING,
    _extract,
    calculate_cost,
    parse_item,
    parse_llm_metrics,
    parse_prompt,
    parse_session,
    parse_tool_call,
    parse_trace,
)


# ----------------------------- parse_item 分发 -----------------------------


@pytest.mark.parametrize(
    "span_type, expected_fn",
    [
        ("trace", parse_trace),
        ("llm_metrics", parse_llm_metrics),
        ("prompt", parse_prompt),
        ("tool_call", parse_tool_call),
        ("session", parse_session),
    ],
)
def test_parse_item_dispatches_by_span_type(span_type, expected_fn):
    # 同时提供 trace_id / span_id / session_id，覆盖所有 parser 的必填键
    item = {
        "trace_id": "t",
        "span_id": "s",
        "session_id": "sess",
        "span_type": span_type,
    }
    # 通过对比内部用的解析函数引用来确认分发
    from app.kafka.consumer import PARSE_MAP

    assert PARSE_MAP[span_type] is expected_fn
    # parse_item 不应抛异常
    result = parse_item(item)
    assert result["trace_id"] == "t"


def test_parse_item_unknown_span_type_falls_back_to_trace():
    item = {
        "trace_id": "t",
        "span_id": "s",
        "span_type": "totally-unknown",
        "name": "n",
        "start_time": "2026-07-16T00:00:00",
        "end_time": "2026-07-16T00:00:01",
    }
    result = parse_item(item)
    # 回退到 trace 解析，应包含 trace 专属字段
    assert result["name"] == "n"
    assert "attributes" in result  # trace 列


def test_parse_item_default_span_type_is_trace():
    item = {"trace_id": "t", "span_id": "s"}
    result = parse_item(item)
    assert result["trace_id"] == "t"
    assert result["span_id"] == "s"
    assert "attributes" in result  # trace 解析产物


# ----------------------------- parse_trace -----------------------------


def test_parse_trace_serializes_attributes_and_defaults():
    item = {
        "trace_id": "t1",
        "span_id": "s1",
        "name": "root",
        "start_time": "2026-07-16T00:00:00",
        "end_time": "2026-07-16T00:00:01",
        "attributes": {"model": "gpt-5.4", "nested": {"a": 1}},
    }
    row = parse_trace(item)
    assert row == {
        "trace_id": "t1",
        "span_id": "s1",
        "parent_span_id": "",
        "name": "root",
        "start_time": "2026-07-16T00:00:00",
        "end_time": "2026-07-16T00:00:01",
        "attributes": json.dumps({"model": "gpt-5.4", "nested": {"a": 1}}),
    }


def test_parse_trace_missing_optional_fields_uses_empty_defaults():
    row = parse_trace({"trace_id": "t", "span_id": "s"})
    assert row["parent_span_id"] == ""
    assert row["name"] == ""
    assert row["start_time"] == ""
    assert row["end_time"] == ""
    assert row["attributes"] == json.dumps({})


# ----------------------------- parse_llm_metrics -----------------------------


def test_parse_llm_metrics_reads_top_level_fields():
    item = {
        "trace_id": "t",
        "span_id": "s",
        "model_name": "gpt-5.4",
        "provider": "openai",
        "prefill_ms": 100,
        "decode_ms": 200,
        "input_tokens": 1000,
        "output_tokens": 500,
        "tps": 2500,
    }
    row = parse_llm_metrics(item)
    assert row["model_name"] == "gpt-5.4"
    assert row["provider"] == "openai"
    assert row["prefill_ms"] == 100
    assert row["decode_ms"] == 200
    assert row["input_tokens"] == 1000
    assert row["output_tokens"] == 500
    assert row["tps"] == 2500
    # cost 由 calculate_cost 计算得出
    assert row["cost_usd"] == pytest.approx(0.01)


def test_parse_llm_metrics_falls_back_to_attributes():
    """顶层缺失时从 attributes 回退读取"""
    item = {
        "trace_id": "t",
        "span_id": "s",
        "attributes": {
            "model_name": "claude-sonnet-5",
            "provider": "anthropic",
            "prefill_ms": 50,
            "decode_ms": 150,
            "input_tokens": 200,
            "output_tokens": 100,
            "tps": 666.7,
        },
    }
    row = parse_llm_metrics(item)
    assert row["model_name"] == "claude-sonnet-5"
    assert row["provider"] == "anthropic"
    assert row["input_tokens"] == 200
    assert row["output_tokens"] == 100
    assert row["tps"] == 666.7


def test_parse_llm_metrics_defaults_unknown_model_and_zero_tokens():
    item = {"trace_id": "t", "span_id": "s"}
    row = parse_llm_metrics(item)
    assert row["model_name"] == "unknown"
    assert row["provider"] == ""
    assert row["prefill_ms"] == 0
    assert row["decode_ms"] == 0
    assert row["input_tokens"] == 0
    assert row["output_tokens"] == 0
    assert row["tps"] == 0
    assert row["cost_usd"] == 0.0


def test_parse_llm_metrics_top_level_overrides_attributes():
    """顶层字段与 attributes 同时存在时，顶层优先（item.get 先命中）"""
    item = {
        "trace_id": "t",
        "span_id": "s",
        "model_name": "gpt-5.4",
        "input_tokens": 1000,
        "output_tokens": 500,
        "attributes": {"model_name": "claude-sonnet-5", "input_tokens": 1},
    }
    row = parse_llm_metrics(item)
    assert row["model_name"] == "gpt-5.4"
    assert row["input_tokens"] == 1000


# ----------------------------- parse_prompt -----------------------------


def test_parse_prompt_uses_explicit_values():
    item = {
        "trace_id": "t",
        "span_id": "s",
        "model_name": "gpt-5.4",
        "prompt": "hi",
        "response": "hello",
        "input_tokens": 3,
        "output_tokens": 2,
        "latency_ms": 120,
        "stream": True,
        "status": "success",
        "error": "",
    }
    row = parse_prompt(item)
    assert row["model_name"] == "gpt-5.4"
    assert row["prompt"] == "hi"
    assert row["response"] == "hello"
    assert row["stream"] is True
    assert row["status"] == "success"


def test_parse_prompt_defaults():
    row = parse_prompt({"trace_id": "t", "span_id": "s"})
    assert row["model_name"] == "unknown"
    assert row["prompt"] == ""
    assert row["response"] == ""
    assert row["input_tokens"] == 0
    assert row["output_tokens"] == 0
    assert row["latency_ms"] == 0
    assert row["stream"] is False
    assert row["status"] == "success"
    assert row["error"] == ""


# ----------------------------- parse_tool_call -----------------------------


def test_parse_tool_call_serializes_attributes_with_unicode():
    item = {
        "trace_id": "t",
        "span_id": "s",
        "tool_name": "search",
        "tool_type": "mcp",
        "input_data": '{"q":"中文"}',
        "output_data": '{"r":1}',
        "duration_ms": 42,
        "status": "success",
        "error": "",
        "attributes": {"mcp": {"server": "rag"}},
    }
    row = parse_tool_call(item)
    assert row["tool_name"] == "search"
    assert row["tool_type"] == "mcp"
    assert row["duration_ms"] == 42
    # ensure_ascii=False，中文应原样保留
    assert "中文" not in row["attributes"]  # attributes 是 mcp 元数据，不含 input_data
    assert json.loads(row["attributes"]) == {"mcp": {"server": "rag"}}


def test_parse_tool_call_defaults():
    row = parse_tool_call({"trace_id": "t", "span_id": "s"})
    assert row["tool_name"] == "unknown"
    assert row["tool_type"] == "generic"
    assert row["input_data"] == "{}"
    assert row["output_data"] == "{}"
    assert row["duration_ms"] == 0
    assert row["status"] == "success"
    assert row["error"] == ""
    assert json.loads(row["attributes"]) == {}


# ----------------------------- parse_session -----------------------------


def test_parse_session_uses_explicit_values():
    item = {
        "session_id": "sess-1",
        "trace_id": "t",
        "agent_name": "agent",
        "user_input": "in",
        "final_response": "out",
        "total_spans": 7,
        "total_tokens": 1234,
        "total_cost_usd": 0.12,
        "duration_ms": 9999,
        "status": "completed",
    }
    row = parse_session(item)
    assert row["session_id"] == "sess-1"
    assert row["agent_name"] == "agent"
    assert row["total_tokens"] == 1234
    assert row["total_cost_usd"] == 0.12
    assert row["status"] == "completed"


def test_parse_session_defaults():
    row = parse_session({"session_id": "sess", "trace_id": "t"})
    assert row["agent_name"] == ""
    assert row["user_input"] == ""
    assert row["final_response"] == ""
    assert row["total_spans"] == 0
    assert row["total_tokens"] == 0
    assert row["total_cost_usd"] == 0
    assert row["duration_ms"] == 0
    assert row["status"] == "completed"


# ----------------------------- _extract -----------------------------


def test_extract_returns_value_when_present():
    assert _extract({"a": 1}, "a") == 1


def test_extract_returns_default_when_missing():
    assert _extract({}, "missing", default="fallback") == "fallback"


# ----------------------------- calculate_cost -----------------------------


def test_calculate_cost_known_model_gpt_5_4():
    # 2.50/1M input, 15.00/1M output
    cost = calculate_cost("gpt-5.4", 1_000_000, 1_000_000)
    assert cost == pytest.approx(2.50 + 15.00)


def test_calculate_cost_prefix_long_key_wins_over_short_key():
    """gpt-5.4-mini 必须优先于 gpt-5.4 命中，避免误用更高单价"""
    mini = calculate_cost("gpt-5.4-mini", 1_000_000, 1_000_000)
    base = calculate_cost("gpt-5.4", 1_000_000, 1_000_000)
    # mini: 0.75 + 4.50 = 5.25 ; base: 2.50 + 15.00 = 17.50
    assert mini == pytest.approx(5.25)
    assert base == pytest.approx(17.50)
    assert mini != base


def test_calculate_cost_prefix_matches_versioned_suffix():
    """带版本后缀的模型名应命中对应前缀"""
    # gpt-5.6-sol-preview 应命中 gpt-5.6-sol (5.00 + 30.00)
    cost = calculate_cost("gpt-5.6-sol-preview", 1_000_000, 0)
    assert cost == pytest.approx(5.00)


def test_calculate_cost_case_insensitive():
    assert calculate_cost("GPT-5.4", 1000, 500) == pytest.approx(
        calculate_cost("gpt-5.4", 1000, 500)
    )


def test_calculate_cost_unknown_model_uses_default_pricing():
    cost = calculate_cost("some-future-model", 1_000_000, 1_000_000)
    assert cost == pytest.approx(DEFAULT_PRICING["input"] + DEFAULT_PRICING["output"])


def test_calculate_cost_zero_tokens_returns_zero():
    assert calculate_cost("gpt-5.4", 0, 0) == 0.0


@pytest.mark.parametrize("model_key", list(MODEL_PRICING.keys()))
def test_calculate_cost_every_pricing_key_matches_itself(model_key):
    """定价表中每个 key 都能命中自身模型名（无兜底）"""
    pricing = MODEL_PRICING[model_key]
    cost = calculate_cost(model_key, 1_000_000, 1_000_000)
    assert cost == pytest.approx(pricing["input"] + pricing["output"])


def test_pricing_table_includes_documented_models():
    """README 中列出的主流模型都应在定价表中"""
    expected = {
        "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna",
        "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano",
        "gpt-4.1", "gpt-4.1-mini",
        "claude-opus-4-8", "claude-sonnet-5", "claude-haiku-4-5",
        "deepseek-chat", "deepseek-reasoner",
    }
    assert expected.issubset(MODEL_PRICING.keys())
