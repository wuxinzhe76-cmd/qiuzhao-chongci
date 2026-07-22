import { useState, useEffect } from 'react'
import type {
  Trace,
  WaterfallItem,
  ApiResponse,
} from '../types'

const API_BASE = '/api/v1'

/** 带有 children 的 span 树节点 */
interface SpanNode extends Trace {
  children: SpanNode[]
}

function TraceView() {
  const [selectedTrace, setSelectedTrace] = useState<string | null>(null)
  const [traceList, setTraceList] = useState<Trace[]>([])
  const [traces, setTraces] = useState<Trace[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetch(`${API_BASE}/traces`)
      .then(res => res.json() as Promise<ApiResponse<Trace>>)
      .then(data => {
        if (data.status === 'success') {
          const unique = [...new Map(data.data.map(t => [t.trace_id, t])).values()]
          setTraceList(unique)
        }
      })
      .catch(err => console.error('Failed to load traces:', err))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    if (!selectedTrace) return
    fetch(`${API_BASE}/traces?trace_id=${selectedTrace}`)
      .then(res => res.json() as Promise<ApiResponse<Trace>>)
      .then(data => {
        if (data.status === 'success') setTraces(data.data || [])
      })
      .catch(err => console.error('Failed to load trace detail:', err))
  }, [selectedTrace])

  const waterfallData = buildWaterfall(traces)

  return (
    <div>
      <h2 className="page-title">链路跟踪 (Trace View)</h2>

      <div className="trace-selector">
        <select
          value={selectedTrace ?? ''}
          onChange={e => setSelectedTrace(e.target.value)}
        >
          <option value="">选择一条链路...</option>
          {traceList.map(t => (
            <option key={t.trace_id} value={t.trace_id}>
              {t.trace_id.slice(0, 8)}... - {t.name} ({new Date(t.start_time).toLocaleString()})
            </option>
          ))}
        </select>
      </div>

      {loading ? (
        <div className="loading">加载中...</div>
      ) : !selectedTrace ? (
        <div className="loading">请选择一条链路查看瀑布图</div>
      ) : (
        <div className="waterfall">
          {waterfallData.length === 0 ? (
            <div className="loading">暂无数据</div>
          ) : (
            waterfallData.map((item, idx) => (
              <div className="waterfall-item" key={idx}>
                <div className="waterfall-label" title={item.name}>
                  {'  '.repeat(item.depth)}{item.name}
                </div>
                <div className="waterfall-bar-container">
                  <div
                    className={`waterfall-bar ${item.type}`}
                    style={{
                      left: `${item.offsetPercent}%`,
                      width: `${Math.max(item.durationPercent, 2)}%`,
                    }}
                  >
                    {item.durationPercent > 10 ? `${item.durationMs.toFixed(0)}ms` : ''}
                  </div>
                </div>
                <div className="waterfall-duration">{item.durationMs.toFixed(1)}ms</div>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  )
}

// ---- helpers ----

function buildWaterfall(traces: Trace[]): WaterfallItem[] {
  if (traces.length === 0) return []

  const times = traces.flatMap(t => [
    new Date(t.start_time).getTime(),
    new Date(t.end_time).getTime(),
  ])
  const globalStart = Math.min(...times)
  const globalEnd = Math.max(...times)
  const totalDuration = globalEnd - globalStart || 1

  const spanMap = new Map<string, SpanNode>()
  traces.forEach(t => {
    spanMap.set(t.span_id, { ...t, children: [] })
  })

  const roots: SpanNode[] = []
  traces.forEach(t => {
    const span = spanMap.get(t.span_id)!
    if (t.parent_span_id && spanMap.has(t.parent_span_id)) {
      spanMap.get(t.parent_span_id)!.children.push(span)
    } else {
      roots.push(span)
    }
  })

  const result: WaterfallItem[] = []

  function flatten(spans: SpanNode[], depth: number): void {
    spans.forEach(span => {
      const start = new Date(span.start_time).getTime()
      const end = new Date(span.end_time).getTime()
      const durationMs = end - start
      const offsetPercent = ((start - globalStart) / totalDuration) * 100
      const durationPercent = (durationMs / totalDuration) * 100

      let type: WaterfallItem['type'] = 'trace'
      const attrs = typeof span.attributes === 'string'
        ? JSON.parse(span.attributes || '{}')
        : span.attributes || {}
      if (span.name === 'llm_call' || span.name === 'llm_metrics' || attrs.model || attrs.model_name) type = 'llm'
      else if (span.name?.startsWith('tool:') || span.name?.includes('tool')) type = 'tool'

      result.push({
        name: span.name || 'unknown',
        type,
        depth,
        offsetPercent,
        durationPercent,
        durationMs,
      })

      flatten(span.children, depth + 1)
    })
  }

  flatten(roots, 0)
  return result
}

export default TraceView
