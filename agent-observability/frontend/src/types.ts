// ============================================================
// API 响应通用结构
// ============================================================

export interface ApiResponse<T> {
  status: 'success' | 'error'
  count?: number
  message?: string
  data: T[]
}

// ============================================================
// 数据实体类型
// ============================================================

/** 链路追踪 Span */
export interface Trace {
  trace_id: string
  span_id: string
  parent_span_id: string
  name: string
  start_time: string
  end_time: string
  duration_ms: number
  attributes: string | Record<string, unknown>
  created_at: string
}

/** LLM 性能指标 */
export interface LlmMetric {
  model_name: string
  provider?: string
  total_requests: number
  avg_prefill_ms: number
  avg_decode_ms: number
  avg_tps: number
  total_input_tokens: number
  total_output_tokens: number
  total_cost_usd: number
}

/** Prompt / Response 记录 */
export interface PromptLog {
  trace_id: string
  span_id: string
  model_name: string
  prompt: string
  response: string
  input_tokens: number
  output_tokens: number
  latency_ms: number
  stream: boolean
  status: 'success' | 'error'
  error: string
  created_at: string
}

/** Tool 调用记录 */
export interface ToolCall {
  trace_id: string
  span_id: string
  tool_name: string
  tool_type: string
  input_data: string
  output_data: string
  duration_ms: number
  status: 'success' | 'error'
  error: string
  attributes: string | Record<string, unknown>
  created_at: string
}

/** Agent 会话 */
export interface Session {
  session_id: string
  trace_id: string
  agent_name: string
  user_input: string
  final_response: string
  total_spans: number
  total_tokens: number
  total_cost_usd: number
  duration_ms: number
  status: 'completed' | 'error' | 'running'
  created_at: string
}

// ============================================================
// 排行榜相关类型
// ============================================================

export type LeaderboardMetric = 'slowest_tool' | 'most_tokens' | 'most_failed'

export interface SlowestToolItem {
  tool_name: string
  tool_type: string
  total_calls: number
  avg_duration_ms: number
  max_duration_ms: number
  error_count: number
  error_rate: number
}

export interface MostTokensItem {
  model_name: string
  total_input: number
  total_output: number
  total_tokens: number
  request_count: number
}

export interface MostFailedItem {
  tool_name: string
  tool_type: string
  total_calls: number
  error_count: number
  error_rate: number
  avg_duration_ms: number
}

export type LeaderboardItem = SlowestToolItem | MostTokensItem | MostFailedItem

// ============================================================
// 瀑布图 / 时间线渲染类型
// ============================================================

export interface WaterfallItem {
  name: string
  type: 'trace' | 'llm' | 'tool' | 'memory'
  depth: number
  offsetPercent: number
  durationPercent: number
  durationMs: number
}

export interface TimelineItem {
  name: string
  type: 'trace' | 'llm' | 'tool' | 'memory'
  startTime: string
  endTime: string
  durationMs: number
  attributes: Record<string, unknown>
}

// ============================================================
// 图表数据辅助类型
// ============================================================

export interface ChartData<K extends string = string> {
  name: string
  [key: string]: number | string
}
