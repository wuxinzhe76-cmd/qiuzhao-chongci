"""
ClickHouse 客户端 - 负责数据写入和查询

设计要点：
  - 同步 clickhouse-driver 通过 asyncio.to_thread 包装为非阻塞调用（3.10+）
  - 所有 SQL 均参数化，杜绝注入
  - 通过 _TableSpec 注册表描述表 schema，insert_/query_ 函数统一走通用路径，
    新增表只需在 _SPECS 中加一项，无需复制粘贴模板代码
"""

import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from clickhouse_driver import Client as SyncClient
from clickhouse_driver import errors as ch_errors

from ..config import settings

logger = logging.getLogger(__name__)

_client: Optional[SyncClient] = None


def get_client() -> SyncClient:
    """获取 ClickHouse 客户端（单例）"""
    global _client
    if _client is None:
        _client = SyncClient(
            host=settings.clickhouse_host,
            port=settings.clickhouse_port,
            database=settings.clickhouse_database,
            user=settings.clickhouse_user,
            password=settings.clickhouse_password,
        )
    return _client


# ---------------------------------------------------------------------------
# 表 Schema 注册表
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class TableSpec:
    """描述一张 ClickHouse 表的写入/查询 schema

    insert_columns:  写入时使用的列（顺序与 SQL VALUES 一致）
    insert_defaults: 字段缺失时的默认值；不在此 dict 中的列视为必填，
                     缺失会抛 KeyError（与原实现行为一致）
    query_columns:   查询返回的列（含物化字段如 duration_ms、created_at）
    time_column:     排序使用的时间列
    """
    name: str
    insert_columns: Tuple[str, ...]
    insert_defaults: Dict[str, Any] = field(default_factory=dict)
    query_columns: Tuple[str, ...] = ()
    time_column: str = "created_at"


_SPECS: Dict[str, TableSpec] = {
    "traces": TableSpec(
        name="agent_traces",
        insert_columns=(
            "trace_id", "span_id", "parent_span_id", "name",
            "start_time", "end_time", "attributes",
        ),
        # 全部必填，无默认值
        query_columns=(
            "trace_id", "span_id", "parent_span_id", "name",
            "start_time", "end_time", "duration_ms", "attributes", "created_at",
        ),
        time_column="start_time",
    ),
    "metrics": TableSpec(
        name="llm_metrics",
        insert_columns=(
            "trace_id", "span_id", "model_name", "provider",
            "prefill_ms", "decode_ms",
            "input_tokens", "output_tokens",
            "tps", "cost_usd",
        ),
        insert_defaults={
            "provider": "",
        },
        # metrics 走自定义聚合查询，不暴露简单 query_columns
    ),
    "prompts": TableSpec(
        name="prompt_logs",
        insert_columns=(
            "trace_id", "span_id", "model_name", "prompt", "response",
            "input_tokens", "output_tokens", "latency_ms",
            "stream", "status", "error",
        ),
        insert_defaults={
            "model_name": "unknown", "prompt": "", "response": "",
            "input_tokens": 0, "output_tokens": 0, "latency_ms": 0,
            "stream": False, "status": "success", "error": "",
        },
        query_columns=(
            "trace_id", "span_id", "model_name", "prompt", "response",
            "input_tokens", "output_tokens", "latency_ms",
            "stream", "status", "error", "created_at",
        ),
    ),
    "tool_calls": TableSpec(
        name="tool_calls",
        insert_columns=(
            "trace_id", "span_id", "tool_name", "tool_type",
            "input_data", "output_data", "duration_ms", "status", "error",
            "attributes",
        ),
        insert_defaults={
            "tool_name": "unknown", "tool_type": "generic",
            "input_data": "{}", "output_data": "{}",
            "duration_ms": 0, "status": "success", "error": "",
            "attributes": "{}",
        },
        query_columns=(
            "trace_id", "span_id", "tool_name", "tool_type",
            "input_data", "output_data", "duration_ms", "status", "error",
            "attributes", "created_at",
        ),
    ),
    "sessions": TableSpec(
        name="sessions",
        insert_columns=(
            "session_id", "trace_id", "agent_name",
            "user_input", "final_response",
            "total_spans", "total_tokens", "total_cost_usd",
            "duration_ms", "status",
        ),
        insert_defaults={
            "agent_name": "", "user_input": "", "final_response": "",
            "total_spans": 0, "total_tokens": 0, "total_cost_usd": 0,
            "duration_ms": 0, "status": "completed",
        },
        query_columns=(
            "session_id", "trace_id", "agent_name",
            "user_input", "final_response",
            "total_spans", "total_tokens", "total_cost_usd",
            "duration_ms", "status", "created_at",
        ),
    ),
}


# ---------------------------------------------------------------------------
# 通用执行器
# ---------------------------------------------------------------------------

