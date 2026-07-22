# Agent Insight Frontend

AI Agent 可观测性系统的前端控制台 —— 通过可视化界面展示链路追踪、LLM 性能指标、Prompt 回放、Tool 调用、Session 会话和排行榜数据，所有数据来自后端查询 API。

## 架构

```
┌──────────────┐      ┌──────────────┐      ┌──────────────┐
│   浏览器 UI   │─────▶│  Vite Dev    │─────▶│  FastAPI     │
│  React SPA   │◀─────│  Server      │◀─────│  Backend     │
└──────────────┘  /api │  (代理 /api) │  查询 │  (8000)      │
                        └──────────────┘      └──────┬───────┘
                                                     │
                                                     ▼
                                              ┌──────────────┐
                                              │  ClickHouse  │
                                              └──────────────┘
```

**数据流**：浏览器 → Vite Dev Server 代理 `/api/*` → FastAPI Backend → ClickHouse 查询 → 返回 JSON → React 渲染图表/表格

## 项目结构

```
frontend/
├── src/
│   ├── main.tsx                  # 应用入口
│   ├── App.tsx                   # 路由与侧边栏导航
│   ├── types.ts                  # TypeScript 类型定义（对齐 backend 数据结构）
│   ├── index.css                 # 全局样式（暗色主题）
│   └── pages/
│       ├── TraceView.tsx         # 链路追踪瀑布图
│       ├── Timeline.tsx          # 时间线视图
│       ├── PromptReplay.tsx      # Prompt/Tool 调用回放
│       ├── SessionList.tsx       # Session 会话列表
│       ├── MetricsCompare.tsx    # 多模型效能对比
│       ├── StatsDashboard.tsx    # 统计分析仪表盘
│       └── Leaderboard.tsx       # 排行榜
├── vite.config.ts                # Vite 配置（含 /api 代理）
├── tsconfig.json                 # TypeScript 配置
└── package.json
```

## 功能页面

| 路由              | 页面              | 说明                                                  |
|-------------------|-------------------|-------------------------------------------------------|
| `/`               | 链路跟踪          | 瀑布图展示一条链路的 span 树，按时间偏移和耗时渲染      |
| `/timeline`       | 时间线            | 按时间正序列出 span，标注类型（trace/llm/tool/memory）  |
| `/prompt-replay`  | Prompt 回放       | 查看选定链路的 LLM Prompt/Response 和 Tool 调用详情     |
| `/sessions`       | Session 会话      | Session 列表 + 聚合指标（总数/已完成/失败/总 Token）    |
| `/metrics`        | 模型效能对比      | 多模型 Prefill/Decode/TPS 柱状图对比                   |
| `/stats`          | 统计分析          | Token 分布、成本饼图、性能折线图                       |
| `/leaderboard`    | 排行榜            | 最慢 Tool / Token 消耗 / 失败次数 三个榜单             |

## 调用的后端 API

所有请求走 `/api/v1` 前缀，开发环境由 Vite 代理转发至 `http://localhost:8000`。

| 方法 | 路径                     | 使用页面                          |
|------|--------------------------|-----------------------------------|
| GET  | `/api/v1/traces`         | 链路跟踪 / 时间线 / Prompt 回放 / Session 详情 |
| GET  | `/api/v1/sessions`       | Session 会话                      |
| GET  | `/api/v1/prompts`        | Prompt 回放                       |
| GET  | `/api/v1/tool-calls`     | Prompt 回放                       |
| GET  | `/api/v1/metrics/compare`| 模型效能对比 / 统计分析           |
| GET  | `/api/v1/leaderboard`    | 排行榜                            |

## 数据类型对齐

前端 `types.ts` 中的类型定义与 backend ClickHouse 表结构保持一致：

| 类型         | 对应 ClickHouse 表 | 关键字段                              |
|--------------|--------------------|---------------------------------------|
| `Trace`      | `agent_traces`     | `attributes`（JSON 字符串，含 model 等）|
| `LlmMetric`  | `llm_metrics`      | `provider`、`avg_prefill_ms`、`avg_tps` |
| `PromptLog`  | `prompt_logs`      | `prompt`、`response`、`stream`         |
| `ToolCall`   | `tool_calls`       | `attributes`（MCP/RAG 元数据 JSON）    |
| `Session`    | `sessions`         | `total_tokens`、`total_cost_usd`       |

## 配置

### Vite 开发代理

[vite.config.ts](vite.config.ts) 中配置了 `/api` 代理，开发时自动转发到后端：

```ts
server: {
  port: 3000,
  proxy: {
    '/api': {
      target: 'http://localhost:8000',
      changeOrigin: true,
    },
  },
}
```

如后端运行在其他地址，修改 `target` 即可。

## 安装与运行

### 前置依赖

- Node.js 18+
- 后端服务已启动（默认 `http://localhost:8000`）

### 安装

```bash
cd frontend
npm install
```

### 开发模式

```bash
cd frontend
npm run dev
```

启动后访问 `http://localhost:3000`，API 请求会自动代理到后端。

### 生产构建

```bash
cd frontend
npm run build
```

构建产物输出到 `dist/`，可直接用 nginx 或其他静态服务器托管，需将 `/api/*` 反向代理到后端。

### 预览构建产物

```bash
cd frontend
npm run preview
```

## 技术栈

| 组件          | 技术                          |
|--------------|-------------------------------|
| 框架          | React 18                      |
| 构建工具      | Vite 5                        |
| 路由          | react-router-dom 6            |
| 图表          | recharts 2                    |
| 类型系统      | TypeScript 5                  |
| 主题          | 暗色主题（自定义 CSS）          |
