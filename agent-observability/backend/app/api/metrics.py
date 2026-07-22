"""
指标查询 API
"""

import logging
from typing import List, Optional

from fastapi import APIRouter, Query

from ..clickhouse.client import query_metrics_compare

logger = logging.getLogger(__name__)

router = APIRouter()


@router.get("/metrics/compare")
async def compare_metrics(
    models: Optional[str] = Query(
        None,
        description="模型名称列表，逗号分隔，如: gpt-5.4,claude-opus-4-8",
    ),
    hours: int = Query(24, ge=1, le=720, description="查询时间范围（小时）"),
):
    """
    多模型效能对比数据

    返回各模型的平均 Prefill 延迟、Decode 速度 (TPS)、Token 消耗和成本
    """
    model_names = None
    if models:
        model_names = [m.strip() for m in models.split(",")]

    try:
        metrics = await query_metrics_compare(model_names=model_names, hours=hours)
        return {
            "status": "success",
            "count": len(metrics),
            "data": metrics,
        }
    except Exception as e:
        logger.error(f"Failed to query metrics: {e}")
        return {
            "status": "error",
            "message": str(e),
            "data": [],
        }