async def _retry_insert(
    insert_fn, data: List[Dict[str, Any]], label: str, max_retries: int = 3
) -> None:
    """带指数退避重试的 ClickHouse 写入

    注意：insert_fn 必须是 async 函数（内部已通过 asyncio.to_thread 执行同步 IO），
    不能再放进 run_in_executor，否则 async 函数只会在线程里产生一个
    未被 await 的 coroutine，数据实际不会写入。
    """
    if not data:
        return

    last_exc = None
    for attempt in range(max_retries):
        try:
            await insert_fn(data)
            return
        except ch_errors.Error as e:
            last_exc = e
            if attempt < max_retries - 1:
                delay = 2 ** attempt
                logger.warning(
                    f"ClickHouse insert {label} attempt {attempt + 1} failed, "
                    f"retrying in {delay}s: {e}"
                )
                await asyncio.sleep(delay)

    logger.error(
        f"ClickHouse insert {label} failed after {max_retries} attempts, "
        f"discarding {len(data)} records: {last_exc}"
    )


def _build_insert_rows(spec: TableSpec, data: List[Dict[str, Any]]) -> List[tuple]:
    """根据 TableSpec 把 dict list 转成 tuple list（应用默认值，必填列缺失抛 KeyError）"""
    defaults = spec.insert_defaults
    cols = spec.insert_columns
    return [
        tuple(
            d.get(c, defaults[c]) if c in defaults else d[c]
            for c in cols
        )
        for d in data
    ]


async def _bulk_insert(spec_key: str, data: List[Dict[str, Any]]) -> None:
    """通用批量插入"""
    if not data:
        return

    spec = _SPECS[spec_key]
    columns_sql = ", ".join(spec.insert_columns)
    sql = f"INSERT INTO {spec.name} ({columns_sql}) VALUES"
    rows = _build_insert_rows(spec, data)

    def _insert():
        get_client().execute(sql, rows)

    try:
        await asyncio.to_thread(_insert)
    except ch_errors.Error as e:
        logger.error(f"ClickHouse insert {spec_key} error: {e}")
        raise


async def _select(
    sql: str,
    params: Optional[Dict[str, Any]],
    columns: Tuple[str, ...],
    error_label: str,
) -> List[Dict[str, Any]]:
    """通用查询执行器：在线程中执行同步查询，返回 dict list"""
    def _query():
        return get_client().execute(sql, params or {})

    try:
        result = await asyncio.to_thread(_query)
        return [dict(zip(columns, row)) for row in result]
    except ch_errors.Error as e:
        logger.error(f"ClickHouse query {error_label} error: {e}")
        return []


async def _select_by_filter_or_recent(
    spec_key: str,
    filter_column: str,
    filter_value: Optional[str],
    limit: int,
    error_label: str,
    asc_when_filtered: bool = True,
) -> List[Dict[str, Any]]:
    """统一的 '按某列过滤 / 最近 N 条' 查询模板

    覆盖 traces / prompts / tool_calls / sessions 四个简单查询：
      - 传 filter_value: WHERE filter_column = ? ORDER BY time_column [ASC|DESC]
      - 不传:            ORDER BY time_column DESC LIMIT ?
    """
    spec = _SPECS[spec_key]
    cols_sql = ", ".join(spec.query_columns)
    time_col = spec.time_column

    if filter_value:
        direction = "ASC" if asc_when_filtered else "DESC"
        sql = (
            f"SELECT {cols_sql} FROM {spec.name} "
            f"WHERE {filter_column} = %(fv)s "
            f"ORDER BY {time_col} {direction}"
        )
        return await _select(sql, {"fv": filter_value}, spec.query_columns, error_label)

    sql = (
        f"SELECT {cols_sql} FROM {spec.name} "
        f"ORDER BY {time_col} DESC LIMIT %(lim)s"
    )
    return await _select(sql, {"lim": limit}, spec.query_columns, error_label)


# ---------------------------------------------------------------------------
# 写入 API（保持原签名）
# ---------------------------------------------------------------------------

async def insert_traces(data: List[Dict[str, Any]]) -> None:
    await _bulk_insert("traces", data)


async def insert_metrics(data: List[Dict[str, Any]]) -> None:
    await _bulk_insert("metrics", data)


async def insert_prompts(data: List[Dict[str, Any]]) -> None:
    await _bulk_insert("prompts", data)


async def insert_tool_calls(data: List[Dict[str, Any]]) -> None:
    await _bulk_insert("tool_calls", data)


async def insert_sessions(data: List[Dict[str, Any]]) -> None:
    await _bulk_insert("sessions", data)


# ---------------------------------------------------------------------------
# 查询 API（保持原签名）
# ---------------------------------------------------------------------------

async def query_traces(
    trace_id: Optional[str] = None, limit: int = 100
) -> List[Dict[str, Any]]:
    """查询 trace 数据；传 trace_id 返回该链路全部 span 的扁平列表（按时间正序，含 parent_span_id）"""
    return await _select_by_filter_or_recent(
        "traces", "trace_id", trace_id, limit, "traces", asc_when_filtered=True
    )


