# ClickHouse 指南

---

## 一、为什么需要 ClickHouse？

### 从一个问题说起

假设你的 Agent-Insight 系统运行了一天，采集了 1000 万条 LLM 调用记录，每条记录包含：

| 字段 | 类型 | 示例 |
|------|------|------|
| trace_id | String | "a1b2c3d4..." |
| span_id | String | "e5f6g7h8..." |
| model_name | String | "gpt-5.4" |
| prefill_ms | Float64 | 523.7 |
| decode_ms | Float64 | 2100.3 |
| input_tokens | UInt32 | 1500 |
| output_tokens | UInt32 | 800 |
| tps | Float64 | 380.5 |
| cost_usd | Float64 | 0.035 |

现在老板问你：各模型的平均 prefill 耗时是多少？

SQL 写出来很简单：

```sql
SELECT model_name, AVG(prefill_ms) FROM llm_metrics GROUP BY model_name;
```

但 MySQL 执行这条 SQL 时，要把 1000 万行数据全部读出来。每行 9 个字段，实际只用到 `model_name` 和 `prefill_ms` 这 2 个字段，其余 7 个字段的数据白读了。

数据量越大，浪费越严重。

### 两种存储方式

```
MySQL（行式存储）—— 按行连续存放：

磁盘：[行1全部字段][行2全部字段][行3全部字段]...
       ↓ 查询 AVG(prefill_ms) 时
       必须读完整行 → 丢弃不需要的列

ClickHouse（列式存储）—— 按列连续存放：

磁盘：
  trace_id 列:    [值1][值2][值3]...
  span_id 列:     [值1][值2][值3]...
  model_name 列:  [值1][值2][值3]...    ← 只读这列
  prefill_ms 列:  [值1][值2][值3]...    ← 只读这列
  ...
       ↓ 查询 AVG(prefill_ms) 时
       只读 model_name + prefill_ms 两列，其他列完全不碰
```

行式存储像一页一页翻书，列式存储像直接翻到某一列。

---

## 二、ClickHouse 是什么

ClickHouse 是 Yandex（俄罗斯搜索引擎）开源的列式数据库，专门做分析查询（OLAP）。

和 MySQL 的区别：

|  | MySQL | ClickHouse |
|--|-------|-----------|
| 设计目标 | 事务处理 | 分析查询 |
| 典型操作 | 单行 INSERT/UPDATE | 批量 INSERT + 聚合 SELECT |
| 数据量 | 百万级 | 亿级 |
| 查询特点 | 精确查找、JOIN | 扫描大量数据、GROUP BY |
| 响应时间 | 毫秒级 | 百毫秒到秒级 |
| 事务 | 支持 ACID | 不支持 |

### 架构对比

**存储层**

```
MySQL（行式）                          ClickHouse（列式）

┌─────────────────────────────┐       ┌─────────────────────────────┐
│  Page / Block               │       │  Column Chunk               │
│                             │       │                             │
│  ┌───────────────────────┐  │       │  trace_id:  [a][b][c][d]... │
│  │ Row1: id|name|age|... │  │       │  model:     [g][g][c][q]... │
│  │ Row2: id|name|age|... │  │       │  prefill:   [523][480][610] │
│  │ Row3: id|name|age|... │  │       │  tps:       [380][315][400]  │
│  │ ...                     │  │       │  ...                        │
│  └───────────────────────┘  │       └─────────────────────────────┘
│  每行完整存储                │       每列连续存储，压缩后很紧凑      │
└─────────────────────────────┘
```

**索引层**

```
MySQL（B+ 树）                         ClickHouse（稀疏索引）

        [根节点]                         Part 1          Part 2
       /        \                       ┌──────┐       ┌──────┐
   [内部]      [内部]                   │索引条目│       │索引条目│
   /    \      /    \                   │每8192行│       │每8192行│
 [叶]  [叶]  [叶]  [叶]                │一条记录│       │一条记录│
  ↓    ↓    ↓    ↓                     └──────┘       └──────┘
 指向完整行数据                         指向数据块

 精确定位单行                           快速跳过无关数据块
 WHERE id = 1                          WHERE date > '2026-01-01'
```

