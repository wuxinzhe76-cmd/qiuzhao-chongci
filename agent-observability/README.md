# AI Agent 可观测性系统 (Agent-Insight)

一个轻量级但具备高并发扩展能力的 AI Agent 可观测性基础设施原型。

> 本项目是一个完整的 AI Agent 可观测性系统，涵盖从 SDK 自动埋点、数据采集存储到 Dashboard 可视化的全链路，可作为简历项目展示。

## 系统架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          AI Agent 应用层                                 │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  OpenAI Client / 自定义 Tool / Memory / Planner                  │  │
│  └────────────────────────┬─────────────────────────────────────────┘  │
│                           │                                            │
│  ┌────────────────────────▼─────────────────────────────────────────┐  │
│  │  Agent Insight SDK (非侵入式拦截)                                 │  │
│  │  - TraceContext 上下文传递 (contextvars)                         │  │
│  │  - LLMInterceptor 多厂商调用拦截                                │  │
│  │  - StreamMonitor 流式响应监控 (prefill/decode/TPS)               │  │
│  │  - ToolSDK 装饰器自动埋点                                        │  │
│  │  - TraceAPI 显式 startTrace/startSpan/endSpan                    │  │
│  │  - AsyncBatchUploader 异步批量上报                               │  │
│  └────────────────────────┬─────────────────────────────────────────┘  │
└───────────────────────────┼────────────────────────────────────────────┘
                            │ HTTP POST /api/v1/collect
                            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          后端服务层                                      │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  FastAPI Collector (v0.3.0)                                      │  │
│  │  - 5 种数据类型参数校验 → Kafka 投递 → 立即返回 202              │  │
│  └────────────────────────┬─────────────────────────────────────────┘  │
│                           │                                            │
│  ┌────────────────────────▼─────────────────────────────────────────┐  │
│  │  Kafka (KRaft 模式, 无需 Zookeeper)                               │  │
│  │  Topic: agent-logs                                               │  │
│  └────────────────────────┬─────────────────────────────────────────┘  │
│                           │                                            │
│  ┌────────────────────────▼─────────────────────────────────────────┐  │
│  │  Kafka Consumer → ClickHouse 按类型分流写入 5 张表               │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Query API (5 个路由模块)                                         │  │
│  │  /api/v1/collect  /traces  /sessions  /prompts  /tool-calls      │  │
│  │  /api/v1/metrics/compare  /leaderboard                           │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          前端展示层                                      │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  React 18 + TypeScript + Vite + Recharts                         │  │
│  │  - Trace Tree 瀑布图 (展开每个 Span 查看详情)                     │  │
│  │  - Timeline 时间线 (按 span 类型着色)                             │  │
│  │  - Prompt Replay (Prompt / Response / Tool 调用记录)             │  │
│  │  - Session 会话列表 (多维度筛选 + 关联链路)                       │  │
│  │  - 模型效能对比 (Prefill / Decode / TPS 柱状图)                   │  │
│  │  - 统计分析 (Token 分布 / 成本分布 / 性能折线图)                  │  │
│  │  - 排行榜 (最慢 Tool / Token 消耗 / 失败次数)                     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

## 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| **探针 SDK** | Python 3.10+ | 多厂商 LLM 拦截（Provider Adapter 模式），contextvars 上下文传递 |
| **后端服务** | FastAPI | 异步框架，高并发 Ingestion |
| **消息中间件** | Kafka (KRaft) | 单机 Docker 版，高并发削峰，无需 Zookeeper |
| **数据存储** | ClickHouse | 列式数据库，5 张业务表 + 2 张聚合表 + 2 个物化视图 |
| **前端展示** | React 18 + TypeScript | Vite 构建，Recharts 图表，React Router v6 |
| **容器化** | Docker Compose | 一键启动 Kafka + ClickHouse |

## 项目结构

