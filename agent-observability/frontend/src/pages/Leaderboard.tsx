import { useState, useEffect } from 'react'
import type {
  LeaderboardMetric,
  SlowestToolItem,
  MostTokensItem,
  MostFailedItem,
  LeaderboardItem,
  ApiResponse,
} from '../types'

const API_BASE = '/api/v1'

interface MetricOption {
  value: LeaderboardMetric
  label: string
}

const METRIC_OPTIONS: MetricOption[] = [
  { value: 'slowest_tool', label: '最慢 Tool 调用' },
  { value: 'most_tokens', label: 'Token 消耗排行榜' },
  { value: 'most_failed', label: '失败次数排行榜' },
]

function Leaderboard() {
  const [metric, setMetric] = useState<LeaderboardMetric>('slowest_tool')
  const [data, setData] = useState<LeaderboardItem[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setLoading(true)
    fetch(`${API_BASE}/leaderboard?metric=${metric}&limit=15`)
      .then(res => res.json() as Promise<ApiResponse<LeaderboardItem>>)
      .then(result => {
        if (result.status === 'success') setData(result.data || [])
      })
      .catch(err => console.error('Failed to load leaderboard:', err))
      .finally(() => setLoading(false))
  }, [metric])

  const renderTable = () => {
    if (loading) return <div className="loading">加载中...</div>
    if (data.length === 0) return <div className="loading">暂无数据</div>

    if (metric === 'slowest_tool') {
      const items = data as SlowestToolItem[]
      return (
        <table className="data-table">
          <thead>
            <tr>
              <th>#</th>
              <th>Tool 名称</th>
              <th>类型</th>
              <th>调用次数</th>
              <th>平均耗时</th>
              <th>最大耗时</th>
              <th>错误次数</th>
              <th>错误率</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item, idx) => (
              <tr key={idx}>
                <td className="rank">{idx + 1}</td>
                <td>{item.tool_name}</td>
                <td>{item.tool_type}</td>
                <td>{item.total_calls}</td>
                <td className={item.avg_duration_ms > 2000 ? 'warn' : ''}>
                  {item.avg_duration_ms?.toFixed(1)}ms
                </td>
                <td>{item.max_duration_ms?.toFixed(1)}ms</td>
                <td>{item.error_count}</td>
                <td>{(item.error_rate * 100)?.toFixed(1)}%</td>
              </tr>
            ))}
          </tbody>
        </table>
      )
    }

    if (metric === 'most_tokens') {
      const items = data as MostTokensItem[]
      return (
        <table className="data-table">
          <thead>
            <tr>
              <th>#</th>
              <th>模型</th>
              <th>Input Tokens</th>
              <th>Output Tokens</th>
              <th>总 Token</th>
              <th>请求数</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item, idx) => (
              <tr key={idx}>
                <td className="rank">{idx + 1}</td>
                <td>{item.model_name}</td>
                <td>{item.total_input?.toLocaleString()}</td>
                <td>{item.total_output?.toLocaleString()}</td>
                <td className="highlight">{item.total_tokens?.toLocaleString()}</td>
                <td>{item.request_count}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )
    }

    if (metric === 'most_failed') {
      const items = data as MostFailedItem[]
      return (
        <table className="data-table">
          <thead>
            <tr>
              <th>#</th>
              <th>Tool 名称</th>
              <th>类型</th>
              <th>总调用</th>
              <th>失败次数</th>
              <th>失败率</th>
              <th>平均耗时</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item, idx) => (
              <tr key={idx}>
                <td className="rank">{idx + 1}</td>
                <td>{item.tool_name}</td>
                <td>{item.tool_type}</td>
                <td>{item.total_calls}</td>
                <td className="danger">{item.error_count}</td>
                <td>{(item.error_rate * 100)?.toFixed(1)}%</td>
                <td>{item.avg_duration_ms?.toFixed(1)}ms</td>
              </tr>
            ))}
          </tbody>
        </table>
      )
    }

    return null
  }

  return (
    <div>
      <h2 className="page-title">排行榜 (Leaderboard)</h2>

      <div className="leaderboard-tabs">
        {METRIC_OPTIONS.map(opt => (
          <button
            key={opt.value}
            className={`tab-btn ${metric === opt.value ? 'active' : ''}`}
            onClick={() => setMetric(opt.value)}
          >
            {opt.label}
          </button>
        ))}
      </div>

      <div className="leaderboard-table">
        {renderTable()}
      </div>
    </div>
  )
}

export default Leaderboard
