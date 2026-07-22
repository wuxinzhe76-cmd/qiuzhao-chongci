import { useState, useEffect } from 'react'
import type { Session, Trace, ApiResponse } from '../types'

const API_BASE = '/api/v1'

function SessionList() {
  const [sessions, setSessions] = useState<Session[]>([])
  const [selectedSession, setSelectedSession] = useState<string | null>(null)
  const [traces, setTraces] = useState<Trace[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetch(`${API_BASE}/sessions`)
      .then(res => res.json() as Promise<ApiResponse<Session>>)
      .then(data => {
        if (data.status === 'success') setSessions(data.data || [])
      })
      .catch(err => console.error('Failed to load sessions:', err))
      .finally(() => setLoading(false))
  }, [])

  const viewSessionDetail = (sessionId: string, traceId: string): void => {
    setSelectedSession(sessionId)
    fetch(`${API_BASE}/traces?trace_id=${traceId}`)
      .then(res => res.json() as Promise<ApiResponse<Trace>>)
      .then(data => {
        if (data.status === 'success') setTraces(data.data || [])
      })
      .catch(err => console.error('Failed to load traces:', err))
  }

  const formatTime = (ts: string | undefined): string => {
    if (!ts) return '-'
    return new Date(ts).toLocaleString()
  }

  const formatDuration = (ms: number | undefined): string => {
    if (!ms) return '0ms'
    if (ms < 1000) return `${ms.toFixed(0)}ms`
    return `${(ms / 1000).toFixed(1)}s`
  }

  return (
    <div>
      <h2 className="page-title">Session 会话列表</h2>

      {loading ? (
        <div className="loading">加载中...</div>
      ) : (
        <div className="session-container">
          <div className="metrics-grid">
            <div className="metric-card">
              <h3>总 Session</h3>
              <div className="value">{sessions.length}</div>
            </div>
            <div className="metric-card">
              <h3>已完成</h3>
              <div className="value">
                {sessions.filter(s => s.status === 'completed').length}
              </div>
            </div>
            <div className="metric-card">
              <h3>失败</h3>
              <div className="value">
                {sessions.filter(s => s.status === 'error').length}
              </div>
            </div>
            <div className="metric-card">
              <h3>总 Token</h3>
              <div className="value">
                {sessions.reduce((sum, s) => sum + (s.total_tokens || 0), 0).toLocaleString()}
              </div>
            </div>
          </div>

          <div className="session-table">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Agent</th>
                  <th>Session ID</th>
                  <th>用户输入</th>
                  <th>Span 数</th>
                  <th>Token 数</th>
                  <th>耗时</th>
                  <th>成本</th>
                  <th>状态</th>
                  <th>时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {sessions.length === 0 ? (
                  <tr><td colSpan={10} className="center">暂无数据</td></tr>
                ) : (
                  sessions.map(s => (
                    <tr key={s.session_id}>
                      <td>{s.agent_name || '-'}</td>
                      <td className="mono">{s.session_id?.slice(0, 12)}...</td>
                      <td className="ellipsis" title={s.user_input}>
                        {s.user_input?.slice(0, 40) || '-'}
                      </td>
                      <td>{s.total_spans || 0}</td>
                      <td>{s.total_tokens?.toLocaleString() || 0}</td>
                      <td>{formatDuration(s.duration_ms)}</td>
                      <td>${(s.total_cost_usd || 0).toFixed(4)}</td>
                      <td>
                        <span className={`badge ${s.status}`}>{s.status}</span>
                      </td>
                      <td>{formatTime(s.created_at)}</td>
                      <td>
                        <button
                          className="btn-sm"
                          onClick={() => viewSessionDetail(s.session_id, s.trace_id)}
                        >
                          查看链路
                        </button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>

          {selectedSession && (
            <div className="detail-panel">
              <h3>Session {selectedSession.slice(0, 12)}... 的链路详情</h3>
              {traces.length === 0 ? (
                <div className="loading">该 Session 暂无链路数据</div>
              ) : (
                <table className="data-table">
                  <thead>
                    <tr>
                      <th>Span 名称</th>
                      <th>开始时间</th>
                      <th>结束时间</th>
                      <th>耗时</th>
                      <th>属性</th>
                    </tr>
                  </thead>
                  <tbody>
                    {traces.map((t, idx) => (
                      <tr key={idx}>
                        <td>{t.name}</td>
                        <td>{formatTime(t.start_time)}</td>
                        <td>{formatTime(t.end_time)}</td>
                        <td>{formatDuration(t.duration_ms)}</td>
                        <td className="mono" style={{ fontSize: 11 }}>
                          {typeof t.attributes === 'string'
                            ? t.attributes.slice(0, 60)
                            : JSON.stringify(t.attributes || {}).slice(0, 60)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export default SessionList