```
agent-observability/
├── docker-compose.yml              # Docker 编排 (Kafka + ClickHouse)
├── .env.example                    # 环境变量示例（API Key 等）
├── docker/
│   └── clickhouse/
│       └── init.sql                # ClickHouse 初始化 (5 业务表 + 2 聚合表 + 2 物化视图)
│
├── sdk/                            # Python 探针 SDK
│   ├── agent_insight_sdk/
│   │   ├── __init__.py             # 模块入口 (18 个公开 API)
│   │   ├── context.py              # TraceContext 上下文管理 (contextvars)
│   │   ├── interceptor.py          # [兼容保留] 原 OpenAIInterceptor (已别名到 LLMInterceptor)
│   │   ├── stream_monitor.py       # StreamMonitor 流式响应监控
│   │   ├── session_sdk.py          # SessionSDK 会话生命周期自动聚合
│   │   ├── tool_sdk.py             # ToolSDK 装饰器 (通用 / MCP / RAG)
│   │   ├── trace_api.py            # TraceAPI 显式 startTrace/startSpan/endSpan
│   │   ├── uploader.py             # AsyncBatchUploader + SpanData（5 种 span_type）
│   │   └── providers/              # Provider Adapter 模式（多厂商 LLM 拦截）
│   │       ├── base.py             # BaseProviderAdapter + LLMInterceptor + LLMCallRecord
│   │       ├── openai_compatible.py# OpenAI / DeepSeek / vLLM / Ollama 等
│   │       └── anthropic.py        # Anthropic Claude
│   ├── examples/
│   │   ├── example_simple_agent.py    # 阶段1：无埋点简单 Agent 示例
│   │   ├── example_sdk_demo.py        # 阶段3：SDK 完整功能示例（多厂商 + Tool + Trace + Session）
│   │   ├── example_mcp_rag_tools.py   # ToolSDK 进阶：MCP / RAG 装饰器示例
│   │   ├── example_custom_provider.py # 自定义 Provider Adapter 接入示例
│   │   └── example_rag_agent.py       # 端到端 RAG Agent 示例（全 Mock，无需 API Key）
│   ├── tests/
│   │   ├── conftest.py             # pytest 公共 fixture
│   │   ├── README.md               # 测试说明
│   │   ├── test_context.py         # TraceContext 单测
│   │   ├── test_providers.py       # Provider Adapter 单测
│   │   ├── test_session_sdk.py     # SessionSDK 单测
│   │   ├── test_stream_monitor.py  # StreamMonitor 单测
│   │   ├── test_tool_sdk.py        # ToolSDK 单测
│   │   ├── test_trace_api.py       # TraceAPI 单测
│   │   ├── test_uploader.py        # Uploader 单测
│   │   ├── test_span_data.py       # SpanData 序列化单测
│   │   ├── test_integration.py     # 集成测试
│   │   └── test_agent_simulation.py # 模拟 Agent 全链路测试
│   ├── setup.py
│   └── SDK_USAGE.md                # SDK 完整使用文档
│
├── backend/                        # FastAPI 后端服务
│   ├── app/
│   │   ├── main.py                 # FastAPI 入口 (8 个路由注册)
│   │   ├── config.py               # 配置管理
│   │   ├── api/                    # API 路由模块
│   │   │   ├── collect.py          # POST /api/v1/collect (5 种数据类型校验)
│   │   │   ├── traces.py           # GET /api/v1/traces + /sessions
│   │   │   ├── metrics.py          # GET /api/v1/metrics/compare
│   │   │   ├── prompts.py          # GET /api/v1/prompts + /tool-calls
│   │   │   └── leaderboard.py      # GET /api/v1/leaderboard (3 维度排行)
│   │   ├── kafka/
│   │   │   ├── producer.py         # Kafka 生产者
│   │   │   └── consumer.py         # Kafka 消费者 (按 span_type 分流 5 表)
│   │   └── clickhouse/
│   │       └── client.py           # ClickHouse 客户端 (5 insert + 6 query)
│   ├── tests/
│   │   └── test_api.py
│   └── requirements.txt
│
└── frontend/                       # React + TypeScript 前端
    ├── src/
    │   ├── main.tsx                # 入口
    │   ├── App.tsx                 # 路由 + 侧边栏 (7 个页面入口)
    │   ├── types.ts                # 全局类型定义 (15+ 接口)
    │   ├── index.css               # 深色主题样式
    │   └── pages/
    │       ├── TraceView.tsx        # 链路瀑布图
    │       ├── Timeline.tsx         # 时间线
    │       ├── PromptReplay.tsx     # Prompt 回放
    │       ├── SessionList.tsx      # Session 会话列表
    │       ├── MetricsCompare.tsx   # 模型效能对比
    │       ├── StatsDashboard.tsx   # 统计分析 (柱/饼/折线图)
    │       └── Leaderboard.tsx      # 排行榜
    ├── index.html
    ├── package.json
    ├── tsconfig.json
    ├── tsconfig.node.json
    ├── vite.config.ts
    └── README.md
```

