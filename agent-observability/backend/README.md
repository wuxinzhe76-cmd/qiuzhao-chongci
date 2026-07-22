# Agent Insight Backend

AI Agent 可观测性后端服务 —— 接收 SDK 上报的链路追踪和性能指标数据，经 Kafka 异步写入 ClickHouse，并提供查询 API。

## 架构

```
┌──────────┐   POST /api/v1/collect    ┌──────────────┐    produce     ┌───────┐
│   SDK    │ ──────────────────────────▶│   FastAPI    │ ─────────────▶│ Kafka │
└──────────┘                            │   Backend    │               └───┬───┘
                                        └──────────────┘                   │
                                               │                      consume
                                               │  GET /api/v1/*           │
                                               ▼                           ▼
                                        ┌──────────────┐           ┌────────────┐
                                        │   前端 / CLI  │◀──────────│ ClickHouse │
                                        └──────────────┘   query   └────────────┘
```

**数据流**：SDK → `POST /api/v1/collect` → Kafka Producer → Kafka → Kafka Consumer → ClickHouse（5 张表按类型分流）

## 项目结构

```
backend/
├── app/
│   ├── __init__.py
│   ├── main.py                  # FastAPI 应用入口
│   ├── config.py                # 配置管理（环境变量）
│   ├── api/
│   │   ├── __init__.py
│   │   ├── collect.py           # 数据收集 API
│   │   ├── traces.py            # 链路查询 & Session 查询 API
│   │   ├── metrics.py           # 多模型效能对比 API
│   │   ├── prompts.py           # Prompt 回放 & Tool 调用查询 API
│   │   └── leaderboard.py       # 排行榜 API
│   ├── kafka/
│   │   ├── __init__.py
│   │   ├── producer.py          # Kafka 生产者（异步投递）
│   │   └── consumer.py          # Kafka 消费者（分流写入 ClickHouse）
│   └── clickhouse/
│       ├── __init__.py
│       └── client.py            # ClickHouse 客户端（写入 + 查询）
├── conftest.py                  # pytest 共享 fixture（ASGI 客户端，免起服务）
├── tests/
│   ├── test_collect.py          # 数据收集 API + 字段校验
│   ├── test_consumer.py         # 消费者解析分流 + Token 成本计算
│   ├── test_clickhouse_client.py# ClickHouse 行构造/重试/查询模板
│   ├── test_producer.py         # Kafka 生产者生命周期 + 非阻塞投递
│   └── test_query_apis.py       # 6 个查询接口 + 参数边界 + 异常路径
└── requirements.txt
```

## 支持的 5 种数据类型

| span_type      | ClickHouse 表   | 说明                                   |
|----------------|-----------------|----------------------------------------|
| `trace`        | `agent_traces`  | 链路追踪 span（含 `attributes` 扩展属性） |
| `llm_metrics`  | `llm_metrics`   | LLM 性能指标（prefill/decode/TPS/`provider`） |
| `prompt`       | `prompt_logs`   | Prompt/Response 日志                   |
| `tool_call`    | `tool_calls`    | Tool 调用记录（含 `attributes`：MCP/RAG 元数据） |
| `session`      | `sessions`      | Session 会话聚合数据                    |

## API 接口

### 数据写入

| 方法 | 路径                | 说明                          |
|------|---------------------|------------------------------|
| POST | `/api/v1/collect`   | 接收 SDK 批量上报，投递至 Kafka |

返回 `202 Accepted` 表示数据已入队，实际持久化由 Consumer 异步完成。

### 数据查询

| 方法 | 路径                     | 参数                          | 说明               |
|------|--------------------------|-------------------------------|--------------------|
| GET  | `/api/v1/traces`         | `trace_id`, `limit`           | 链路追踪查询        |
| GET  | `/api/v1/sessions`       | `agent_name`, `limit`         | Session 会话列表    |
| GET  | `/api/v1/prompts`        | `trace_id`, `limit`           | Prompt 日志查询     |
| GET  | `/api/v1/tool-calls`     | `trace_id`, `limit`           | Tool 调用记录查询   |
| GET  | `/api/v1/metrics/compare`| `models`, `hours`             | 多模型效能对比       |
| GET  | `/api/v1/leaderboard`    | `metric`, `limit`             | 排行榜查询          |

