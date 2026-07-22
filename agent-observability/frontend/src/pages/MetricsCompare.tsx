import { useState, useEffect } from 'react'
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend,
  ResponsiveContainer,
} from 'recharts'
import type { LlmMetric, ApiResponse } from '../types'

const API_BASE = '/api/v1'

function MetricsCompare() {
  const [metrics, setMetrics] = useState<LlmMetric[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    fetch(`${API_BASE}/metrics/compare`)
      .then(res => res.json() as Promise<ApiResponse<LlmMetric>>)
      .then(data => {
        if (data.status === 'success') setMetrics(data.data || [])
      })
      .catch(err => console.error('Failed to load metrics:', err))
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <div className="loading">加载中...</div>

  if (metrics.length === 0) {
    return (
      <div>
        <h2 className="page-title">多模型效能对比 (Evaluation View)</h2>
        <div className="loading">暂无数据，请先通过 SDK 上报一些 LLM 调用数据</div>
      </div>
    )
  }

  const totalRequests = metrics.reduce((sum, m) => sum + m.total_requests, 0)
  const totalCost = metrics.reduce((sum, m) => sum + m.total_cost_usd, 0)
  const avgTps = metrics.reduce((sum, m) => sum + m.avg_tps, 0) / metrics.length

  return (
    <div>
      <h2 className="page-title">多模型效能对比 (Evaluation View)</h2>

      <div className="metrics-grid">
        <div className="metric-card">
          <h3>总请求数</h3>
          <div className="value">{totalRequests.toLocaleString()}</div>
        </div>
        <div className="metric-card">
          <h3>平均 TPS</h3>
          <div className="value">{avgTps.toFixed(1)}<span className="unit">tokens/s</span></div>
        </div>
        <div className="metric-card">
          <h3>总成本</h3>
          <div className="value">${totalCost.toFixed(4)}<span className="unit">USD</span></div>
        </div>
        <div className="metric-card">
          <h3>模型数量</h3>
          <div className="value">{metrics.length}</div>
        </div>
      </div>

      <div className="chart-container">
        <h3>平均 Prefill 延迟 (ms)</h3>
        <ResponsiveContainer width="100%" height={300}>
          <BarChart data={metrics}>
            <CartesianGrid strokeDasharray="3 3" stroke="#272a35" />
            <XAxis dataKey="model_name" stroke="#71717a" />
            <YAxis stroke="#71717a" />
            <Tooltip
              contentStyle={{ background: '#161822', border: '1px solid #272a35', borderRadius: 8 }}
            />
            <Bar dataKey="avg_prefill_ms" fill="#6366f1" name="Prefill (ms)" />
          </BarChart>
        </ResponsiveContainer>
      </div>

      <div className="chart-container">
        <h3>平均 Decode 速度 (TPS - tokens/s)</h3>
        <ResponsiveContainer width="100%" height={300}>
          <BarChart data={metrics}>
            <CartesianGrid strokeDasharray="3 3" stroke="#272a35" />
            <XAxis dataKey="model_name" stroke="#71717a" />
            <YAxis stroke="#71717a" />
            <Tooltip
              contentStyle={{ background: '#161822', border: '1px solid #272a35', borderRadius: 8 }}
            />
            <Bar dataKey="avg_tps" fill="#8b5cf6" name="TPS" />
          </BarChart>
        </ResponsiveContainer>
      </div>

      <div className="chart-container">
        <h3>详细数据</h3>
        <table className="data-table">
          <thead>
            <tr>
              <th>模型</th>
              <th>请求数</th>
              <th>Prefill (ms)</th>
              <th>Decode (ms)</th>
              <th>TPS</th>
              <th>Input Tokens</th>
              <th>Output Tokens</th>
              <th>成本 (USD)</th>
            </tr>
          </thead>
          <tbody>
            {metrics.map((m, idx) => (
              <tr key={idx}>
                <td>{m.model_name}</td>
                <td>{m.total_requests}</td>
                <td>{m.avg_prefill_ms?.toFixed(1)}</td>
                <td>{m.avg_decode_ms?.toFixed(1)}</td>
                <td>{m.avg_tps?.toFixed(1)}</td>
                <td>{m.total_input_tokens?.toLocaleString()}</td>
                <td>{m.total_output_tokens?.toLocaleString()}</td>
                <td>${m.total_cost_usd?.toFixed(4)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export default MetricsCompare