## 快速开始

### 前置条件

| 工具 | 版本要求 | 检查命令 |
|------|---------|---------|
| Docker Desktop | 最新版（WSL2 后端） | `docker --version` |
| Python | 3.10 或更高 | `python --version` |
| Node.js | 18 或更高 | `node --version` |
| npm | 9 或更高（随 Node.js 附带） | `npm --version` |

> 如果 `python` 命令不可用，请尝试 `python3`；如果 `docker-compose` 不可用，请使用 `docker compose`（空格替换连字符）。

---

### 1. 启动基础设施（Kafka + ClickHouse）

在项目根目录执行：

```bash
docker-compose up -d
```

> 首次启动需要拉取镜像，约 3-5 分钟。如果遇到 WSL 报错，请确保 Docker Desktop 已切换到 WSL2 后端。

#### 1.1 等待容器就绪

```bash
# 查看容器状态，STATUS 应显示 Up（healthy 或 running）
docker ps
```

预期输出：

```
CONTAINER ID   IMAGE                              STATUS          PORTS
xxxxxxxxxx     confluentinc/cp-kafka:7.5.0         Up XX seconds   0.0.0.0:9092-9093->9092-9093/tcp
xxxxxxxxxx     clickhouse/clickhouse-server:23.8   Up XX seconds   0.0.0.0:8123->8123/tcp, 0.0.0.0:9000->9000/tcp
```

#### 1.2 验证 ClickHouse

```bash
curl http://localhost:8123/ping
# 预期输出: Ok
```

#### 1.3 确认数据表已自动创建

```bash
curl http://localhost:8123/?query=SHOW+TABLES
```

预期输出应包含 7 张表 + 2 个物化视图：

```
agent_traces
llm_metrics
model_stats_daily
model_stats_daily_mv
prompt_logs
sessions
tool_calls
tool_stats
tool_stats_mv
```

> 所有表和物化视图由 `docker/clickhouse/init.sql` 在容器首次启动时自动创建。如果未显示，请检查 Docker 日志：`docker logs agent-insight-clickhouse`

##### 已有库的升级迁移（v0.3.0 → v0.3.1）

`init.sql` 仅对**全新创建**的 ClickHouse 数据库生效。如果是从旧版本升级（已有数据），需手动执行以下 ALTER 语句补齐新增列：

```bash
# llm_metrics 新增 provider 列
curl 'http://localhost:8123/?query=ALTER+TABLE+llm_metrics+ADD+COLUMN+IF+NOT+EXISTS+provider+String+DEFAULT+%27%27+AFTER+model_name'

# tool_calls 新增 attributes 列
curl 'http://localhost:8123/?query=ALTER+TABLE+tool_calls+ADD+COLUMN+IF+NOT+EXISTS+attributes+String+DEFAULT+%27%7B%7D%27+AFTER+error'
```

或用 clickhouse-client 执行：

```sql
ALTER TABLE llm_metrics ADD COLUMN IF NOT EXISTS provider String DEFAULT '' AFTER model_name;
ALTER TABLE tool_calls  ADD COLUMN IF NOT EXISTS attributes String DEFAULT '{}' AFTER error;
```

> 两条语句均为幂等（`IF NOT EXISTS`），可重复执行。

#### 1.4 验证 Kafka

```bash
# 查看 Kafka 容器日志，确认启动成功
docker logs agent-insight-kafka 2>&1 | grep -i "started"
```

> Kafka 使用 KRaft 模式运行，无需 Zookeeper。Topic `agent-logs` 由 `KAFKA_AUTO_CREATE_TOPICS_ENABLE=true` 自动创建。

#### 遇到问题？

