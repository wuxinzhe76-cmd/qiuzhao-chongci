# Interview Arena

AI 原生面试平台 — 刷题、判题、AI 模拟面试一体化。

## 系统架构

```
┌─────────────────────────────────────────────────────┐
│                    Frontend (Next.js)                 │
│  刷题 / 判题 / RAG 知识库 / AI 模拟面试               │
├─────────────────────────────────────────────────────┤
│                    Backend (Spring Boot 3.5)          │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────┐  │
│  │ Agent    │  │ RAG      │  │ Judge (Docker)    │  │
│  │ ReAct    │  │ Hybrid   │  │ 沙箱判题           │  │
│  │ Harness  │  │ Retrieve │  │                   │  │
│  └────┬─────┘  └────┬─────┘  └───────────────────┘  │
│       │              │                                │
│  ┌────┴──────────────┴────────────────────────────┐  │
│  │  LLM (MiniMax-M3)  Embedding (DashScope v3)   │  │
│  │  CircuitBreaker + TokenBudget + ReAct          │  │
│  └────────────────────────────────────────────────┘  │
├─────────────────────────────────────────────────────┤
│  MySQL │ Redis │ Milvus │ Elasticsearch │ RabbitMQ   │
└─────────────────────────────────────────────────────┘
```

## 两个 Agent

### 面试官 Agent
- ReAct 循环（Thought -> Action -> Observation）
- 工具白名单：getQuestionDetail / pickQuestion / getWeakPoints
- 三层控制：AI 主导 + 代码兜底（ThreeLayerController）+ 用户主动结束
- 记忆驱动出题：基于用户薄弱点画像优先考察
- Harness 约束：步数限制(5) + 循环检测 + 目标漂移检测 + 参考答案防泄露

### 询问助手 Agent (Quick Ask)
- ReAct 循环 + 降级链路（RetrievalRouter 关键词路由）
- 工具白名单：retrieveKnowledge / retrieveMemory / webSearch
- 语义缓存（RAG_ONLY 路由可共享，防跨用户泄漏）
- 多策略记忆检索 + RRF 融合

## Harness Engineering

| 机制 | 实现 | 接入状态 |
|------|------|---------|
| 状态机 | ThreeLayerController | 已接入 |
| ReAct 循环 | ReActExecutor (MAX_STEPS=5) | 已接入 |
| 工具白名单 | ToolRegistry + per-Agent 隔离 | 已接入 |
| 熔断器 | CircuitBreaker (5次/60s) | 已接入 ReActExecutor |
| Token 预算 | TokenBudget (100k/20k/5k) | 已接入 ReActExecutor |
| 循环检测 | LoopDetector (per-session) | 已接入 |
| 目标漂移 | GoalDriftDetector (Redis 计数) | 已接入 |
| 输入清洗 | InputSanitizer (防 Prompt Injection) | 已接入 |
| 输出监控 | OutputMonitor (堆栈/敏感信息检测) | 已接入 |
| 限流 | Sentinel (HTTP + 工具调用) | 已接入 |
| 答案防泄露 | 3 层（代码注入控制 + Prompt 约束 + 输出检测） | 已接入 |

## 记忆系统

```
短期记忆 (Redis, TTL 2h)          长期记忆 (MySQL + Milvus)
├── 对话历史 (滑动窗口 10 条)      ├── 情景记忆 (面试问答明细, 30 天衰减)
├── 当前题目 / 轮次 / 已用题目     └── 语义记忆 (知识画像 + 薄弱点, 永久)
└── 面试结束 -> 记忆整合 -> 清理       └── 顽固薄弱点 (连续 2 次 < 60 分, 权重 x2)
```

## RAG 检索流程

```
用户提问 -> 语义缓存 -> 查询改写 -> 混合检索(向量+BM25+RRF) -> Cross-Encoder 精排
-> 文档去重 -> Lost-in-the-middle 重排 -> LLM 生成 -> 缓存写入
```

## 环境要求

- Java 21
- Node.js 18+
- MySQL 8.0+
- Redis 7.0+
- Milvus 2.3+
- Elasticsearch 8.x (含 IK 分词器)
- RabbitMQ 3.12+

## 快速启动

### 1. 配置环境变量

```bash
cp backend/.env.example backend/.env
# 编辑 .env 填入 API Key 和中间件密码
```

### 2. 启动中间件

使用外部 Docker Compose 或手动启动 MySQL / Redis / Milvus / ES / RabbitMQ。

### 3. 启动后端

```bash
cd backend
./mvnw spring-boot:run
```

Flyway 自动执行数据库迁移 (V1~V6)。

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

### 5. 初始化 RAG 索引

启动后端后，调用管理员接口导入题目到 Milvus + ES：

```bash
curl -X POST http://localhost:8080/api/rag/import
```

## 环境变量

| 变量 | 说明 | 默认值 |
|------|------|--------|
| MINIMAX_API_KEY | MiniMax Chat API Key | - |
| MINIMAX_BASE_URL | MiniMax API 地址 | https://api.minimaxi.com |
| MINIMAX_CHAT_MODEL | 主聊天模型 | MiniMax-M3 |
| AI_DASHSCOPE_API_KEY | DashScope API Key (Embedding/Rerank) | - |
| MYSQL_HOST | MySQL 地址 | host.docker.internal |
| MYSQL_PASSWORD | MySQL 密码 | - |
| REDIS_HOST | Redis 地址 | host.docker.internal |
| REDIS_PASSWORD | Redis 密码 | - |
| RABBITMQ_HOST | RabbitMQ 地址 | host.docker.internal |
| RABBITMQ_PASSWORD | RabbitMQ 密码 | - |
| ES_HOST | Elasticsearch 地址 | host.docker.internal |
| MILVUS_HOST | Milvus 地址 | host.docker.internal |
| MILVUS_PORT | Milvus 端口 | 19530 |

## 测试

```bash
cd backend
# 纯单元测试（无需中间件）
./mvnw test -Dtest="LoopDetectorTest,ThreeLayerControllerTest,OutputMonitorTest"
```

## 技术栈

| 层 | 技术 |
|----|------|
| 后端框架 | Spring Boot 3.5 + Spring AI 1.1 |
| AI 模型 | MiniMax-M3 (Chat) + DashScope (Embedding/Rerank) |
| 向量数据库 | Milvus (HNSW + COSINE) |
| 搜索引擎 | Elasticsearch (BM25 + IK 分词) |
| 消息队列 | RabbitMQ (异步报告生成) |
| 限流 | Sentinel |
| ORM | MyBatis-Plus |
| 前端框架 | Next.js 14 + TypeScript + Tailwind CSS |
| 代码编辑器 | Monaco Editor |
| 状态管理 | Zustand + SWR |
