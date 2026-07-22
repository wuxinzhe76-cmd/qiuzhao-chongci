"""
Kafka 消费者 - 消费原始日志并写入 ClickHouse

支持的数据类型：trace / llm_metrics / prompt / tool_call / session
"""

import asyncio
import json
import logging
from typing import Any, Dict, List

from aiokafka import AIOKafkaConsumer

from ..config import settings
from ..clickhouse.client import (
    insert_traces,
    insert_metrics,
    insert_prompts,
    insert_tool_calls,
    insert_sessions,
    _retry_insert,
)

logger = logging.getLogger(__name__)

_consumer: AIOKafkaConsumer = None
_consumer_task: asyncio.Task = None

BATCH_SIZE = 50

# ---- 消费者生命周期 ----


async def start_consumer() -> None:
    """启动 Kafka 消费者"""
    global _consumer, _consumer_task

    try:
        _consumer = AIOKafkaConsumer(
            settings.kafka_topic,
            bootstrap_servers=settings.kafka_bootstrap_servers,
            group_id=settings.kafka_group_id,
            auto_offset_reset="earliest",
            enable_auto_commit=True,
            value_deserializer=lambda m: json.loads(m.decode("utf-8")),
            max_poll_records=100,
        )
        await _consumer.start()
        logger.info(f"Kafka consumer started, topic: {settings.kafka_topic}")
        _consumer_task = asyncio.create_task(consume_loop())
    except Exception as e:
        logger.error(f"Failed to start Kafka consumer: {e}")
        raise


async def stop_consumer() -> None:
    """停止 Kafka 消费者"""
    global _consumer, _consumer_task

    if _consumer_task:
        _consumer_task.cancel()
        try:
            await _consumer_task
        except asyncio.CancelledError:
            pass

    if _consumer:
        await _consumer.stop()
        _consumer = None
        logger.info("Kafka consumer stopped")


# ---- 消息消费主循环 ----


async def consume_loop() -> None:
    """消费循环：按数据类型分流到 ClickHouse 对应表"""
    batches: Dict[str, List[Dict[str, Any]]] = {
        "trace": [],
        "metrics": [],
        "prompt": [],
        "tool_call": [],
        "session": [],
    }

    flush_map = {
        "trace": (insert_traces, flush_traces),
        "metrics": (insert_metrics, flush_metrics),
        "prompt": (insert_prompts, flush_prompts),
        "tool_call": (insert_tool_calls, flush_tool_calls),
        "session": (insert_sessions, flush_sessions),
    }

    FLUSH_INTERVAL = 5.0  # poll 超时：唤醒循环重新检查批量阈值（本身不强制刷新）

    try:
        while True:
            # 带超时的 poll — 超时仅唤醒循环，刷新仍只看批量阈值
            try:
                msg = await asyncio.wait_for(
                    _consumer.getone(),
                    timeout=FLUSH_INTERVAL,
                )
                data = msg.value
                items = data if isinstance(data, list) else [data]

                for item in items:
                    span_type = item.get("span_type", "trace")
                    parsed = parse_item(item)

                    if span_type == "llm_metrics":
                        batches["metrics"].append(parsed)
                    elif span_type == "prompt":
                        batches["prompt"].append(parsed)
                    elif span_type == "tool_call":
                        batches["tool_call"].append(parsed)
                    elif span_type == "session":
                        batches["session"].append(parsed)
                    else:
                        batches["trace"].append(parsed)

            except asyncio.TimeoutError:
                pass  # 超时仅唤醒循环，下方仍按批量阈值判断是否刷新

            # 批量刷新：仅当达到 BATCH_SIZE 阈值时触发；未达阈值的残留数据在 finally 中关闭时刷新
            for key, batch in batches.items():
                if len(batch) >= BATCH_SIZE:
                    await flush_map[key][1](batch)
                    batches[key] = []

    except asyncio.CancelledError:
        logger.info("Consumer loop cancelled")
    except Exception as e:
        logger.error(f"Error in consumer loop: {e}")
        raise
    finally:
        # 关闭前刷新所有残留数据
        for key, batch in batches.items():
            if batch:
                await flush_map[key][1](batch)
                logger.info(f"Flushed remaining {len(batch)} {key} on shutdown")


# ---- 数据解析 ----


def parse_item(item: Dict[str, Any]) -> Dict[str, Any]:
    """将原始 item 解析为目标表字段"""
    span_type = item.get("span_type", "trace")
    parse_fn = PARSE_MAP.get(span_type, parse_trace)
    return parse_fn(item)


def parse_trace(item: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "trace_id": item["trace_id"],
        "span_id": item["span_id"],
        "parent_span_id": item.get("parent_span_id", ""),
        "name": item.get("name", ""),
        "start_time": item.get("start_time", ""),
        "end_time": item.get("end_time", ""),
        "attributes": json.dumps(item.get("attributes", {})),
    }


def _extract(attrs: dict, key: str, default: Any = 0) -> Any:
    """从 attrs 字典读取 key，不存在则返回 default"""
    return attrs.get(key, default)