- **Docker 启动失败（WSL 报错）**：确保在 Docker Desktop Settings → General 中，WSL2 后端已启用
- **端口被占用**：修改 `docker-compose.yml` 中的端口映射
- **拉取镜像缓慢**：可在 Docker Desktop Settings → Docker Engine 中配置国内镜像源

---

### 2. 安装并验证 SDK

```bash
cd sdk
pip install -e .
```

> `-e` 表示可编辑安装，修改 SDK 代码无需重新安装。

#### 2.1 验证安装

```bash
python -c "from agent_insight_sdk import TraceContext, LLMInterceptor, ToolSDK, TraceAPI, SessionSDK, AsyncBatchUploader; print('SDK OK')"
```

预期输出：`SDK OK`

---

### 3. 启动后端服务

打开一个新的终端窗口：

```bash
cd backend
```

#### 3.1 创建虚拟环境（推荐）

```bash
# 创建虚拟环境
python -m venv venv

# 激活虚拟环境
# Windows:
venv\Scripts\activate
# macOS / Linux:
source venv/bin/activate
```

#### 3.2 安装依赖

```bash
pip install -r requirements.txt
```

依赖清单：`fastapi`、`uvicorn`、`aiokafka`、`clickhouse-driver`、`pydantic`、`pydantic-settings`、`httpx`

#### 3.3 启动服务

```bash
python -m uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

启动成功后应看到：

```
INFO:     Started server process [xxxxx]
INFO:     Waiting for application startup.
INFO:     Kafka producer initialized
INFO:     Kafka consumer started, topic: agent-logs
INFO:     Application startup complete.
INFO:     Uvicorn running on http://0.0.0.0:8000
```

#### 3.4 验证后端

```bash
# 健康检查
curl http://localhost:8000/health
# 预期输出: {"status":"ok"}

# 查看 API 文档（浏览器打开）
# http://localhost:8000/docs
```

#### 可能遇到的错误

- **`ModuleNotFoundError: No module named 'app.main'`**：确认当前目录是 `d:\agent-observability\backend`，且 `app/main.py` 存在
- **Kafka 连接失败**：确认 Docker 容器已启动（执行 `docker ps` 检查）
- **ClickHouse 写入失败（Table doesn't exist）**：确认 ClickHouse 容器已运行并且 init.sql 已执行

---

### 4. 启动前端

再打开一个新的终端窗口：

```bash
cd frontend
```

#### 4.1 安装依赖

```bash
npm install
```

> 首次安装约需 1-2 分钟。如果安装卡住，可以尝试 `npm install --legacy-peer-deps`。

#### 4.2 启动开发服务器

```bash
npm run dev
```

启动成功后会显示：

```
VITE v5.x.x  ready in xxx ms

  ➜  Local:   http://localhost:3000/
  ➜  Network: use --host to expose
```

#### 4.3 验证前端

浏览器访问 http://localhost:3000，应看到：

- 左侧深色侧边栏，显示 7 个导航菜单（链路跟踪 / 时间线 / Prompt 回放 / Session 会话 / 模型效能对比 / 统计分析 / 排行榜）
- 右侧主内容区，默认显示链路跟踪页面

> 前端通过 Vite 代理将 `/api` 请求转发到后端 `http://localhost:8000`，无需额外配置。

---

### 5. 运行示例验证全链路

回到项目根目录，再打开一个新终端：

```bash
cd sdk
```

#### 5.1 阶段1 示例（无埋点简单 Agent）

```bash
python examples/example_simple_agent.py
```

在终端中观察 Agent 执行流程日志（LLM 调用、Tool 计算、打印输出）。

#### 5.2 阶段3 示例（SDK 完整功能）

```bash
python examples/example_sdk_demo.py
```

观察输出中的 span 上报日志。该脚本会：

1. 演示 `LLMInterceptor` 多厂商 LLM 调用自动拦截（OpenAI / Anthropic / DeepSeek / Ollama）
2. 演示 `ToolSDK` 装饰器自动埋点 Tool 调用
3. 演示 `TraceAPI` 显式 startTrace/startSpan/endSpan
4. 演示 `SessionSDK` 自动聚合 Session 生命周期

> 未配置 API Key 时自动回退到模拟模式，无需真实 Key 即可观察埋点行为。

#### 5.3 进阶示例（无需 API Key，全 Mock）

