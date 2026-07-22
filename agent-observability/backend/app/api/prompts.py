"""
Prompt Replay API - 查看 Prompt/Response/Tool 调用记录
"""

import logging
from typing import Optional

from fastapi import APIRouter, Query

from ..clickhouse.client import query_prompts, query_tool_calls

logger = logging.getLogger(__name__)

router = APIRouter()


@router.get("/prompts")
async def get_prompts(
    trace_id: Optional[str] = Query(None, description="指定 trace_id 查询"),
    limit: int = Query(100, ge=1, le=1000, description="返回数量限制"),
):
    """
    查询 Prompt 日志

    - 不传 trace_id: 返回最近的 Prompt 列表
    - 传 trace_id: 返回该链路的完整 Prompt/Response 记录
    """
    try:
        prompts = await query_prompts(trace_id=trace_id, limit=limit)
        return {
            "status": "success",
            "count": len(prompts),
            "data": prompts,
        }
    except Exception as e:
        logger.error(f"Failed to query prompts: {e}")
        return {
            "status": "error",
            "message": str(e),
            "data": [],
        }


@router.get("/tool-calls")
async def get_tool_calls(
    trace_id: Optional[str] = Query(None, description="指定 trace_id 查询"),
    limit: int = Query(100, ge=1, le=1000, description="返回数量限制"),
):
    """
    查询 Tool 调用记录

    - 不传 trace_id: 返回最近的 Tool 调用列表
    - 传 trace_id: 返回该链路的完整 Tool 调用记录
    """
    try:
        tool_calls = await query_tool_calls(trace_id=trace_id, limit=limit)
        return {
            "status": "success",
            "count": len(tool_calls),
            "data": tool_calls,
        }
    except Exception as e:
        logger.error(f"Failed to query tool calls: {e}")
        return {
            "status": "error",
            "message": str(e),
            "data": [],
        }
