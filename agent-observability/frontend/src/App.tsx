import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom'
import TraceView from './pages/TraceView'
import Timeline from './pages/Timeline'
import PromptReplay from './pages/PromptReplay'
import MetricsCompare from './pages/MetricsCompare'
import StatsDashboard from './pages/StatsDashboard'
import SessionList from './pages/SessionList'
import Leaderboard from './pages/Leaderboard'

const navItems = [
  { to: '/', label: '链路跟踪', end: true },
  { to: '/timeline', label: '时间线' },
  { to: '/prompt-replay', label: 'Prompt 回放' },
  { to: '/sessions', label: 'Session 会话' },
  { to: '/metrics', label: '模型效能对比' },
  { to: '/stats', label: '统计分析' },
  { to: '/leaderboard', label: '排行榜' },
]

function App() {
  return (
    <BrowserRouter>
      <div className="app">
        <aside className="sidebar">
          <h1>Agent-Insight</h1>
          <nav>
            {navItems.map(({ to, label, end }) => (
              <NavLink
                key={to}
                to={to}
                end={end}
                className={({ isActive }) => isActive ? 'active' : ''}
              >
                {label}
              </NavLink>
            ))}
          </nav>
        </aside>
        <main className="main-content">
          <Routes>
            <Route path="/" element={<TraceView />} />
            <Route path="/timeline" element={<Timeline />} />
            <Route path="/prompt-replay" element={<PromptReplay />} />
            <Route path="/sessions" element={<SessionList />} />
            <Route path="/metrics" element={<MetricsCompare />} />
            <Route path="/stats" element={<StatsDashboard />} />
            <Route path="/leaderboard" element={<Leaderboard />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  )
}

export default App