```bash
# ToolSDK 进阶：MCP 协议工具 & RAG 检索自动埋点
python examples/example_mcp_rag_tools.py

# 自定义 Provider Adapter：接入非内置 LLM 厂商
python examples/example_custom_provider.py

# 端到端 RAG Agent：检索 → LLM → MCP 持久化完整链路
python examples/example_rag_agent.py
```

这三个示例全部使用 Mock，无需真实 LLM API Key 即可运行，启动后端后可在 Dashboard 看到完整 Trace 链路。

#### 5.4 模拟测试（发送模拟数据）

```bash
python tests/test_agent_simulation.py
```

该脚本会上报模拟的 Trace 和 Metrics 数据到后端。

#### 5.5 在 Dashboard 查看数据

回到浏览器 http://localhost:3000，依次查看：

| 页面 | 路由 | 应看到的内容 |
|------|------|------------|
| 链路跟踪 | `/` | 下拉框中选择一条 trace_id，查看瀑布图 |
| 时间线 | `/timeline` | 选中链路后看到按时间排列的 Span 卡片 |
| Prompt 回放 | `/prompt-replay` | LLM 的 Prompt/Response 和 Tool 调用记录 |
| Session 会话 | `/sessions` | Session 列表、汇总统计、单击可查看关联链路 |
| 模型效能对比 | `/metrics` | 多模型的 Prefill/Decode/TPS 柱状图 |
| 统计分析 | `/stats` | Token 分布 + 成本饼图 + 性能折线图 |
| 排行榜 | `/leaderboard` | 最慢 Tool / Token 消耗 / 失败次数排行 |

> 如果没有数据，请先执行 5.3 的模拟测试。如果后端未启动，前端页面会显示"加载中..."。

---

### 6. 一键终止

在各自的终端窗口中按 `Ctrl + C` 停止：

1. 前端 dev server（`npm run dev`）
2. 后端 uvicorn（`python -m uvicorn ...`）

停止 Docker 基础设施：

```bash
docker-compose down
```

> 如需保留数据，下次使用 `docker-compose up -d` 即可恢复。如需清理所有数据，执行 `docker-compose down -v`。

## API 接口一览

### 数据采集

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/v1/collect` | 接收 SDK 上报，支持 trace / llm_metrics / prompt / tool_call / session 五种 span_type，投递 Kafka 后立即返回 202 |

### 数据查询

| 方法 | 路径 | 参数 | 说明 |
|------|------|------|------|
| `GET` | `/api/v1/traces` | `trace_id`, `limit` | 链路追踪数据，传 trace_id 查看完整 Trace Tree |
| `GET` | `/api/v1/sessions` | `agent_name`, `limit` | Session 会话列表，支持按 Agent 名称筛选 |
| `GET` | `/api/v1/prompts` | `trace_id`, `limit` | Prompt / Response 回放记录 |
| `GET` | `/api/v1/tool-calls` | `trace_id`, `limit` | Tool 调用记录（输入/输出/耗时/状态） |
| `GET` | `/api/v1/metrics/compare` | `model_names`, `hours` | 多模型效能对比（Prefill / Decode / TPS / Cost） |
| `GET` | `/api/v1/leaderboard` | `metric`, `limit` | 排行榜：`slowest_tool` / `most_tokens` / `most_failed` |

## 数据库设计

ClickHouse 包含 5 张业务表 + 2 张聚合表 + 2 个物化视图：

| 表名 | 说明 | Engine |
|------|------|--------|
| `agent_traces` | 链路追踪 Span 数据 | MergeTree |
| `llm_metrics` | LLM 性能指标（prefill/decode/TPS/cost/provider） | MergeTree |
| `prompt_logs` | Prompt/Response 记录 | MergeTree |
| `tool_calls` | Tool 调用记录（含 MCP/RAG 元数据 attributes） | MergeTree |
| `sessions` | Agent Session 会话 | MergeTree |
| `model_stats_daily` | 模型按日聚合统计表（由 `model_stats_daily_mv` 物化视图写入） | AggregatingMergeTree |
| `tool_stats` | Tool 调用聚合统计表（由 `tool_stats_mv` 物化视图写入） | AggregatingMergeTree |

> `model_stats_daily` 和 `tool_stats` 使用 `AggregatingMergeTree` 配合 `avgState` / `maxState` 聚合函数状态存储，查询时通过 `avgMerge` / `maxMerge` 还原。avg / max 等非加性聚合不能用 SummingMergeTree（合并时会错误累加）。

## SDK 核心能力

```python
from agent_insight_sdk import (
    TraceContext,          # 上下文管理
    LLMInterceptor,        # 多厂商 LLM 统一拦截（自动识别 provider）
    StreamMonitor,         # 流式响应监控
    ToolSDK,               # Tool 自动埋点（通用 / MCP / RAG）
    TraceAPI,              # 显式 Trace API
    SessionSDK,            # Session 生命周期自动聚合
    AsyncBatchUploader,    # 异步批量上报
)
```

### Provider Adapter 模式

SDK 内置 Provider Adapter 模式，同一套 `LLMInterceptor.wrap(client)` API 支持所有主流 LLM 厂商：

| 厂商 | SDK | 协议 | Adapter |
|------|-----|------|---------|
| **OpenAI** | `pip install openai` | OpenAI | `OpenAICompatibleAdapter` |
| **DeepSeek** | `pip install openai` | OpenAI 兼容 | `OpenAICompatibleAdapter` |
| **vLLM** | `pip install openai` | OpenAI 兼容 | `OpenAICompatibleAdapter` |
| **Ollama** | `pip install openai` | OpenAI 兼容 | `OpenAICompatibleAdapter` |
| **Groq** | `pip install openai` | OpenAI 兼容 | `OpenAICompatibleAdapter` |
| **Together AI** | `pip install openai` | OpenAI 兼容 | `OpenAICompatibleAdapter` |
| **Anthropic** | `pip install anthropic` | Anthropic 专有 | `AnthropicAdapter` |
| **自定义** | 任意 | 任意 | 实现 `BaseProviderAdapter` |

```python
from openai import OpenAI
from agent_insight_sdk import LLMInterceptor, AsyncBatchUploader