### 健康检查

| 方法 | 路径         | 说明       |
|------|-------------|-----------|
| GET  | `/health`   | 服务健康检查 |

### 排行榜指标类型

| metric          | 说明                                  |
|-----------------|--------------------------------------|
| `slowest_tool`  | 最慢 Tool 调用（按平均耗时降序）        |
| `most_tokens`   | Token 消耗排行（按总 Token 数降序）     |
| `most_failed`   | 失败次数排行（按错误次数降序）          |

## 配置

通过环境变量或 `.env` 文件配置（使用 pydantic-settings）：

| 变量                       | 默认值                  | 说明              |
|---------------------------|------------------------|-------------------|
| `kafka_bootstrap_servers` | `localhost:9093`       | Kafka 地址         |
| `kafka_topic`             | `agent-logs`           | Kafka Topic       |
| `kafka_group_id`          | `agent-insight-consumer` | Consumer Group ID |
| `clickhouse_host`         | `localhost`            | ClickHouse 地址    |
| `clickhouse_port`         | `9000`                 | ClickHouse 端口    |
| `clickhouse_database`     | `default`              | 数据库名           |
| `clickhouse_user`         | `default`              | 用户名             |
| `clickhouse_password`     | `""`                   | 密码               |
| `backend_host`            | `0.0.0.0`              | 服务监听地址        |
| `backend_port`            | `8000`                 | 服务端口           |

示例 `.env` 文件：

```env
kafka_bootstrap_servers=localhost:9093
kafka_topic=agent-logs
clickhouse_host=localhost
clickhouse_port=9000
clickhouse_database=default
clickhouse_user=default
clickhouse_password=
```

## 安装与运行

### 前置依赖

- Python 3.10+
- Kafka（默认端口 9093）
- ClickHouse（默认端口 9000）

### 安装

```bash
cd backend
pip install -r requirements.txt
```

### 启动

