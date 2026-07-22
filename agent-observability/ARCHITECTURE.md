# Agent-Insight 功能架构文档

---

## 项目定位

AI Agent 可观测性系统——从 SDK 自动埋点、数据采集、消息缓冲、列式存储到 Dashboard 可视化，覆盖全链路。

解决的问题：AI Agent 开发过程中，LLM 调用延时多少？哪个模型最快最省钱？Tool 调用有没有瓶颈？一次性看所有 Trace、Metrics、Prompt。

---

## 系统架构

```
                     ┌─────────────────────────┐
                     │    用户 Agent 代码        │
                     │  OpenAI / Tool / Memory   │
                     └────────────┬────────────┘
                                  │ wrap() / @instrument / start_trace()
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                       SDK 探针层                                 │
│                                                                 │
│  TraceContext  ◄── contextvars 管理 trace_id / span_id          │
│  LLMInterceptor  ◄── Provider Adapter 模式（多厂商）             │
│  StreamMonitor  ◄── 流式 prefill / decode / TPS 计算            │
│  ToolSDK  ◄── @instrument 装饰器，自动记录 Tool 调用             │
│  TraceAPI  ◄── 显式 start_trace / start_span / end_span         │
│         │                                                       │
│         └──────────► AsyncBatchUploader                         │
│                      Queue(maxsize=10000)                       │
│                      每 20 条 / 500ms 批量上报                   │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  │ HTTP POST /api/v1/collect
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                   FastAPI Collector（v0.3.0）                    │
│                                                                 │
│  /api/v1/collect  ← 接收 JSON 数组，5 种 span_type 校验          │
│         │                                                       │
│         │ 校验通过 → 202 Accepted                               │
│         │                                                       │
│         ▼                                                       │
│  Kafka Producer  ← send() + callback（非阻塞，gzip 压缩）       │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│              Kafka（KRaft 模式，无 Zookeeper）                   │
│                                                                 │
│  Topic: agent-logs                                              │
│  削峰填谷 / 解耦上下游 / 故障缓冲                                │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  │ Consumer 批量拉取
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        Kafka Consumer                               │
│                                                                     │
│  consume_loop() → 按 span_type 分流                                 │
│    trace       → agent_traces（MergeTree）                          │
│    llm_metrics → llm_metrics（MergeTree）                           │
│    prompt      → prompt_logs（MergeTree）                           │
│    tool_call   → tool_calls（MergeTree）                            │
│    session     → sessions（MergeTree）                              │
│                                                                     │
│  攒够 50 条或每 5 秒强制刷新  /  失败指数退避重试（1s → 2s → 4s）   │
└─────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│              ClickHouse（列式存储，高压缩比）                        │
│                                                                     │
│  5 张业务表 + 2 张聚合表 + 2 个物化视图（自动聚合按模型/按天/按工具）│
└─────────────────────────────────────────────────────────────────────┘
                                  │
                                  │ JSON REST API
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   React 18 前端（7 个页面）                          │
│                                                                     │
│  链路瀑布图 / 时间线 / Prompt 回放 / Session 列表                   │
│  模型效能对比 / 统计仪表盘 / 排行榜                                  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 模块划分

### SDK（`sdk/`）

Python 探针库，非侵入式采集 AI Agent 的运行数据。

| 模块 | 文件 | 职责 |
|------|------|------|
| TraceContext | `context.py` | 用 `contextvars` 管理 trace_id、span_id、parent_span_id，异步安全 |
| LLMInterceptor | `providers/base.py` | 统一拦截入口，自动扫描匹配的 Adapter |
| Provider Adapter | `providers/openai_compatible.py` | 拦截 `client.chat.completions.create`，兼容 OpenAI / DeepSeek / vLLM / Ollama / Groq / Together AI |
| Provider Adapter | `providers/anthropic.py` | 拦截 `client.messages.create`，适配 Claude 数据结构 |
| StreamMonitor | `stream_monitor.py` | 流式响应计时，计算 prefill_ms / decode_ms / TPS |
| ToolSDK | `tool_sdk.py` | 三种装饰器：`@instrument`（通用）/ `@instrument_mcp`（MCP）/ `@instrument_rag`（RAG），自动记录 Tool 调用耗时和输入输出 |
| TraceAPI | `trace_api.py` | 显式 API：start_trace / start_span / end_span / end_trace |
| SessionSDK | `session_sdk.py` | 自动聚合一次会话的总 Span / Token / 成本 / 耗时，结束时上报 session span |
| Uploader | `uploader.py` | 有界队列（maxsize=10000），批量上报，3 次指数退避重试，支持 observer 回调 |

**数据上报类型（5 种 span_type）：**

| span_type | 说明 | 对应表 |
|-----------|------|--------|
| trace | 调用链路 Span | agent_traces |
| llm_metrics | LLM 性能指标 | llm_metrics |
| prompt | Prompt/Response 记录 | prompt_logs |
| tool_call | Tool 调用记录 | tool_calls |
| session | 会话汇总 | sessions |

### 后端（`backend/`）

FastAPI 服务，负责数据接入、Kafka 投递、Kafka 消费、ClickHouse 读写。

| 模块 | 文件 | 职责 |
|------|------|------|
| 入口 | `app/main.py` | FastAPI 应用，CORS，注册路由，启动/关闭钩子 |
| 配置 | `app/config.py` | 环境变量读取（Kafka、ClickHouse、Host） |
| 采集 API | `app/api/collect.py` | POST /api/v1/collect，校验后投递 Kafka |
| 查询 API | `app/api/traces.py` | GET /traces、/sessions |
| 查询 API | `app/api/metrics.py` | GET /metrics/compare（模型对比） |
| 查询 API | `app/api/prompts.py` | GET /prompts、/tool-calls |
| 查询 API | `app/api/leaderboard.py` | GET /leaderboard（3 个维度） |
| Kafka 生产 | `app/kafka/producer.py` | send() 非阻塞投递，gzip 压缩 |
| Kafka 消费 | `app/kafka/consumer.py` | 批量消费，按类型分流写入 ClickHouse |
| ClickHouse | `app/clickhouse/client.py` | 5 个写入函数 + 6 个查询函数 |

### 前端（`frontend/`）

React 18 + TypeScript + Vite，深色主题，7 个页面。

| 页面 | 文件 | 功能 |
|------|------|------|
| 链路瀑布图 | `TraceView.tsx` | 查看 Trace 内所有 Span 的层级关系、耗时占比 |
| 时间线 | `Timeline.tsx` | 按时间顺序展示 Span，不同颜色区分类型 |
| Prompt 回放 | `PromptReplay.tsx` | 还原 LLM 对话，展示 Prompt/Response/Tool 调用 |
| Session 列表 | `SessionList.tsx` | 查看所有会话，汇总信息，展开查看关联 Trace |
| 模型对比 | `MetricsCompare.tsx` | 多模型 Prefill/Decode/TPS/成本 柱状图 |
| 统计仪表盘 | `StatsDashboard.tsx` | 汇总卡片 + Token 分布 + 成本饼图 + 性能趋势 |
| 排行榜 | `Leaderboard.tsx` | 最慢工具 / 最多 Token / 最多失败 三个维度 |

---

## 数据流

```
Agent 代码
  │
  │ LLMInterceptor.wrap(client) / @tool_sdk.instrument() / trace_api.start_trace()
  ▼
