-- ClickHouse 初始化脚本
-- 创建 AI Agent 可观测性系统所需的数据表

-- 1. agent_traces 表：记录全局链路追踪数据
CREATE TABLE IF NOT EXISTS agent_traces (
    trace_id String,
    span_id String,
    parent_span_id String,
    name String,
    start_time DateTime64(3),
    end_time DateTime64(3),
    duration_ms Float64 MATERIALIZED (end_time - start_time) * 1000,
    attributes String DEFAULT '{}',
    created_at DateTime DEFAULT now()
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(start_time)
ORDER BY (trace_id, start_time)
SETTINGS index_granularity = 8192;

-- 2. llm_metrics 表：记录大模型推理细粒度指标
CREATE TABLE IF NOT EXISTS llm_metrics (
    trace_id String,
    span_id String,
    model_name String,
    provider String DEFAULT '',
    prefill_ms Float64,
    decode_ms Float64,
    input_tokens UInt32,
    output_tokens UInt32,
    tps Float64,
    cost_usd Float64 DEFAULT 0,
    created_at DateTime DEFAULT now()
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(created_at)
ORDER BY (model_name, created_at)
SETTINGS index_granularity = 8192;

-- 3. prompt_logs 表：记录每次 LLM 调用的 Prompt 和 Response
CREATE TABLE IF NOT EXISTS prompt_logs (
    trace_id String,
    span_id String,
    model_name String,
    prompt String,
    response String,
    input_tokens UInt32 DEFAULT 0,
    output_tokens UInt32 DEFAULT 0,
    latency_ms Float64 DEFAULT 0,
    stream Bool DEFAULT false,
    status String DEFAULT 'success',
    error String DEFAULT '',
    created_at DateTime DEFAULT now()
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(created_at)
ORDER BY (trace_id, created_at)
SETTINGS index_granularity = 8192;

-- 4. tool_calls 表：记录每次 Tool 调用
CREATE TABLE IF NOT EXISTS tool_calls (
    trace_id String,
    span_id String,
    tool_name String,
    tool_type String DEFAULT 'generic',
    input_data String DEFAULT '{}',
    output_data String DEFAULT '{}',
    duration_ms Float64 DEFAULT 0,
    status String DEFAULT 'success',
    error String DEFAULT '',
    attributes String DEFAULT '{}',
    created_at DateTime DEFAULT now()
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(created_at)
ORDER BY (trace_id, created_at)
SETTINGS index_granularity = 8192;

-- 5. sessions 表：记录 Agent Session（用户会话）
CREATE TABLE IF NOT EXISTS sessions (
    session_id String,
    trace_id String,
    agent_name String DEFAULT '',
    user_input String DEFAULT '',
    final_response String DEFAULT '',
    total_spans UInt32 DEFAULT 0,
    total_tokens UInt32 DEFAULT 0,
    total_cost_usd Float64 DEFAULT 0,
    duration_ms Float64 DEFAULT 0,
    status String DEFAULT 'completed',
    created_at DateTime DEFAULT now()
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(created_at)
ORDER BY (created_at, session_id)
SETTINGS index_granularity = 8192;

-- 创建物化视图用于聚合统计
-- 注意：avg / max 等非加性聚合不能用 SummingMergeTree（合并时会错误累加），
--       必须使用 AggregatingMergeTree + State/Merge 函数。
CREATE TABLE IF NOT EXISTS model_stats_daily (
    day Date,
    model_name String,
    total_requests UInt64,
    prefill_ms_state AggregateFunction(avg, Float64),
    decode_ms_state AggregateFunction(avg, Float64),
    tps_state AggregateFunction(avg, Float64),
    total_input_tokens UInt64,
    total_output_tokens UInt64,
    total_cost_usd Float64
) ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(day)
ORDER BY (day, model_name);

CREATE MATERIALIZED VIEW IF NOT EXISTS model_stats_daily_mv
TO model_stats_daily AS
SELECT
    toDate(created_at) AS day,
    model_name,
    count() AS total_requests,
    avgState(prefill_ms) AS prefill_ms_state,
    avgState(decode_ms) AS decode_ms_state,
    avgState(tps) AS tps_state,
    sum(input_tokens) AS total_input_tokens,
    sum(output_tokens) AS total_output_tokens,
    sum(cost_usd) AS total_cost_usd
FROM llm_metrics
GROUP BY day, model_name;

-- 6. 工具调用排行榜视图（最慢 Tool / 失败次数）
-- 使用 AggregatingMergeTree 存储 avg/max 的状态，查询时通过 Merge 函数还原
CREATE TABLE IF NOT EXISTS tool_stats (
    tool_name String,
    tool_type String,
    total_calls UInt64,
    duration_ms_state AggregateFunction(avg, Float64),
    duration_ms_max_state AggregateFunction(max, Float64),
    error_count UInt64,
    created_at DateTime DEFAULT now()
) ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(created_at)
ORDER BY (tool_name, tool_type);

CREATE MATERIALIZED VIEW IF NOT EXISTS tool_stats_mv
TO tool_stats AS
SELECT
    tool_name,
    tool_type,
    count() AS total_calls,
    avgState(duration_ms) AS duration_ms_state,
    maxState(duration_ms) AS duration_ms_max_state,
    sumIf(1, status = 'error') AS error_count,
    now() AS created_at
FROM tool_calls
GROUP BY tool_name, tool_type;