async def query_prompts(
    trace_id: Optional[str] = None, limit: int = 100
) -> List[Dict[str, Any]]:
    return await _select_by_filter_or_recent(
        "prompts", "trace_id", trace_id, limit, "prompts"
    )


async def query_tool_calls(
    trace_id: Optional[str] = None, limit: int = 100
) -> List[Dict[str, Any]]:
    return await _select_by_filter_or_recent(
        "tool_calls", "trace_id", trace_id, limit, "tool_calls"
    )


async def query_sessions(
    limit: int = 100, agent_name: Optional[str] = None
) -> List[Dict[str, Any]]:
    # sessions 按时间倒序，按 agent_name 过滤时同样倒序
    return await _select_by_filter_or_recent(
        "sessions", "agent_name", agent_name, limit, "sessions",
        asc_when_filtered=False,
    )


async def query_metrics_compare(
    model_names: Optional[List[str]] = None,
    hours: int = 24,
) -> List[Dict[str, Any]]:
    """查询多模型效能对比数据

    hours 参数用于限定查询时间范围（最近 N 小时），避免全表扫描。
    """
    conditions = ["created_at >= now() - INTERVAL %(hours)s HOUR"]
    params: Dict[str, Any] = {"hours": hours}

    if model_names:
        # 使用 IN + 参数化，避免 SQL 注入
        placeholders = ", ".join([f"%(m{i})s" for i in range(len(model_names))])
        conditions.append(f"model_name IN ({placeholders})")
        for i, m in enumerate(model_names):
            params[f"m{i}"] = m

    where_clause = "WHERE " + " AND ".join(conditions)
    columns = (
        "model_name", "total_requests",
        "avg_prefill_ms", "avg_decode_ms", "avg_tps",
        "total_input_tokens", "total_output_tokens", "total_cost_usd",
    )

    sql = f"""
        SELECT
            model_name,
            count() AS total_requests,
            avg(prefill_ms) AS avg_prefill_ms,
            avg(decode_ms) AS avg_decode_ms,
            avg(tps) AS avg_tps,
            sum(input_tokens) AS total_input_tokens,
            sum(output_tokens) AS total_output_tokens,
            sum(cost_usd) AS total_cost_usd
        FROM llm_metrics
        {where_clause}
        GROUP BY model_name
        ORDER BY total_requests DESC
    """
    return await _select(sql, params, columns, "metrics")


# ---------------------------------------------------------------------------
# 排行榜查询（tool_stats 使用 AggregatingMergeTree，avg/max 以 State 形式存储）
# ---------------------------------------------------------------------------

_LEADERBOARD_QUERIES: Dict[str, Dict[str, Any]] = {
    "slowest_tool": {
        "sql": """
            SELECT
                tool_name, tool_type,
                sum(total_calls) AS total_calls,
                avgMerge(duration_ms_state) AS avg_duration_ms,
                maxMerge(duration_ms_max_state) AS max_duration_ms,
                sum(error_count) AS error_count,
                sum(error_count) / sum(total_calls) AS error_rate
            FROM tool_stats
            GROUP BY tool_name, tool_type
            ORDER BY avg_duration_ms DESC
            LIMIT %(lim)s
        """,
        "columns": (
            "tool_name", "tool_type", "total_calls",
            "avg_duration_ms", "max_duration_ms", "error_count", "error_rate",
        ),
    },
    "most_tokens": {
        "sql": """
            SELECT
                model_name,
                sum(input_tokens) AS total_input,
                sum(output_tokens) AS total_output,
                sum(input_tokens) + sum(output_tokens) AS total_tokens,
                count() AS request_count
            FROM llm_metrics
            GROUP BY model_name
            ORDER BY total_tokens DESC
            LIMIT %(lim)s
        """,
        "columns": (
            "model_name", "total_input", "total_output",
            "total_tokens", "request_count",
        ),
    },
    "most_failed": {
        "sql": """
            SELECT
                tool_name, tool_type,
                sum(total_calls) AS total_calls,
                sum(error_count) AS error_count,
                sum(error_count) / sum(total_calls) AS error_rate,
                avgMerge(duration_ms_state) AS avg_duration_ms
            FROM tool_stats
            GROUP BY tool_name, tool_type
            HAVING error_count > 0
            ORDER BY error_count DESC
            LIMIT %(lim)s
        """,
        "columns": (
            "tool_name", "tool_type", "total_calls",
            "error_count", "error_rate", "avg_duration_ms",
        ),
    },
}


async def query_leaderboard(
    metric: str = "slowest_tool",
    limit: int = 10,
) -> List[Dict[str, Any]]:
    """查询排行榜：slowest_tool | most_tokens | most_failed"""
    template = _LEADERBOARD_QUERIES.get(metric)
    if not template:
        return []
    return await _select(
        template["sql"], {"lim": limit}, template["columns"], f"leaderboard/{metric}"
    )