**执行引擎**

```
MySQL（逐行执行）                      ClickHouse（向量化执行）

  ┌─────┐                              ┌─────────────────┐
  │行 1 │ → 计算 → 输出                │[行1..行8192]    │
  │     │                              │                 │ → 批量计算 → 输出
  │行 2 │ → 计算 → 输出                │  CPU SIMD 指令   │
  │     │                              │  一次处理多个值   │
  │行 3 │ → 计算 → 输出                └─────────────────┘
  │     │
  └─────┘                              吞吐量高 10-100x
                                       适合扫描百万行
  CPU 利用率低
  适合精确查找几行
```

**压缩效果**

| 数据类型 | MySQL | ClickHouse | 原因 |
|---------|-------|-----------|------|
| 整数列 | 2:1 | 10:1+ | 同列值相近，Delta 编码高效 |
| 字符串列 | 2:1 | 15:1+ | 重复值多，字典编码高效 |
| 浮点列 | 2:1 | 5:1+ | 同列值范围相近 |
| 整表 | 2-3:1 | 10-20:1 | 列式 + 针对性编码 |

### 什么时候用 ClickHouse

| 场景 | 选哪个 | 为什么 |
|------|--------|--------|
| 用户注册/登录 | MySQL | 需要事务、频繁单行读写 |
| 订单支付 | MySQL | ACID 保证一致性 |
| 商品详情查询 | MySQL | 精确查找，JOIN 多 |
| 日志采集 | ClickHouse | 只追加不修改，写入量大 |
| 链路追踪 | ClickHouse | 按 trace_id 聚合，宽表扫描 |
| 模型性能对比 | ClickHouse | AVG/SUM 聚合快 |
| 监控仪表盘 | ClickHouse | 秒级聚合百万数据 |
| 成本统计 | ClickHouse | GROUP BY 快 |
| 数据清理 | ClickHouse | 按分区 DROP 秒级完成 |
| 频繁 UPDATE | MySQL | ClickHouse 的 UPDATE 很重 |
| 高并发小查询 | MySQL | ClickHouse 适合少量大查询 |
| 复杂 JOIN | MySQL | ClickHouse JOIN 性能一般 |

简单判断：

```
数据主要是"改"的 → MySQL
数据主要是"加"的 → ClickHouse

查询主要是"找某一条" → MySQL
查询主要是"算统计" → ClickHouse
```

### 核心特性

**列式存储**
- 同列数据连续存放，查询时只读需要的列
- 同列数据类型一致，压缩效率高（通常 10:1 以上）

**向量化执行**
- 不是逐行处理，而是一批数据一起计算
- 利用 CPU 的 SIMD 指令集

```
传统数据库：  行1 → 计算 → 行2 → 计算 → 行3 → 计算
ClickHouse：  [行1,行2,行3,...行N] → 一次性批量计算
```

**稀疏索引**
- 每隔 8192 行记录一个索引条目
- 索引很小，全部放入内存
- 不像 MySQL 的 B+ 树能精确定位到单行

**分区与排序**
- 数据按分区键（如日期）分成多个物理分区
- 每个分区内按排序键排序，加速范围查询

### 表引擎

ClickHouse 的表引擎决定数据的存储方式。最常用的是 MergeTree 家族：

```sql
CREATE TABLE llm_metrics (
    trace_id    String,
    span_id     String,
    model_name  String,
    prefill_ms  Float64,
    decode_ms   Float64,
    input_tokens UInt32,
    output_tokens UInt32,
    tps         Float64,
    cost_usd    Float64,
    created_at  DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY (model_name, created_at);
```

| 引擎 | 用途 |
|------|------|
| MergeTree | 基础引擎，支持索引、分区 |
| ReplacingMergeTree | 去重，相同主键只保留最新 |
| AggregatingMergeTree | 预聚合，后台自动聚合 |
| Distributed | 分布式，跨节点查询 |

