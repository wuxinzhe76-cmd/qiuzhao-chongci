"""
数据收集 API - 接收 SDK 上报的数据

支持的数据类型：
- trace: 链路追踪数据
- llm_metrics: LLM 性能指标
- prompt: Prompt/Response 记录
- tool_call: Tool 调用记录
- session: Session 会话记录
"""

import logging
from typing import Any, Dict, List

from fastapi import APIRouter, HTTPException, status

from ..kafka.producer import send_batch

logger = logging.getLogger(__name__)

router = APIRouter()

# 必填字段定义
REQUIRED_FIELDS = {
    "trace": ["trace_id", "span_id", "name", "start_time", "end_time"],
    "llm_metrics": ["trace_id", "span_id"],
    "prompt": ["trace_id", "span_id"],
    "tool_call": ["trace_id", "span_id"],
    "session": ["session_id", "trace_id"],
}


def validate_item(item: Dict[str, Any]) -> None:
    """校验单条数据"""
    if not isinstance(item, dict):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Each item must be a JSON object",
        )

    span_type = item.get("span_type", "trace")

    # 根据类型检查必填字段
    required = REQUIRED_FIELDS.get(span_type, REQUIRED_FIELDS["trace"])
    missing = [f for f in required if f not in item]
    if missing:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"Missing required fields for type '{span_type}': {missing}",
        )


@router.post("/collect", status_code=status.HTTP_202_ACCEPTED)
async def collect_data(data: List[Dict[str, Any]]):
    """
    接收 SDK 上报的链路和指标数据

    该接口不做任何数据库写操作，直接将数据投递到 Kafka 后立即返回 202 Accepted

    请求体示例：
    ```json
    [
      {
        "trace_id": "uuid",
        "span_id": "uuid",
        "parent_span_id": "",
        "name": "llm_call",
        "start_time": "2026-06-28T10:00:00",
        "end_time": "2026-06-28T10:00:02",
        "span_type": "trace",
        "attributes": {"model": "gpt-5.4"}
      },
      {
        "trace_id": "uuid",
        "span_id": "uuid",
        "span_type": "llm_metrics",
        "attributes": {
          "model_name": "gpt-5.4",
          "prefill_ms": 500,
          "decode_ms": 2000,
          "input_tokens": 1500,
          "output_tokens": 800,
          "tps": 400
        }
      },
      {
        "trace_id": "uuid",
        "span_id": "uuid",
        "span_type": "prompt",
        "model_name": "gpt-5.4",
        "prompt": "...",
        "response": "...",
        "input_tokens": 1500,
        "output_tokens": 800,
        "latency_ms": 2500,
        "stream": false,
        "status": "success"
      },
      {
        "trace_id": "uuid",
        "span_id": "uuid",
        "span_type": "tool_call",
        "tool_name": "calculator",
        "tool_type": "calculator",
        "input_data": "...",
        "output_data": "...",
        "duration_ms": 120,
        "status": "success"
      },
      {
        "session_id": "session-uuid",
        "trace_id": "trace-uuid",
        "span_type": "session",
        "agent_name": "my-agent",
        "user_input": "hello",
        "final_response": "hi",
        "total_spans": 5,
        "total_tokens": 2300,
        "total_cost_usd": 0.05,
        "duration_ms": 3000,
        "status": "completed"
      }
    ]
    ```
    """
    if not data:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Empty data array",
        )

    if not isinstance(data, list):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Request body must be a JSON array",
        )

    # 逐条校验
    for i, item in enumerate(data):
        try:
            validate_item(item)
        except HTTPException:
            raise
        except Exception as e:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Invalid item at index {i}: {str(e)}",
            )

    try:
        await send_batch(data)
        return {
            "status": "accepted",
            "count": len(data),
            "message": "Data queued for processing",
        }
    except Exception as e:
        logger.error(f"Failed to queue data: {e}")
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail=f"Failed to queue data: {str(e)}",
        )