SDK 采集层
  │  TraceContext 管理上下文
  │  StreamMonitor 计时
  │  SessionSDK 聚合会话指标
  │  SpanData 序列化
  ▼
AsyncBatchUploader
  │  有界队列缓冲
  │  每 20 条 / 500ms 批量 POST
  ▼
FastAPI Collector
  │  校验必填字段
  │  Kafka send() → 202 Accepted
  ▼
Kafka（KRaft 模式）
  │  topic: agent-logs
  │  削峰填谷、解耦
  ▼
Kafka Consumer
  │  按 span_type 分流
  │  50 条一批或 5 秒强制刷新
  ▼
ClickHouse
  │  5 张基础表 + 2 个物化视图
  │  列式存储，高压缩比
  ▼
FastAPI Query API
  │  JSON REST 接口
  ▼
React 前端
  │  charts / tables / waterfall
  ▼
用户浏览器
```

---

## 数据库设计

### 业务表

**agent_traces** —— 链路 Span

| 字段 | 类型 | 说明 |
|------|------|------|
| trace_id | String | 链路 ID |
| span_id | String | Span ID |
| parent_span_id | String | 父 Span ID |
| name | String | Span 名称 |
| start_time | DateTime64(3) | 开始时间 |
| end_time | DateTime64(3) | 结束时间 |
| attributes | String | 属性 JSON |

**llm_metrics** —— LLM 性能指标

| 字段 | 类型 | 说明 |
|------|------|------|
| trace_id | String | 关联链路 |
| span_id | String | 关联 Span |
| model_name | String | 模型名 |
| provider | String | LLM 厂商标识（openai-compatible / anthropic 等） |
| prefill_ms | Float64 | 首 token 延迟 |
| decode_ms | Float64 | 生成阶段耗时 |
| input_tokens | UInt32 | 输入 token 数 |
| output_tokens | UInt32 | 输出 token 数 |
| tps | Float64 | token/秒 |
| cost_usd | Float64 | 费用（USD，由后端按价格表计算） |

**prompt_logs** —— Prompt/Response 日志

| 字段 | 类型 | 说明 |
|------|------|------|
| trace_id | String | 关联链路 |
| span_id | String | 关联 Span |
| model_name | String | 模型名 |
| prompt | String | 用户 Prompt |
| response | String | 模型 Response |
| input_tokens | UInt32 | 输入 token |
| output_tokens | UInt32 | 输出 token |
| latency_ms | Float64 | 总耗时 |
| stream | UInt8 | 是否流式 |
| status | String | success / error |
| error | String | 错误信息 |

**tool_calls** —— Tool 调用记录

| 字段 | 类型 | 说明 |
|------|------|------|
| trace_id | String | 关联链路 |
| span_id | String | 关联 Span |
| tool_name | String | Tool 名称 |
| tool_type | String | Tool 类型（generic / mcp / rag） |
| input_data | String | 输入参数 JSON |
| output_data | String | 返回结果 JSON |
| duration_ms | UInt32 | 耗时 |
| status | String | success / error |
| error | String | 错误信息 |
| attributes | String | 扩展属性 JSON（MCP/RAG 元数据：mcp_server、rag_vector_db 等） |

**sessions** —— 会话汇总

| 字段 | 类型 | 说明 |
|------|------|------|
| session_id | String | 会话 ID |
| trace_id | String | 关联链路 |
| agent_name | String | Agent 名 |
| user_input | String | 用户输入 |
| final_response | String | 最终回复 |
| total_spans | UInt32 | 总 Span 数 |
| total_tokens | UInt32 | 总 Token 数 |
| total_cost_usd | Float64 | 总费用 |
| duration_ms | UInt32 | 总耗时 |
| status | String | completed / error |

### 聚合表与物化视图

- **model_stats_daily**：按模型+日期聚合，`AggregatingMergeTree`，通过 `model_stats_daily_mv` 物化视图写入；avg 用 `avgState` 存储、查询时 `avgMerge` 还原，token 和成本自动累加
- **tool_stats**：按工具+日期聚合，`AggregatingMergeTree`，通过 `tool_stats_mv` 物化视图写入；avg/max 用 `avgState` / `maxState` 存储，调用次数和错误数自动累加

> avg / max 等非加性聚合不能用 SummingMergeTree（合并时会错误累加），必须使用 AggregatingMergeTree + State/Merge 函数。

---

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/collect | 数据采集入口，接收 span 数组，返回 202 |
| GET | /api/v1/traces | 查询链路，支持 `?trace_id=` |
| GET | /api/v1/sessions | 查询会话，支持 `?agent_name=` |
| GET | /api/v1/prompts | 查询 Prompt 日志，支持 `?trace_id=` |
| GET | /api/v1/tool-calls | 查询 Tool 调用，支持 `?trace_id=` |
| GET | /api/v1/metrics/compare | 多模型对比，支持 `?models=` `?hours=` |
| GET | /api/v1/leaderboard | 排行榜，支持 `?metric=`（slowest_tool/most_tokens/most_failed） |
| GET | /health | 健康检查 |

---

## 基础设施

| 组件 | 镜像 | 端口 | 说明 |
|------|------|------|------|
| Kafka | confluentinc/cp-kafka:7.5.0 | 9093 | KRaft 模式，无需 Zookeeper |
| ClickHouse | clickhouse/clickhouse-server:23.8 | 8123/9000 | HTTP / Native 双协议 |

启动命令：

```bash
docker-compose up -d
```

### ClickHouse 表结构升级迁移

`docker/clickhouse/init.sql` 仅在容器**首次启动**（数据卷为空）时执行。已有数据的库升级到新版表结构（v0.3.0 → v0.3.1，新增 `llm_metrics.provider` 与 `tool_calls.attributes` 列）需手动执行：

```sql
ALTER TABLE llm_metrics ADD COLUMN IF NOT EXISTS provider String DEFAULT '' AFTER model_name;
ALTER TABLE tool_calls  ADD COLUMN IF NOT EXISTS attributes String DEFAULT '{}' AFTER error;
```

> 两条语句均幂等（`IF NOT EXISTS`），可重复执行。如需重建库，执行 `docker-compose down -v` 清空数据卷后重新 `up`，init.sql 会自动建表。

---

## 技术栈

| 层 | 技术 | 版本 |
|----|------|------|
| SDK | Python + HTTPX | 3.10+ |
| 后端 | FastAPI + Uvicorn | 0.3.0 |
| 消息队列 | Kafka（KRaft） | 7.5.0 |
| 存储 | ClickHouse | 23.8 |
| 前端 | React + TypeScript + Vite | 18 |
| 图表 | Recharts | |
| 容器化 | Docker + Docker Compose | |

---

## 项目结构

```
agent-observability/
├── docker-compose.yml           # Kafka + ClickHouse
├── .env.example                 # 环境变量示例（API Key 等）
├── ARCHITECTURE.md              # 本文档
├── README.md                    # 项目说明
├── docker/
│   └── clickhouse/init.sql      # 建表 + 物化视图
├── docs/                        # 基础设施指南
│   ├── clickhouse_guide.md
│   ├── kafka_guide.md
│   └── docker_guide.md
├── sdk/                         # Python 探针 SDK
│   ├── setup.py
│   ├── SDK_USAGE.md             # SDK 完整使用文档
│   ├── agent_insight_sdk/
│   │   ├── __init__.py          # 模块入口（18 个公开 API）
│   │   ├── context.py           # TraceContext
│   │   ├── interceptor.py       # [兼容保留] OpenAIInterceptor 别名
│   │   ├── providers/           # Provider Adapter 模式
│   │   │   ├── base.py          # BaseProviderAdapter + LLMInterceptor
│   │   │   ├── openai_compatible.py
│   │   │   └── anthropic.py
│   │   ├── stream_monitor.py    # 流式计时
│   │   ├── session_sdk.py       # Session 自动聚合
│   │   ├── tool_sdk.py          # Tool 装饰器（通用/MCP/RAG）
│   │   ├── trace_api.py         # 显式 API
│   │   └── uploader.py          # 批量上报器
│   ├── examples/                # 示例集
│   │   ├── example_simple_agent.py    # 阶段1：无埋点简单 Agent
│   │   ├── example_sdk_demo.py        # SDK 完整功能（多厂商 + Tool + Trace + Session）
│   │   ├── example_mcp_rag_tools.py   # ToolSDK 进阶：MCP / RAG 装饰器
│   │   ├── example_custom_provider.py # 自定义 Provider Adapter 接入
│   │   └── example_rag_agent.py       # 端到端 RAG Agent（全 Mock）
│   └── tests/                   # 单测 + 集成测试
├── backend/                     # FastAPI 服务
│   ├── requirements.txt
│   ├── README.md
│   └── app/
│       ├── main.py              # 入口
│       ├── config.py            # 配置
│       ├── api/                 # REST 接口
│       │   ├── collect.py       # 数据采集
│       │   ├── traces.py        # 链路查询
│       │   ├── metrics.py       # 模型对比
│       │   ├── prompts.py       # Prompt 查询
│       │   └── leaderboard.py   # 排行榜
│       ├── kafka/
│       │   ├── producer.py      # 非阻塞投递
│       │   └── consumer.py      # 批量消费
│       └── clickhouse/
│           └── client.py        # 读写客户端
└── frontend/                    # React 前端
    ├── vite.config.ts
    ├── README.md
    └── src/
        ├── types.ts             # 类型定义
        ├── App.tsx              # 路由 + 布局
        └── pages/
            ├── TraceView.tsx    # 瀑布图
            ├── Timeline.tsx     # 时间线
            ├── PromptReplay.tsx # 对话回放
            ├── SessionList.tsx  # 会话列表
            ├── MetricsCompare.tsx # 模型对比
            ├── StatsDashboard.tsx # 统计仪表盘
            └── Leaderboard.tsx  # 排行榜
