import { useState, useEffect } from 'react'
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend,
  ResponsiveContainer, LineChart, Line, PieChart, Pie, Cell,
} from 'recharts'
import type { LlmMetric, ApiResponse, ChartData } from '../types'

const API_BASE = '/api/v1'
const COLORS = ['#6366f1', '#8b5cf6', '#a855f7', '#d946ef', '#ec4899', '#f43f5e']

function StatsDashboard() {
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
        <h2 className="page-title">统计分析 (Stats Dashboard)</h2>
        <div className="loading">暂无数据，请先通过 SDK 上报一些 LLM 调用数据</div>
      </div>
    )
  }

  const totalRequests = metrics.reduce((sum, m) => sum + m.total_requests, 0)
  const totalTokens = metrics.reduce((sum, m) => sum + m.total_input_tokens + m.total_output_tokens, 0)
  const totalCost = metrics.reduce((sum, m) => sum + m.total_cost_usd, 0)
  const avgTps = metrics.reduce((sum, m) => sum + m.avg_tps, 0) / metrics.length
  const avgPrefill = metrics.reduce((sum, m) => sum + m.avg_prefill_ms, 0) / metrics.length
  const avgDecode = metrics.reduce((sum, m) => sum + m.avg_decode_ms, 0) / metrics.length

  const tokenDistribution = metrics.map(m => ({
    name: m.model_name,
    input: m.total_input_tokens,
    output: m.total_output_tokens,
  }))

  const costDistribution = metrics.map(m => ({
    name: m.model_name,
    value: m.total_cost_usd,
  }))

  return (
    <div>
      <h2 className="page-title">统计分析 (Stats Dashboard)</h2>

      <div className="metrics-grid">
        <div className="metric-card">
          <h3>总请求数</h3>
          <div className="value">{totalRequests.toLocaleString()}</div>
        </div>
        <div className="metric-card">
          <h3>总 Token 数</h3>
          <div className="value">{totalTokens.toLocaleString()}</div>
        </div>
        <div className="metric-card">
          <h3>总成本</h3>
          <div className="value">${totalCost.toFixed(4)}<span className="unit">USD</span></div>
        </div>
        <div className="metric-card">
          <h3>平均 TPS</h3>
          <div className="value">{avgTps.toFixed(1)}<span className="unit">tokens/s</span></div>
        </div>
        <div className="metric-card">
          <h3>平均 Prefill</h3>
          <div className="value">{avgPrefill.toFixed(0)}<span className="unit">ms</span></div>
        </div>
        <div className="metric-card">
          <h3>平均 Decode</h3>
          <div className="value">{avgDecode.toFixed(0)}<span className="unit">ms</span></div>
        </div>
      </div>

      {/* Token 分布 */}
      <div className="chart-container">
        <h3>Token 分布 (Input vs Output)</h3>
        <ResponsiveContainer width="100%" height={300}>
          <BarChart data={tokenDistribution}>
            <CartesianGrid strokeDasharray="3 3" stroke="#272a35" />
            <XAxis dataKey="name" stroke="#71717a" />
            <YAxis stroke="#71717a" />
            <Tooltip contentStyle={{ background: '#161822', border: '1px solid #272a35', borderRadius: 8 }} />
            <Legend />
            <Bar dataKey="input" fill="#6366f1" name="Input Tokens" stackId="a" />
            <Bar dataKey="output" fill="#8b5cf6" name="Output Tokens" stackId="a" />
          </BarChart>
        </ResponsiveContainer>
      </div>

      {/* 成本分布饼图 */}
      <div className="chart-container">
        <h3>成本分布 (USD)</h3>
        <ResponsiveContainer width="100%" height={300}>
          <PieChart>
            <Pie
              data={costDistribution}
              cx="50%"
              cy="50%"
              labelLine={false}
              label={({ name, percent }) => `${name}: ${(percent * 100).toFixed(0)}%`}
              outerRadius={100}
              fill="#8884d8"
              dataKey="value"
            >
              {costDistribution.map((_, index) => (
                <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
              ))}
            </Pie>
            <Tooltip
              contentStyle={{ background: '#161822', border: '1px solid #272a35', borderRadius: 8 }}
              formatter={(value: number) => `$${value.toFixed(4)}`}
            />
          </PieChart>
        </ResponsiveContainer>
      </div>

      {/* 性能指标折线图 */}
      <div className="chart-container">
        <h3>性能指标对比</h3>
        <ResponsiveContainer width="100%" height={300}>
          <LineChart data={metrics}>
            <CartesianGrid strokeDasharray="3 3" stroke="#272a35" />
            <XAxis dataKey="model_name" stroke="#71717a" />
            <YAxis stroke="#71717a" />
            <Tooltip contentStyle={{ background: '#161822', border: '1px solid #272a35', borderRadius: 8 }} />
            <Legend />
            <Line type="monotone" dataKey="avg_prefill_ms" stroke="#6366f1" name="Prefill (ms)" />
            <Line type="monotone" dataKey="avg_decode_ms" stroke="#8b5cf6" name="Decode (ms)" />
            <Line type="monotone" dataKey="avg_tps" stroke="#a855f7" name="TPS" yAxisId="right" />
          </LineChart>
        </ResponsiveContainer>
      </div>

      {/* 详细数据表 */}
      <div className="chart-container">
        <h3>详细数据</h3>
        <table className="data-table">
          <thead>
            <tr>
              <th>模型</th>
              <th>请求数</th>
              <th>Input Tokens</th>
              <th>Output Tokens</th>
              <th>Total Tokens</th>
              <th>Prefill (ms)</th>
              <th>Decode (ms)</th>
              <th>TPS</th>
              <th>成本 (USD)</th>
            </tr>
          </thead>
          <tbody>
            {metrics.map((m, idx) => (
              <tr key={idx}>
                <td>{m.model_name}</td>
                <td>{m.total_requests}</td>
                <td>{m.total_input_tokens?.toLocaleString()}</td>
                <td>{m.total_output_tokens?.toLocaleString()}</td>
                <td>{(m.total_input_tokens + m.total_output_tokens)?.toLocaleString()}</td>
                <td>{m.avg_prefill_ms?.toFixed(1)}</td>
                <td>{m.avg_decode_ms?.toFixed(1)}</td>
                <td>{m.avg_tps?.toFixed(1)}</td>
                <td>${m.total_cost_usd?.toFixed(4)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

export default StatsDashboard