def parse_llm_metrics(item: Dict[str, Any]) -> Dict[str, Any]:
    attrs = item.get("attributes", {})
    model = item.get("model_name") or attrs.get("model_name", "unknown")
    provider = item.get("provider") or attrs.get("provider", "")
    input_tokens = item.get("input_tokens") or attrs.get("input_tokens", 0)
    output_tokens = item.get("output_tokens") or attrs.get("output_tokens", 0)
    return {
        "trace_id": item["trace_id"],
        "span_id": item["span_id"],
        "model_name": model,
        "provider": provider,
        "prefill_ms": item.get("prefill_ms") or attrs.get("prefill_ms", 0),
        "decode_ms": item.get("decode_ms") or attrs.get("decode_ms", 0),
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "tps": item.get("tps") or attrs.get("tps", 0),
        "cost_usd": calculate_cost(model, input_tokens, output_tokens),
    }


def parse_prompt(item: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "trace_id": item["trace_id"],
        "span_id": item["span_id"],
        "model_name": item.get("model_name", "unknown"),
        "prompt": item.get("prompt", ""),
        "response": item.get("response", ""),
        "input_tokens": item.get("input_tokens", 0),
        "output_tokens": item.get("output_tokens", 0),
        "latency_ms": item.get("latency_ms", 0),
        "stream": item.get("stream", False),
        "status": item.get("status", "success"),
        "error": item.get("error", ""),
    }


def parse_tool_call(item: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "trace_id": item["trace_id"],
        "span_id": item["span_id"],
        "tool_name": item.get("tool_name", "unknown"),
        "tool_type": item.get("tool_type", "generic"),
        "input_data": item.get("input_data", "{}"),
        "output_data": item.get("output_data", "{}"),
        "duration_ms": item.get("duration_ms", 0),
        "status": item.get("status", "success"),
        "error": item.get("error", ""),
        "attributes": json.dumps(item.get("attributes", {}), ensure_ascii=False),
    }


def parse_session(item: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "session_id": item["session_id"],
        "trace_id": item["trace_id"],
        "agent_name": item.get("agent_name", ""),
        "user_input": item.get("user_input", ""),
        "final_response": item.get("final_response", ""),
        "total_spans": item.get("total_spans", 0),
        "total_tokens": item.get("total_tokens", 0),
        "total_cost_usd": item.get("total_cost_usd", 0),
        "duration_ms": item.get("duration_ms", 0),
        "status": item.get("status", "completed"),
    }


PARSE_MAP = {
    "trace": parse_trace,
    "llm_metrics": parse_llm_metrics,
    "prompt": parse_prompt,
    "tool_call": parse_tool_call,
    "session": parse_session,
}


# ---- Token 成本计算 ----

# 模型单价表：USD / 1M tokens
# 与 SDK 端 agent_insight_sdk.session_sdk.DEFAULT_PRICING 保持一致，
# 修改任一处时请同步另一处。
MODEL_PRICING: Dict[str, Dict[str, float]] = {
    # OpenAI GPT-5.6 系列（2026-07 发布）
    "gpt-5.6-sol": {"input": 5.00, "output": 30.00},
    "gpt-5.6-terra": {"input": 2.50, "output": 15.00},
    "gpt-5.6-luna": {"input": 1.00, "output": 6.00},
    # OpenAI GPT-5.4 系列（主流生产）
    "gpt-5.4": {"input": 2.50, "output": 15.00},
    "gpt-5.4-mini": {"input": 0.75, "output": 4.50},
    "gpt-5.4-nano": {"input": 0.20, "output": 1.25},
    # OpenAI GPT-4.1 系列（1M 长上下文）
    "gpt-4.1": {"input": 2.00, "output": 8.00},
    "gpt-4.1-mini": {"input": 0.40, "output": 1.60},
    # Anthropic Claude（2026）
    "claude-opus-4-8": {"input": 5.00, "output": 25.00},
    "claude-sonnet-5": {"input": 3.00, "output": 15.00},
    "claude-haiku-4-5": {"input": 1.00, "output": 5.00},
    # DeepSeek
    "deepseek-chat": {"input": 0.14, "output": 0.28},
    "deepseek-reasoner": {"input": 0.55, "output": 2.19},
}

# 兜底单价：USD / 1M tokens
DEFAULT_PRICING = {"input": 1.00, "output": 2.00}


def calculate_cost(model_name: str, input_tokens: int, output_tokens: int) -> float:
    """计算 Token 成本（美元）

    匹配策略：按 key 长度降序精确匹配 model_name 的前缀，
    避免短 key（如 gpt-5.4）误匹配长 key（如 gpt-5.4-mini）。
    """
    name_lower = model_name.lower()
    price = None
    # 按 key 长度降序，优先匹配更具体的长名（gpt-5.4-mini 先于 gpt-5.4）
    for key in sorted(MODEL_PRICING, key=len, reverse=True):
        if name_lower.startswith(key):
            price = MODEL_PRICING[key]
            break
    if price is None:
        price = DEFAULT_PRICING

    input_cost = input_tokens * price["input"] / 1_000_000
    output_cost = output_tokens * price["output"] / 1_000_000
    return input_cost + output_cost


# ---- 批量写入 ----

async def flush_traces(batch: List[dict]) -> None:
    await _retry_insert(insert_traces, batch, "traces")


async def flush_metrics(batch: List[dict]) -> None:
    await _retry_insert(insert_metrics, batch, "metrics")


async def flush_prompts(batch: List[dict]) -> None:
    await _retry_insert(insert_prompts, batch, "prompts")


async def flush_tool_calls(batch: List[dict]) -> None:
    await _retry_insert(insert_tool_calls, batch, "tool_calls")


async def flush_sessions(batch: List[dict]) -> None:
    await _retry_insert(insert_sessions, batch, "sessions")
