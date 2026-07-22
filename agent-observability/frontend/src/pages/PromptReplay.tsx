import { useState, useEffect } from 'react'
import type { Trace, PromptLog, ToolCall, ApiResponse } from '../types'

const API_BASE = '/api/v1'

function PromptReplay() {
  const [traces, setTraces] = useState<Trace[]>([])
  const [selectedTrace, setSelectedTrace] = useState<string | null>(null)
  const [prompts, setPrompts] = useState<PromptLog[]>([])
  const [toolCalls, setToolCalls] = useState<ToolCall[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetch(`${API_BASE}/traces`)
      .then(res => res.json() as Promise<ApiResponse<Trace>>)
      .then(data => {
        if (data.status === 'success') {
          const unique = [...new Map(data.data.map(t => [t.trace_id, t])).values()]
          setTraces(unique)
        }
      })
      .catch(err => console.error('Failed to load traces:', err))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    if (!selectedTrace) return

    Promise.all([
      fetch(`${API_BASE}/prompts?trace_id=${selectedTrace}`).then(res => res.json()) as Promise<ApiResponse<PromptLog>>,
      fetch(`${API_BASE}/tool-calls?trace_id=${selectedTrace}`).then(res => res.json()) as Promise<ApiResponse<ToolCall>>,
    ])
      .then(([promptData, toolData]) => {
        if (promptData.status === 'success') setPrompts(promptData.data || [])
        if (toolData.status === 'success') setToolCalls(toolData.data || [])
      })
      .catch(err => console.error('Failed to load replay data:', err))
  }, [selectedTrace])

  return (
    <div>
      <h2 className="page-title">Prompt 回放 (Prompt Replay)</h2>

      <div className="trace-selector">
        <select
          value={selectedTrace ?? ''}
          onChange={e => setSelectedTrace(e.target.value)}
        >
          <option value="">选择一条链路...</option>
          {traces.map(t => (
            <option key={t.trace_id} value={t.trace_id}>
              {t.trace_id.slice(0, 8)}... - {t.name} ({new Date(t.start_time).toLocaleString()})
            </option>
          ))}
        </select>
      </div>

      {loading ? (
        <div className="loading">加载中...</div>
      ) : !selectedTrace ? (
        <div className="loading">请选择一条链路查看回放</div>
      ) : (
        <div className="replay-container">
          <section className="replay-section">
            <h3>LLM 调用记录 ({prompts.length})</h3>
            {prompts.length === 0 ? (
              <div className="loading">暂无 LLM 调用记录</div>
            ) : (
              prompts.map((p, idx) => (
                <div className="prompt-card" key={idx}>
                  <div className="prompt-header">
                    <span className="prompt-model">{p.model_name}</span>
                    <span className={`prompt-status ${p.status}`}>{p.status}</span>
                    <span className="prompt-tokens">
                      {p.input_tokens} &rarr; {p.output_tokens} tokens
                    </span>
                    <span className="prompt-latency">{p.latency_ms?.toFixed(0)}ms</span>
                    {p.stream && <span className="prompt-stream">stream</span>}
                  </div>
                  <div className="prompt-content">
                    <div className="prompt-input">
                      <strong>Prompt:</strong>
                      <pre>{p.prompt}</pre>
                    </div>
                    <div className="prompt-output">
                      <strong>Response:</strong>
                      <pre>{p.response}</pre>
                    </div>
                    {p.error && (
                      <div className="prompt-error">
                        <strong>Error:</strong> {p.error}
                      </div>
                    )}
                  </div>
                </div>
              ))
            )}
          </section>

          <section className="replay-section">
            <h3>Tool 调用记录 ({toolCalls.length})</h3>
            {toolCalls.length === 0 ? (
              <div className="loading">暂无 Tool 调用记录</div>
            ) : (
              toolCalls.map((tc, idx) => {
                const attrs = parseToolAttrs(tc.attributes)
                return (
                <div className="tool-card" key={idx}>
                  <div className="tool-header">
                    <span className="tool-name">{tc.tool_name}</span>
                    <span className="tool-type">{tc.tool_type}</span>
                    {attrs.tool_subtype && (
                      <span className={`tool-subtype ${attrs.tool_subtype}`}>{attrs.tool_subtype}</span>
                    )}
                    <span className={`tool-status ${tc.status}`}>{tc.status}</span>
                    <span className="tool-duration">{tc.duration_ms?.toFixed(0)}ms</span>
                  </div>
                  {(attrs.mcp_server || attrs.mcp_tool || attrs.rag_vector_db || attrs.rag_top_k || attrs.rag_recall_count) && (
                    <div className="tool-meta">
                      {attrs.mcp_server && <span className="meta-tag">MCP Server: {attrs.mcp_server}</span>}
                      {attrs.mcp_tool && <span className="meta-tag">MCP Tool: {attrs.mcp_tool}</span>}
                      {attrs.rag_vector_db && <span className="meta-tag">Vector DB: {attrs.rag_vector_db}</span>}
                      {attrs.rag_top_k != null && <span className="meta-tag">Top K: {attrs.rag_top_k}</span>}
                      {attrs.rag_recall_count != null && <span className="meta-tag">Recall: {attrs.rag_recall_count}</span>}
                    </div>
                  )}
                  <div className="tool-content">
                    <div className="tool-input">
                      <strong>Input:</strong>
                      <pre>{tc.input_data}</pre>
                    </div>
                    <div className="tool-output">
                      <strong>Output:</strong>
                      <pre>{tc.output_data}</pre>
                    </div>
                    {tc.error && (
                      <div className="tool-error">
                        <strong>Error:</strong> {tc.error}
                      </div>
                    )}
                  </div>
                </div>
                )
              })
            )}
          </section>
        </div>
      )}
    </div>
  )
}

export default PromptReplay

// ---- helpers ----

interface ToolAttrs {
  tool_subtype?: string
  mcp_server?: string
  mcp_tool?: string
  mcp_protocol_version?: string
  rag_vector_db?: string
  rag_top_k?: number
  rag_recall_count?: number
}

function parseToolAttrs(raw: string | Record<string, unknown> | undefined): ToolAttrs {
  if (!raw) return {}
  try {
    const parsed = typeof raw === 'string' ? JSON.parse(raw || '{}') : raw
    return parsed || {}
  } catch {
    return {}
  }
}
