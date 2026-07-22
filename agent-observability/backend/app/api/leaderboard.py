"""
排行榜 API - 最慢 Tool / Token 消耗 / 失败次数 排行榜
"""

import logging

from fastapi import APIRouter, Query

from ..clickhouse.client import query_leaderboard

logger = logging.getLogger(__name__)

router = APIRouter()


@router.get("/leaderboard")
async def get_leaderboard(
    metric: str = Query(
        "slowest_tool",
        description="排行指标: slowest_tool | most_tokens | most_failed",
    ),
    limit: int = Query(10, ge=1, le=50, description="返回条数"),
):
    """
    查询排行榜

    - slowest_tool: 最慢 Tool 调用排行榜（按平均耗时降序）
    - most_tokens: Token 消耗排行榜（按总 Token 数降序）
    - most_failed: 失败次数排行榜（按错误次数降序）
    """
    try:
        data = await query_leaderboard(metric=metric, limit=limit)
        return {
            "status": "success",
            "metric": metric,
            "count": len(data),
            "data": data,
        }
    except Exception as e:
        logger.error(f"Failed to query leaderboard: {e}")
        return {
            "status": "error",
            "message": str(e),
            "data": [],
        }