```bash
cd backend
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

### 运行测试

测试基于 `pytest` + `pytest-asyncio`，通过 mock 隔离 Kafka / ClickHouse，无需真实中间件即可运行：

```bash
cd backend
pip install -r requirements.txt        # 已包含 pytest 与 pytest-asyncio
python -m pytest -v                     # 运行全部用例
python -m pytest tests/test_consumer.py # 运行单个模块
```

测试覆盖：
1. `test_collect.py` — `/health`、`/api/v1/collect` 5 种 span_type 上报、必填字段校验、空数组、Kafka 失败 500
2. `test_consumer.py` — 5 种 `parse_*` 分流与默认值、`calculate_cost` 定价矩阵（长 key 前缀优先 / 大小写 / 兜底价）
3. `test_clickhouse_client.py` — 行构造与默认值、指数退避重试、查询模板、排行榜分发
4. `test_producer.py` — Kafka 生产者生命周期、`send()` + 回调非阻塞投递、回调成功/失败分支
5. `test_query_apis.py` — 6 个查询接口、参数边界（422）、CSV models 解析、异常吞掉为 `status=error`

## Kafka 设计

### Pull 模型

Kafka 采用**消费者主动拉取（Pull）模型**，Broker 不会主动推送消息：

- **数据消费**：Consumer 通过 `poll()` 主动向 Broker 拉取消息，消费速度完全由 Consumer 控制，避免被压垮。
- **心跳维持**：Consumer 定时发送心跳，Broker 据此判断存活状态。长时间无心跳会被踢出 Consumer Group 并触发 Rebalance。
- **Rebalance 通知**：Consumer 加入/离开 Group 或 Partition 变化时，Group Coordinator 会**主动通知**所有 Consumer 触发 Rebalance —— 这是少数 Broker 主动推送的场景。
- **长轮询（Long Polling）**：`poll()` 本质是长轮询。没有数据时 Broker 会 hold 住连接最多 `fetch.max.wait.ms`（默认 500ms），期间有数据到达则立即返回，避免空轮询开销。

### Partition 数据模型

一个 Partition 的数据由 **Segment 文件 + 索引文件 + Broker 元信息** 组成：

```
topic-0/
├── 00000000000000000000.log        ← Segment 0 消息数据（顺序追加）
├── 00000000000000000000.index      ← Segment 0 偏移量索引
├── 00000000000000000000.timeindex  ← Segment 0 时间索引
├── 00000000000000005000.log        ← Segment 1 消息数据
├── 00000000000000005000.index
├── 00000000000000005000.timeindex
└── ...
```

每条消息的物理结构：`offset | timestamp | key | value | headers`

关键点：
- Commit Log 不是单一文件，而是由多个 Segment 组成（默认 1GB 或 7 天滚动）。
- **消费进度 offset 不存储在 Partition 自身**，而是存入 Kafka 内部 Topic `__consumer_offsets`，因此不同 Consumer Group 可以独立消费同一 Partition。
- Broker 侧元信息（Leader/Replica 分布、ISR 列表、Leader Epoch）存储在 ZooKeeper 或 KRaft 中，与数据文件分离。

### 分流消费

本项目的 Consumer 消费 Kafka 消息后，按 `span_type` 字段分流入 5 张 ClickHouse 表。达到批量阈值 50 条时刷新；`FLUSH_INTERVAL=5s` 仅作为 poll 超时唤醒循环（不强制刷新），未达阈值的残留数据在 Consumer 关闭时统一刷新。

### Token 成本计算

Consumer 内置主流模型的 Token 定价表（单位：USD / 1M tokens），写入 ClickHouse 时自动计算 `cost_usd`：

| 模型              | 输入 $/1M tokens | 输出 $/1M tokens |
|-------------------|-----------------|------------------|
| gpt-5.6-sol       | 5.00            | 30.00            |
| gpt-5.6-terra     | 2.50            | 15.00            |
| gpt-5.6-luna      | 1.00            | 6.00             |
| gpt-5.4           | 2.50            | 15.00            |
| gpt-5.4-mini      | 0.75            | 4.50             |
| gpt-5.4-nano      | 0.20            | 1.25             |
| gpt-4.1           | 2.00            | 8.00             |
| gpt-4.1-mini      | 0.40            | 1.60             |
| claude-opus-4-8   | 5.00            | 25.00            |
| claude-sonnet-5   | 3.00            | 15.00            |
| claude-haiku-4-5  | 1.00            | 5.00             |
| deepseek-chat     | 0.14            | 0.28             |
| deepseek-reasoner | 0.55            | 2.19             |

**匹配策略**：按 key 长度降序对 `model_name` 做前缀匹配，优先命中更具体的长名（`gpt-5.4-mini` 优先于 `gpt-5.4`，避免短 key 误匹配）。未匹配到的模型按兜底价 $1.00 / $2.00（输入/输出，per 1M tokens）计算。

> 价格表与 SDK 端 `agent_insight_sdk.session_sdk.DEFAULT_PRICING` 保持一致，修改任一处时请同步另一处。

### 容错设计

- **Kafka Producer**：使用 `send()` + 回调模式，不阻塞 FastAPI handler；SDK 侧有重试兜底
- **ClickHouse 写入**：指数退避重试（1s → 2s → 4s，最多 3 次），失败后丢弃并记录错误日志
- **数据校验**：Collector 按 `span_type` 校验必填字段，不合法数据直接返回 400

## 技术栈

| 组件        | 技术                         |
|------------|------------------------------|
| Web 框架    | FastAPI + Uvicorn            |
| 消息队列    | Kafka（aiokafka 异步客户端）   |
| 数据库      | ClickHouse（clickhouse-driver） |
| 配置管理    | pydantic-settings            |
| 测试        | pytest + pytest-asyncio + httpx（ASGI 直连，mock 隔离中间件） |
