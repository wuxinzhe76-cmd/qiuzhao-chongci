# Agent 可观测性后端 · 学习笔记 01：整体架构与 config.py

> 📅 学习日期：2026-06-29
> 🎯 目标：最小颗粒度过一遍后端代码，先建全局认知，再钻细节
> 👤 背景：Java 后端转 AI Agent 方向，需对照 Java 生态理解 Python 机制

---

## 一、项目是干什么的

一句话：给 AI Agent 装一个"行车记录仪 + 体检报告"。

Agent 跑起来会调 LLM、调 Tool、多步推理，一旦出错或变慢，你不知道是哪一步出问题。这个系统把 Agent 跑的每一步都记录下来、存起来、让你能查出来看。行话叫 **Observability（可观测性）**。

本项目是 [LangFuse](https://langfuse.com) / [LangSmith](https://docs.smith.langchain.com) 的开源自建简化版：后端代码全在自己手里，数据不外泄。

---

## 二、技术栈三件套

| 技术 | 角色 | 类比 |
|------|------|------|
| **FastAPI** | Web 服务入口（收发室） | Spring Boot |
| **Kafka** | 消息队列（传送带，缓冲削峰） | Kafka |
| **ClickHouse** | 列式数据库（仓库，存数据跑分析） | 不完全等于 MySQL，是 OLAP 分析型库 |

---

## 三、数据流（最重要）

```
        ┌───────────┐
        │  SDK      │  ← 装在 Agent 进程内，拦截每次 LLM/Tool 调用
        └─────┬─────┘
              │ HTTP POST /api/v1/collect（一批 span）
              ▼
        ┌───────────┐
        │ FastAPI   │  ① 校验数据格式
        │ (collect) │  ② 丢给 Kafka  ③ 立即返回 202 Accepted
        └─────┬─────┘
              │ 异步投递
              ▼
        ┌───────────┐
        │  Kafka    │  消息队列，缓冲削峰（Agent 不用等写库）
        └─────┬─────┘
              │ 异步消费
              ▼
        ┌───────────┐
        │ Consumer  │  ① 按类型分流  ② 攒到 50 条或 5 秒  ③ 批量写入
        └─────┬─────┘
              ▼
        ┌───────────┐
        │ ClickHouse│  存到 5 张表
        └───────────┘
              ▲
              │ SELECT（查询直接打 ClickHouse，不走 Kafka）
        ┌─────┴─────┐
        │ FastAPI   │  ← /traces /sessions /metrics/compare /prompts /leaderboard
        └───────────┘
```

### 为什么不直接 FastAPI → ClickHouse，非要塞 Kafka

- 直接写库要几十到几百毫秒，Agent 同步等写库会被拖慢——可观测性反而成性能瓶颈。
- Kafka 好处：**解耦**（Agent 扔进 Kafka 就走）、**削峰**（突发 1 万条先攒着）、**批量写入**（Consumer 攒 50 条一起写，比一条一条写快得多）。
- 代价：数据有延迟（几秒），但可观测性场景容忍。

### 核心认知：Kafka 是传送带，不是仓库

Kafka 是消息队列，按 retention（默认几天）自动删消息，**不能当数据库用**。数据最终要落到 ClickHouse 才能查。查询接口完全不走 Kafka，直接读 ClickHouse。

### 两条链路要分清

- **写入链路（收集）**：collect.py → producer.py → Kafka → consumer.py → client.py(insert_*)
- **读取链路（查询）**：traces.py 等 → client.py(query_*) → ClickHouse

---

## 四、数据分 5 类，存 5 张表

Agent 上报的数据按 `span_type` 字段分 5 种，各存各的表：

| span_type | 表 | 记什么 |
|-----------|-----|--------|
| `trace` | `agent_traces` | 链路骨架（谁是谁的父 span） |
| `llm_metrics` | `llm_metrics` | LLM 性能指标（prefill/decode 耗时、TPS、token、成本） |
| `prompt` | `prompt_logs` | Prompt 原文 + Response 原文（用于 replay） |
| `tool_call` | `tool_calls` | 工具调用记录（入参、出参、耗时） |
| `session` | `sessions` | 一次完整会话的汇总 |

**span 概念**：一次 Agent 执行拆成多个步骤，每个步骤叫一个 span，所有 span 通过 `trace_id` 串成一棵树，`parent_span_id` 指向父步骤。这是 OpenTelemetry 的核心概念。

**易错点：一次 LLM 调用存几张表？**
答：主要 3 张 —— trace + llm_metrics + prompt_logs。tool_calls 只有触发 tool calling 才写；sessions 是会话级汇总，一次完整会话结束才写一条，不是每次 LLM 调用都写。

---

## 五、核心概念澄清

### 1. SDK 是啥

SDK = Software Development Kit = 软件开发包。**不是独立进程，是别人写好的代码包，你 import 进 Agent 进程用**。跟你的业务代码跑在同一个进程里，帮你干脏活（发请求、重试、记录、上报）。

**SDK 是代码层面的客户端**，不是带 UI 的客户端程序。类比 Java 的 JDBC 驱动、`openai-java` 包。

### 2. 程序类型

| 类型 | 例子 | 是不是独立进程 |
|------|------|--------------|
| 带 UI 的程序 | 浏览器、微信 App | 是 |
| 不带 UI 的后端程序 | FastAPI、Spring Boot | 是 |
| SDK | openai 包、JDBC 驱动 | **否**，嵌在别的进程里 |

"有 UI 界面的后端程序"是伪命题——界面归前端，后台归后端，两个程序。

### 3. SaaS 是啥

SaaS = Software as a Service = 软件即服务。软件放云上租给你用，注册账号就用，按月/按人付费。典型：飞书、钉钉、Notion、Salesforce、LangFuse、Snowflake。

SDK 和 SaaS 的关系：SaaS 公司通常提供 SDK 让客户方便接入。你用他们的 SDK，数据就流到他们的 SaaS 平台。本项目相当于你**既当 SaaS 公司（自建后端），又当自己的客户（用 SDK）**。

### 4. 拦截 LLM 调用两种模式

| 模式 | 改不改业务代码 | 例子 |
|------|--------------|------|
| **手动埋点** | 要改（换接口） | 本项目 SDK：把 `from openai import OpenAI` 换成 `from agent_insight_sdk import OpenAICompatibleClient` |
| **自动埋点** | 不改 | OTel Java Agent（字节码注入）、Spring AI Advisor（AOP） |

**可观测性通常要改代码的原因**：业务调用（调 LLM）分散在代码里、没标准入口，框架不知道你在哪调了 LLM。Druid 看着不改代码，其实偷偷代理了 JDBC 层（有标准接口 DataSource）。

### 5. Java SDK 设计设想（包装 Spring AI ChatClient）

最佳方案：包装 Spring AI 的 `ChatClient`，靠自动装配 + Advisor 机制实现"加依赖+配置即生效"。原理跟 Druid 代理 DataSource 一样——**有标准接口的地方，就能无感代理**。前提是用户走 Spring AI；用原生 OpenAI SDK 的拦不到。

---

## 六、config.py 逐行讲解

### 1. 文件作用

集中管理整个后端的所有配置项（Kafka 地址、ClickHouse 地址、端口）。所有别处 `from .config import settings` 共享同一个单例。相当于 Spring 的 `@ConfigurationProperties` + `application.yml` 合体。

### 2. 为什么不写死在代码里

- 环境差异（本地 localhost，生产 kafka-prod.internal）
- 敏感信息（密码不能进 git）
- 不改代码改配置（改 .env 重启就行）

### 3. 导入

```python
from pydantic_settings import BaseSettings
```

`pydantic-settings` 是 Pydantic v2 的配置管理库，把环境变量 / .env 文件 / 默认值自动合并成一个 Python 对象。

**Pydantic 是啥**：Python 生态最流行的数据校验库，类似 Java 的 Bean Validation（JSR-303，`@Valid`、`@NotNull`）。两个产品：`pydantic`（核心校验，类比 Hibernate Validator）、`pydantic-settings`（管配置，类比 `@ConfigurationProperties`）。

### 4. 配置项三组

**Kafka 配置**：
- `kafka_bootstrap_servers` = `localhost:9093`：Kafka 地址。bootstrap = 引导，客户端先连这个地址拿到集群所有 broker 列表。
- `kafka_topic` = `agent-logs`：所有 Agent 日志发到这个 topic。
- `kafka_group_id` = `agent-insight-consumer`：消费者组 ID，同组多个 consumer 分摊消费不重复。

**端口 9093 的坑**：docker-compose 里 Kafka 配两个端口：
- `9092`：容器内部通信用（PLAINTEXT://kafka:9092）
- `9093`：宿主机访问用（EXTERNAL://localhost:9093）
本地开发连 9093，容器之间互连用 kafka:9092。

**ClickHouse 配置**：
- `clickhouse_port` = `9000`：**Native 协议端口**（clickhouse-driver 用这个）。
- 还有个 `8123`：HTTP 协议端口（浏览器、curl 用）。**别搞混**。

**服务配置**：
- `backend_host` = `0.0.0.0`：监听所有网卡。`0.0.0.0` vs `127.0.0.1`：前者别人能访问，后者只本机。
- `backend_port` = `8000`。

### 5. 内部 Config 类

```python
class Config:
    env_file = ".env"
```

Pydantic v1 老写法，告诉它还从 `.env` 文件读配置。v2 推荐用 `model_config = SettingsConfigDict(env_file=".env")`，本项目是老写法（兼容但有 DeprecationWarning，小瑕疵）。

**读取优先级**（从低到高）：① 代码默认值 → ② .env 文件 → ③ 系统环境变量。

### 6. 实例化

```python
settings = Settings()
```

模块级变量，Python import 时只执行一次，全局共享同一个对象。Python 单例最朴素实现。

---

## 七、Pydantic Settings 机制详解（重点）

### 1. 默认值 ≠ 写死

代码里 `kafka_bootstrap_servers: str = "localhost:9093"` 的 `= "localhost:9093"` 是**默认值（兜底）**，不是最终值。环境变量一旦存在，立刻覆盖它。

查找逻辑：
```
① 系统环境变量有 KAFKA_BOOTSTRAP_SERVERS 吗？有 → 用它
② .env 文件有吗？有 → 用它
③ 都没有 → 用代码默认值
```

### 2. 字段名 → 环境变量名映射（大小写不敏感）

**官方文档原文**：By default, environment variable names are case-insensitive.

机制是**大小写不敏感匹配**，不是"自动转大写"：
- 字段 `kafka_bootstrap_servers`（小写）
- 环境变量 `KAFKA_BOOTSTRAP_SERVERS`（大写）✅ 能匹配
- 环境变量 `kafka_bootstrap_servers`（小写）✅ 也能匹配

映射规则：环境变量名 = `env_prefix`（前缀，默认空）+ 字段名。本项目没配前缀，所以字段名就是环境变量名。

可加前缀避免撞名：`SettingsConfigDict(env_prefix="AGENT_")` → 环境变量变成 `AGENT_KAFKA_BOOTSTRAP_SERVERS`。

### 3. Java 类比：Spring 松散绑定

Spring `@ConfigurationProperties` 的松散绑定更强：`kafka.bootstrap-servers` / `kafka.bootstrapServers` / `KAFKA_BOOTSTRAP_SERVERS` / `kafka_bootstrap_servers` 都能匹配同一字段。Pydantic 只做了"大小写不敏感"，没做"分隔符归一化"，比 Spring 弱但够用。

### 4. 类型注解（str/int）的作用

**关键认知：环境变量读出来永远是字符串**（操作系统和 Docker 只认字符串）。

`clickhouse_port: int = 9000` 的 `: int` 含义：
- **不是规定 env 里必须写 int**（env 里只能写字符串）
- **是告诉 Pydantic：读到字符串后按 int 转换 + 校验**
- 转换失败（如 `CLICKHOUSE_PORT=abc`）→ 启动时直接抛 ValidationError，程序起不来

**Python 是动态类型，不校验类型**：`port = 9000; port = "abc"` Python 运行时不报错。Pydantic 帮你补上运行时校验，启动时崩而不是运行时崩。

**Java 类比**：Java 静态类型编译期就检查；Pydantic 在启动时（运行时）检查。`BaseSettings` = `@ConfigurationProperties` + `@Valid` 一起的效果。

---

## 八、生产部署：Docker 网络大坑

### 问题：Docker 里写 localhost 连别的容器行不行？

**不行！** Docker 每个容器有独立的网络空间。容器里的 `localhost` 指的是**容器自己**，不是宿主机，也不是别的容器。FastAPI 容器写 `localhost:9092` 会在自己容器里找 9092，但自己没跑 Kafka，连接失败。

### 正确做法：用服务名通信

docker-compose 里每个 service 有个**服务名，自动成为 DNS 名**：
```yaml
services:
  kafka:
    # ...
  backend:
    environment:
      - KAFKA_BOOTSTRAP_SERVERS=kafka:9092  # 用服务名 kafka，不是 localhost
```

Docker 内置 DNS，`kafka` 自动解析到 Kafka 容器 IP。

### 本项目默认值的含义

config.py 默认 `localhost:9093` 是给**本地开发**（宿主机跑 FastAPI，容器跑 Kafka）用的。**如果后端也打进 Docker，必须改成 `kafka:9092` 或用环境变量覆盖**。

### Java 类比

Spring Boot 在 Docker 里连 MySQL，`spring.datasource.url` 也不能写 `localhost`，要写 `mysql:3306`（service 名）。

---

## 九、安全：0.0.0.0 监听风险

### 0.0.0.0 本身不是漏洞，要看监听的是什么服务

| 服务 | 监听 0.0.0.0 | 风险 |
|------|-------------|------|
| FastAPI（有鉴权） | ✅ 必须要 | 低 |
| Nginx（网关） | ✅ 必须要 | 低 |
| MySQL | 🔴 别暴露公网 | 高（密码可爆破） |
| Redis（默认无密码） | 🔴 别暴露公网 | 极高（直接被清库） |
| ClickHouse（本项目默认空密码） | 🔴 别暴露公网 | 高 |

### 正确生产架构

```
公网 → 防火墙（只开 80/443） → Nginx → FastAPI（内网 0.0.0.0）
                                        ↓
                                    MySQL/Redis/ClickHouse（只监听 127.0.0.1 或内网）
```

**数据库监听 0.0.0.0 是大忌，Web 服务监听 0.0.0.0 是必须。** 区别：Web 服务有鉴权 + 前有网关，数据库不该直连公网。最小暴露面原则。

### 实战教训

MySQL 3306 暴露公网 + 弱密码 → 被自动扫描器扫到 → 写表勒索（比特币）。全网每天成千上万扫描器在扫 3306/6379/27017。

---

## 十、Spring vs Python 配置对照表

| 对比点 | Spring Boot | Python Pydantic |
|--------|-------------|-----------------|
| 配置文件 | `application.yml` | `.env` |
| 默认值写法 | `${ENV_NAME:default}` | `field: type = default` |
| 强类型绑定类 | `@ConfigurationProperties` + POJO | `BaseSettings` 子类 |
| 取单个值 | `@Value("${kafka.host}")` | `settings.kafka_bootstrap_servers` |
| 环境变量映射 | 需在 yml 声明 | 字段名自动对应（大小写不敏感） |
| 启动时校验 | 需 `@Validated` | 默认就有 |
| 多环境 profile | `application-dev/prod.yml` | 没有内置（自己搞） |

---

## 十一、一句话总结

1. **整体架构**：SDK（Agent 进程内）→ FastAPI /collect → Kafka → Consumer → ClickHouse；查询直接读 ClickHouse。
2. **Kafka 是传送带不是仓库**，数据最终落 ClickHouse 才能查。
3. **SDK 不是自动装配**，必须手动 import + 换接口（或用 Spring AI Advisor 这种框架内置埋点）。
4. **config.py = Spring 的 @ConfigurationProperties + application.yml**，Pydantic = Hibernate Validator + 配置绑定。
5. **默认值 ≠ 写死**，环境变量优先级最高，大小写不敏感匹配字段名。
6. **Docker 里连别的容器用服务名，不能用 localhost**。
7. **0.0.0.0 对 Web 服务是必须，对数据库是大忌**。