```

---

## 关键设计决策

### 为什么用 Kafka

FastAPI 收到 SDK 上报数据后，直接投递 Kafka，立刻返回 202。不等 ClickHouse 写完。这样做的好处：
- ClickHouse 挂了，数据暂存 Kafka，恢复后继续消费
- 以后加告警、分析服务，新增 Consumer Group 就行，不用改上游代码
- 流量突发时，Kafka 缓冲，Consumer 按自己的节奏写 ClickHouse

### 为什么用 ClickHouse

LLM 可观测数据是"写多读少、聚合分析为主"的场景。ClickHouse 列式存储在分析查询上比 MySQL 快几十倍。比如查"各模型的平均 prefill 耗时"，MySQL 要扫 1000 万行所有字段，ClickHouse 只读 `model_name` 和 `prefill_ms` 两列。

### Provider Adapter 模式

SDK 不写死某个 LLM 厂商。新增厂商只需继承 `BaseProviderAdapter` 并实现 `supports()`、`_wrap_call()`、`_unwrap_client()`（`extract()` 有默认实现，OpenAI 兼容格式可直接复用）。目前已支持 OpenAI 兼容厂商（OpenAI、DeepSeek、vLLM、Ollama、Groq、Together AI）和 Anthropic（Claude）。

### 背压保护

SDK 的上报队列设置了 10000 条上限。满了就丢弃并告警，不阻塞 Agent 业务代码。SDK 侧和后端 Consumer 侧都有指数退避重试（1s → 2s → 4s，最多 3 次），数据可靠性有双层保障。