uploader = AsyncBatchUploader()
await uploader.start()

interceptor = LLMInterceptor(uploader)

# 任何 OpenAI 兼容的客户端，一行代码拦截
client = OpenAI(api_key="sk-xxx", base_url="https://api.deepseek.com/v1")
client = interceptor.wrap(client)  # ← 自动识别

response = client.chat.completions.create(
    model="deepseek-chat",
    messages=[{"role": "user", "content": "Hello"}],
)
# 调用自动上报 trace + metrics + prompt 三类数据
```

### 各组件功能速览

| 组件 | 功能 |
|------|------|
| `TraceContext` | 基于 contextvars 的异步安全上下文，自动维护 trace_id / span_id / parent_span_id |
| `LLMInterceptor` | 多厂商 LLM 统一拦截器，`wrap(client)` 自动识别 Provider 并拦截所有 LLM 调用 |
| `StreamMonitor` | 监控流式响应的 chunk，精确计算 prefill_ms（首字耗时）和 decode_ms（生成耗时） |
| `ToolSDK` | 三种装饰器：`@instrument()`（通用）/ `@instrument_mcp()`（MCP 协议）/ `@instrument_rag()`（RAG 检索），自动记录 Tool 输入/输出/耗时/异常 |
| `TraceAPI` | 显式 `startTrace()` / `startSpan()` / `endSpan()` API，适用于手动控制链路场景 |
| `SessionSDK` | 自动聚合一次会话的总 Span 数 / 总 Token 数 / 总成本 / 总耗时，结束时上报 session span |
| `AsyncBatchUploader` | 有界队列（10000）+ 后台任务，每 500ms 或满 20 条自动批量上报，3 次指数退避重试 |

## 前端页面

| 页面 | 路由 | 功能 |
|------|------|------|
| 链路跟踪 | `/` | Span 瀑布图，可选中链路查看子 Span 层级 |
| 时间线 | `/timeline` | 按时间轴展示 Span，按类型着色（trace/llm/tool） |
| Prompt 回放 | `/prompt-replay` | 查看 LLM 的 Prompt/Response 和 Tool 调用记录 |
| Session 会话 | `/sessions` | Session 列表，汇总统计，支持点开查看关联链路 |
| 模型效能对比 | `/metrics` | 多模型 Prefill / Decode / TPS 柱状图 + 详细数据表 |
| 统计分析 | `/stats` | 汇总卡片 + Token 分布柱状图 + 成本饼图 + 性能折线图 |
| 排行榜 | `/leaderboard` | 最慢 Tool / Token 消耗 / 失败次数 三维度排行 |

## 开发路线图

本项目按照以下 6 个阶段逐步构建：

### 阶段1 - AI Agent 与可观测系统概述

- 使用 OpenAI API 搭建简单 Agent（用户输入 → LLM → 输出）
- 为 Agent 增加 Tool（计算器 / 天气查询）
- 打印执行流程日志，分析日志问题，引出可观测系统需求
- **示例**: `sdk/examples/example_simple_agent.py`

### 阶段2 - Trace 模型设计

- 设计 Trace / Span / Event 核心数据结构
- 为 Agent 每个步骤创建 Span（LLM / Tool / Memory）
- 实现 Parent Span → Child Span 调用树
- 自动计算耗时、状态、错误信息
- **实现**: `sdk/agent_insight_sdk/context.py`

### 阶段3 - SDK 自动埋点（多厂商支持）

- `LLMInterceptor` — 统一拦截器，`wrap(client)` 自动识别 Provider，支持 OpenAI / Anthropic / DeepSeek / vLLM / Ollama / Groq / Together AI
- 基于 Provider Adapter 模式，新增厂商只需继承 `BaseProviderAdapter` 并实现 `supports()` / `_wrap_call()` / `_unwrap_client()`
- `StreamMonitor` — 流式响应监控，精确计算 prefill_ms / decode_ms / TPS
- `ToolSDK` — 三种装饰器：`@instrument()`（通用）/ `@instrument_mcp()`（MCP）/ `@instrument_rag()`（RAG），自动记录 Tool 输入/输出/异常/耗时
- `TraceAPI` — 显式 `startTrace()` / `startSpan()` / `endSpan()` API
- `SessionSDK` — 自动聚合一次会话的总 Span / Token / 成本 / 耗时
- `AsyncBatchUploader` — 有界队列 + 异步批量上报至后端，3 次指数退避重试
- **示例**: `sdk/examples/example_sdk_demo.py`（真实 API 调用 + 多厂商）

### 阶段4 - Collector 与存储

- FastAPI Collector 服务，接收 5 种 span_type 并进行参数校验
- Kafka 消息中间件削峰，Consumer 按类型分流写入 5 张 ClickHouse 表
- 5 张业务表 + 2 张聚合表 + 2 个物化视图
- 提供查询接口：traces / sessions / prompts / tool-calls / metrics / leaderboard
- **实现**: `backend/app/api/` + `backend/app/kafka/consumer.py` + `backend/app/clickhouse/client.py`

### 阶段5 - Dashboard 可视化

- 7 个前端页面，React 18 + TypeScript + Recharts
- Trace Tree 瀑布图、Timeline 时间线、Prompt Replay
- Session 会话列表、多模型效能对比
- Token / Cost / Latency 统计图表（柱状图 + 饼图 + 折线图）
- Session / Agent / 模型 多维度筛选
- **实现**: `frontend/src/pages/`

### 阶段6 - 效能分析与排名

- 排行榜 API 三维度：最慢 Tool / Token 消耗 / 失败次数
- Tool 统计物化视图，自动聚合调用次数、耗时、错误率
- 模型统计物化视图，按日聚合请求数、Token 消耗、成本
- 全 TypeScript 前端 + 严格类型校验，零 tsc 错误
- **实现**: `backend/app/api/leaderboard.py` + `frontend/src/pages/Leaderboard.tsx`

## 开发进度

| 阶段 | 内容 | 状态 |
|------|------|------|
| 阶段1 | AI Agent 与可观测系统概述 | ✅ 完成 |
| 阶段2 | Trace 模型设计 | ✅ 完成 |
| 阶段3 | SDK 自动埋点 | ✅ 完成 |
| 阶段4 | Collector 与存储 | ✅ 完成 |
| 阶段5 | Dashboard 可视化 | ✅ 完成 |
| 阶段6 | 企业级能力与项目优化 | ✅ 完成 |

## License

MIT