---

## 三、动手体验

### 启动 ClickHouse

项目已配置 Docker Compose，直接启动：

```bash
docker-compose up -d clickhouse
```

### 连接 ClickHouse

```bash
# 方式一：通过 Docker 进入 CLI
docker exec -it clickhouse clickhouse-client

# 方式二：使用 DBeaver / DataGrip 等工具连接
# Host: localhost
# Port: 8123（HTTP）或 9000（Native）
# User: default
# Password: （空）
```

### 创建表

```sql
-- 链路追踪表
CREATE TABLE IF NOT EXISTS agent_traces (
    trace_id        String,
    span_id         String,
    parent_span_id  String,
    name            String,
    start_time      DateTime64(3),
    end_time        DateTime64(3),
    attributes      String,
    created_at      DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY (trace_id, start_time);

-- LLM 性能指标表
CREATE TABLE IF NOT EXISTS llm_metrics (
    trace_id        String,
    span_id         String,
    model_name      String,
    prefill_ms      Float64,
    decode_ms       Float64,
    input_tokens    UInt32,
    output_tokens   UInt32,
    tps             Float64,
    cost_usd        Float64,
    created_at      DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY (model_name, created_at);

-- Prompt 日志表
CREATE TABLE IF NOT EXISTS prompt_logs (
    trace_id        String,
    span_id         String,
    model_name      String,
    prompt          String,
    response        String,
    input_tokens    UInt32,
    output_tokens   UInt32,
    latency_ms      Float64,
    stream          UInt8,
    status          String,
    error           String,
    created_at      DateTime DEFAULT now()
) ENGINE = MergeTree()
ORDER BY (model_name, created_at);
```

### 插入数据

```sql
-- 单条插入（不推荐，ClickHouse 擅长批量）
INSERT INTO llm_metrics (trace_id, span_id, model_name, prefill_ms, decode_ms, input_tokens, output_tokens, tps, cost_usd)
VALUES ('trace-001', 'span-001', 'gpt-5.4', 523.7, 2100.3, 1500, 800, 380.5, 0.035);

-- 批量插入（推荐）
INSERT INTO llm_metrics (trace_id, span_id, model_name, prefill_ms, decode_ms, input_tokens, output_tokens, tps, cost_usd)
VALUES
    ('trace-002', 'span-002', 'gpt-5.4', 480.2, 1900.1, 1200, 600, 315.7, 0.028),
    ('trace-003', 'span-003', 'claude-sonnet-5', 610.5, 2500.8, 2000, 1000, 400.0, 0.045),
    ('trace-004', 'span-004', 'qwen-max', 350.1, 1500.2, 800, 400, 266.5, 0.012);
```

### 典型查询

```sql
-- 各模型平均性能指标
SELECT
    model_name,
    COUNT() AS total_requests,
    ROUND(AVG(prefill_ms), 1) AS avg_prefill_ms,
    ROUND(AVG(decode_ms), 1) AS avg_decode_ms,
    ROUND(AVG(tps), 1) AS avg_tps,
    SUM(input_tokens) AS total_input_tokens,
    SUM(output_tokens) AS total_output_tokens,
    ROUND(SUM(cost_usd), 4) AS total_cost
FROM llm_metrics
GROUP BY model_name
ORDER BY total_requests DESC;

-- 结果示例：
-- ┌─model_name──────┬─total_requests─┬─avg_prefill_ms─┬─avg_tps─┬─total_cost─┐
-- │ gpt-5.4         │           2    │          501.9 │  348.1  │     0.0630 │
-- │ claude-sonnet-5 │           1    │          610.5 │  400.0  │     0.0450 │
-- │ qwen-max        │           1    │          350.1 │  266.5  │     0.0120 │
-- └─────────────────┴────────────────┴────────────────┴─────────┴────────────┘

-- 查询某条完整链路
SELECT * FROM agent_traces
WHERE trace_id = 'trace-001'
ORDER BY start_time;

-- 最近 10 条 Prompt 记录
SELECT
    model_name,
    LEFT(prompt, 50) AS prompt_preview,
    input_tokens,
    output_tokens,
    latency_ms,
    status
FROM prompt_logs
ORDER BY created_at DESC
LIMIT 10;
```

---

## 四、在 Agent-Insight 项目中的角色

### 数据流转

```
SDK 采集 → FastAPI 接收 → Kafka 缓冲 → Consumer 消费 → ClickHouse 存储 → React 前端展示
   │            │              │              │               │                │
  非侵入     202 Accepted   削峰填谷     批量写入        列式存储        瀑布图/仪表盘
  异步批量   立即返回       解耦上下游    50条/批         高压缩比        模型对比
```

### 为什么选 ClickHouse

| 考量 | MySQL | ClickHouse | 选择理由 |
|------|-------|-----------|---------|
| 写入模式 | 单条 INSERT | 批量 INSERT | SDK 批量上报，天然匹配 |
| 查询模式 | 精确查找 | 聚合分析 | 可观测性系统以聚合统计为主 |
| 数据量 | 百万级 | 亿级 | 生产环境日志量巨大 |
| 存储效率 | 一般 | 高压缩比 | 日志数据保留周期长，需要节省存储 |
| 事务需求 | 需要 | 不需要 | 日志数据只追加不修改 |

### 写入优化

项目对 ClickHouse 写入做了两层优化：

**批量写入 + 定期刷新**（`consumer.py`）
```python
BATCH_SIZE = 50        # 攒够 50 条写一次
FLUSH_INTERVAL = 5.0   # 或者每 5 秒强制写一次
```

**失败重试**（`client.py`）
```python
# 写入失败时指数退避重试（1s → 2s → 4s），最多 3 次
async def _retry_insert(insert_fn, data, label, max_retries=3):
    for attempt in range(max_retries):
        try:
            await loop.run_in_executor(None, insert_fn, data)
            return
        except ch_errors.Error as e:
            delay = 2 ** attempt
            await asyncio.sleep(delay)
```

---

## 五、常见问题

**Q：ClickHouse 为什么快？**

列式存储只读需要的列，减少 IO；向量化执行批量处理数据，利用 CPU SIMD 指令；高压缩比减少磁盘 IO；多线程并行，一个查询可以用多个 CPU 核心。

**Q：ClickHouse 有什么缺点？**

不支持事务；UPDATE/DELETE 操作很重，不适合频繁修改；并发查询低，适合少量大查询，不适合高并发小查询（QPS 通常 < 100）；Join 性能一般。

**Q：什么时候用 ClickHouse，什么时候用 MySQL？**

用 MySQL：用户信息、订单、权限等需要事务和频繁更新的数据。
用 ClickHouse：日志、监控指标、行为分析等"写多读少、追加为主、聚合查询"的数据。
两者结合：很多系统同时使用，MySQL 存业务数据，ClickHouse 存分析数据。

**Q：MergeTree 引擎怎么工作的？**

数据按分区键分成多个分区（如按天分区）；每个分区内按排序键排序，分成多个数据片段（Part）；后台线程定期合并小 Part 为大 Part（这也是名字的由来）；每个 Part 内部是列式存储 + 稀疏索引。

---

## 六、延伸阅读

| 资源 | 链接 |
|------|------|
| ClickHouse 官方文档 | https://clickhouse.com/docs |
| ClickHouse vs MySQL | https://clickhouse.com/comparison |
| 项目中的表结构定义 | `backend/app/clickhouse/schema.py` |
| 项目中的写入逻辑 | `backend/app/clickhouse/client.py` |

---

ClickHouse 是为分析场景设计的列式数据库。它不擅长单行增删改，但能在海量数据中秒级返回聚合统计结果。在 Agent-Insight 项目中，它承担了 LLM 调用日志、链路追踪、性能指标的最终存储和分析职责。
