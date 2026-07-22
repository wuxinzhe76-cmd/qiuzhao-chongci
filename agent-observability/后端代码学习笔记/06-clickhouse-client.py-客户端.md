# Agent 可观测性后端 · 学习笔记 06：clickhouse/client.py（ClickHouse 客户端）

> 📅 学习日期：2026-06-29
> 🎯 目标：理解 ClickHouse 客户端的写入/查询/重试机制、同步库在 async 里的用法
> 👤 背景：Java 后端转 AI Agent，对照 JDBC + 线程池理解

---

## 文件概述

`client.py` 是 ClickHouse 的客户端封装，负责**写入数据 + 查询数据**。

- 被 Consumer 的 `flush_*` 调用写入（通过 `_retry_insert`）
- 被 API 层（traces.py/metrics.py 等）调用查询

**Java 类比**：相当于 MyBatis Mapper + JDBC Connection 的封装。

完整代码 542 行，分 3 块：
1. get_client + _retry_insert（客户端单例 + 重试机制）
2. 5 个 insert_*（写入函数）
3. 6 个 query_*（查询函数）

---

## 块 1：get_client + _retry_insert

### 1. 导入（重点：同步库！）

```python
from clickhouse_driver import Client as SyncClient    # ← 同步客户端
from clickhouse_driver import errors as ch_errors
```

**核心认知**：clickhouse-driver 是**同步库**，没有异步客户端。所以整个文件的核心难点是"怎么在 async 函数里调同步操作"——答案是 `run_in_executor`。

**`as` 重命名**：`Client` 太通用，重命名成 `SyncClient` 强调"同步"。

### 2. get_client（懒加载单例）

```python
_client: Optional[SyncClient] = None

def get_client() -> SyncClient:
    global _client
    if _client is None:
        _client = SyncClient(
            host=settings.clickhouse_host,
            port=settings.clickhouse_port,
            database=settings.clickhouse_database,
            user=settings.clickhouse_user,
            password=settings.clickhouse_password,
        )
    return _client
```

**懒加载**：第一次调 get_client() 时才创建，以后返回已有的。

**跟 Producer/Consumer 的区别**：
- Producer/Consumer：应用启动时创建（lifespan）
- ClickHouse client：**第一次用到时才创建**（懒加载）

**Java 类比**：`@Lazy` 单例 Bean。

### 3. _retry_insert（重试机制，重点！）

```python
async def _retry_insert(
    insert_fn, data: List[Dict[str, Any]], label: str, max_retries: int = 3
) -> None:
    if not data:
        return

    loop = asyncio.get_event_loop()
    last_exc = None

    for attempt in range(max_retries):
        try:
            await loop.run_in_executor(None, insert_fn, data)
            return  # 成功
        except ch_errors.Error as e:
            last_exc = e
            if attempt < max_retries - 1:
                delay = 2 ** attempt
                logger.warning(f"...retrying in {delay}s: {e}")
                await asyncio.sleep(delay)

    logger.error(f"...discarding {len(data)} records: {last_exc}")
```

#### 三个核心机制

**机制 1：run_in_executor（整个文件的核心！）**

```python
await loop.run_in_executor(None, insert_fn, data)
```

- `run_in_executor` 把同步操作丢到**线程池**执行，不阻塞事件循环
- `None` = 用默认线程池
- **问题**：insert_fn 是 async 函数，会导致静默失败（见下方"设计缺陷"）

**Java 类比**：
```java
ExecutorService executor = Executors.newCachedThreadPool();
Future<Void> future = executor.submit(() -> {
    insertFn(data);   // 同步阻塞，但在新线程里跑
    return null;
});
future.get();   // 等它完成
```

**机制 2：指数退避**

```python
delay = 2 ** attempt
# attempt=0: delay=1s
# attempt=1: delay=2s
# attempt=2: delay=4s（最后一次不睡）
```

避免疯狂重试压垮 ClickHouse。

**Java 类比**：Spring Retry `@Backoff(delay=1000, multiplier=2)`

**机制 3：丢弃数据**

3 次都失败 → 记 error 日志 → 丢弃。**这是缺陷**！生产环境应该加死信队列。

#### 谁调用 _retry_insert

被 consumer.py 的 flush_* 调用（不是本文件内部调用）：

```python
# consumer.py
async def flush_traces(batch):
    await _retry_insert(insert_traces, batch, "traces")
```

**API 查询不用 _retry_insert**（查失败返回空列表就行，不用重试）。

---

## 块 2：5 个 insert_* 写入函数

5 个函数结构一模一样，套路相同：

```
① 判空 if not data: return
② loop = asyncio.get_event_loop()
③ 定义 _insert 同步函数：
   - client = get_client()
   - columns = ["trace_id", ...]   列名列表
   - values = [(d["trace_id"], ...) for d in data]   列表推导式生成元组
   - client.execute("INSERT INTO 表名 (列名) VALUES", values)
④ await loop.run_in_executor(None, _insert)
⑤ except: 记日志 + raise
```

### insert_traces 为例

```python
async def insert_traces(data: List[Dict[str, Any]]) -> None:
    if not data:
        return

    loop = asyncio.get_event_loop()

    def _insert():
        client = get_client()
        columns = ["trace_id", "span_id", "parent_span_id", "name",
                   "start_time", "end_time", "attributes"]
        values = [
            (d["trace_id"], d["span_id"], d["parent_span_id"], d["name"],
             d["start_time"], d["end_time"], d["attributes"])
            for d in data
        ]
        client.execute(
            f"INSERT INTO agent_traces ({', '.join(columns)}) VALUES",
            values,
        )

    try:
        await loop.run_in_executor(None, _insert)
    except ch_errors.Error as e:
        logger.error(f"ClickHouse insert traces error: {e}")
        raise
```

### 重点语法

#### `', '.join(columns)` 列名拼接

```python
columns = ["trace_id", "span_id", "name"]
', '.join(columns)
# = "trace_id, span_id, name"
```

#### 列表推导式生成 values

```python
values = [(d["trace_id"], d["span_id"], ...) for d in data]
# = [("trace_001", "span_001", ...), ("trace_002", "span_002", ...)]
```

**Java 类比**：`data.stream().map(d -> new Object[]{d.get("trace_id"), ...}).collect(...)`，Python 更简洁。

#### 参数化查询（防 SQL 注入）

```python
client.execute("INSERT INTO ... VALUES", values)
#                                     ↑ 参数化，安全！
```

insert 用参数化，**没有 SQL 注入问题**。

### 5 个 insert_* 对应 5 张表

| 函数 | 写入表 | span_type |
|------|--------|-----------|
| insert_traces | agent_traces | trace |
| insert_metrics | llm_metrics | llm_metrics |
| insert_prompts | prompt_logs | prompt |
| insert_tool_calls | tool_calls | tool_call |
| insert_sessions | sessions | session |

---

## 块 3：6 个 query_* 查询函数

6 个查询函数套路相同，被 API 层调用。

### 共同套路

```
① loop = asyncio.get_event_loop()
② 定义 _query 同步函数：
   - client = get_client()
   - 拼 SQL（SELECT ... FROM ... WHERE ...）
   - result = client.execute(query)
   - return [dict(zip(columns, row)) for row in result]
③ await loop.run_in_executor(None, _query)
④ except: 记日志 + return []（查失败返回空列表）
```

### 重点语法

#### `dict(zip(columns, row))` 元组转字典

```python
result = client.execute(query)
# result = [("trace_001", "span_001", ...), ("trace_002", ...)]
columns = ["trace_id", "span_id", ...]

[dict(zip(columns, row)) for row in result]
# = [{"trace_id": "trace_001", "span_id": "span_001", ...}, ...]
```

**zip** 把两个列表拼成 pair，**dict** 把 pair 转字典。一行搞定，Java 要写 10 行。

#### `" OR ".join(...)` 列表推导式拼接 SQL 条件

```python
model_filter = " OR ".join([f"model_name = '{m}'" for m in model_names])
# model_names = ["gpt-4", "claude-3"]
# = "model_name = 'gpt-4' OR model_name = 'claude-3'"
```

### 6 个查询函数

| 函数 | 查什么 | 被谁调 |
|------|--------|--------|
| query_traces | 链路列表 | traces.py |
| query_prompts | Prompt 原文 | prompts.py |
| query_tool_calls | Tool 调用记录 | prompts.py |
| query_sessions | 会话列表 | traces.py |
| query_metrics_compare | 多模型效能对比 | metrics.py |
| query_leaderboard | 排行榜（3 种指标） | leaderboard.py |

### 聚合查询是 OLAP 主场

query_metrics_compare 的 SQL：
```sql
SELECT model_name,
       count() as total_requests,
       avg(prefill_ms) as avg_prefill_ms,
       avg(decode_ms) as avg_decode_ms,
       sum(input_tokens) as total_input_tokens,
       sum(cost_usd) as total_cost_usd
FROM llm_metrics
GROUP BY model_name
ORDER BY total_requests DESC
```

**group by + avg/sum 是 ClickHouse 主场**，亿级数据秒级出，MySQL 做这种聚合会很慢。

### query_leaderboard 查 tool_stats 物化视图

```python
# slowest_tool 查的是 tool_stats，不是 tool_calls
query = f"""
    SELECT tool_name, tool_type, total_calls,
           avg_duration_ms, max_duration_ms, error_count, error_rate
    FROM tool_stats
    ORDER BY avg_duration_ms DESC
    LIMIT {limit}
"""
```

**tool_stats 是物化视图**（预先聚合好的），查询比从 tool_calls 现算快。

---

## 🔴 SQL 注入风险（重要！面试考点）

### 问题：query_* 用 f-string 拼接 SQL

```python
# query_traces 里有这样的代码：
query = f"SELECT * FROM agent_traces WHERE trace_id = '{trace_id}'"
#                                              ↑ 直接拼接！危险！
```

### 风险演示

如果 `trace_id` 传入恶意值：

```python
trace_id = "'; DROP TABLE agent_traces; --"

# 拼接后的 SQL：
query = "SELECT * FROM agent_traces WHERE trace_id = ''; DROP TABLE agent_traces; --'"
```

**结果**：agent_traces 表被删了！

### 为什么 insert_* 没这问题

```python
# insert 用参数化，安全
client.execute("INSERT INTO ... VALUES", values)
#                                     ↑ 参数化，ClickHouse 驱动自动转义
```

### 修复方案

**用参数化查询**：

```python
# ❌ 危险（现在代码）
query = f"SELECT * FROM agent_traces WHERE trace_id = '{trace_id}'"
result = client.execute(query)

# ✅ 安全（修复后）
query = "SELECT * FROM agent_traces WHERE trace_id = %(trace_id)s"
result = client.execute(query, {"trace_id": trace_id})
```

clickhouse-driver 支持命名参数 `%(name)s`，驱动会自动转义特殊字符。

### 所有需要修复的查询函数

| 函数 | 拼接的参数 | 修复方式 |
|------|-----------|---------|
| query_traces | trace_id, limit | 改参数化 |
| query_prompts | trace_id, limit | 改参数化 |
| query_tool_calls | trace_id, limit | 改参数化 |
| query_sessions | agent_name, limit | 改参数化 |
| query_metrics_compare | model_names | 改参数化 |
| query_leaderboard | limit | 改参数化 |

**面试加分点**：能指出 SQL 注入风险 + 给出参数化修复方案。

---

## 🔴 设计缺陷：async insert_* + run_in_executor = 静默失败

### 问题

insert_* 是 async 函数，但 _retry_insert 用 run_in_executor 调用它：

```python
# insert_traces 是 async 函数
async def insert_traces(data):
    ...

# _retry_insert 用 run_in_executor 调用它
await loop.run_in_executor(None, insert_fn, data)
#                             ↑ insert_fn = insert_traces(async 函数)
```

### 为什么会静默失败

```
run_in_executor 把 insert_traces 丢给线程池
  ↓
线程池线程调用 insert_traces(data)
  ↓
insert_traces 是 async 函数 → 返回 coroutine 对象（函数体不执行！）
  ↓
线程池任务"完成"（没抛异常）
  ↓
_retry_insert：没异常 → return → 退出
  ↓
数据从未写入 ClickHouse，系统以为成功
```

**核心原因**：
- async 函数不加 await = 只创建 coroutine，不执行函数体
- run_in_executor 期望同步函数，遇到 async 函数只拿到 coroutine 就结束

### 修复方案

**insert_* 改成同步函数**（去掉 async）：

```python
# ❌ 现在（async，有问题）
async def insert_traces(data):
    loop = asyncio.get_event_loop()
    def _insert():
        client.execute(...)
    await loop.run_in_executor(None, _insert)

# ✅ 修复（同步函数）
def insert_traces(data):
    client = get_client()
    client.execute(...)
# _retry_insert 用 run_in_executor 调它，正确执行
```

### 核心铁律

- **await 必须在事件循环里**（协程里）
- **run_in_executor 期望同步函数**（线程池里）
- **async 函数 + run_in_executor = 静默失败**
- **修复**：insert_* 去掉 async，改成同步函数

---

## Python vs Java 语法对照

| 概念 | Python | Java |
|------|--------|------|
| 线程池执行 | `loop.run_in_executor(None, fn, arg)` | `executor.submit(fn, arg)` |
| 懒加载单例 | `if _client is None: _client = ...` | `@Lazy @Bean` |
| 元组转字典 | `dict(zip(columns, row))` | 手动遍历 ResultSet |
| 列表推导式 | `[f"x = '{m}'" for m in names]` | `names.stream().map(...)` |
| 字符串拼接 | `", ".join(columns)` | `String.join(", ", columns)` |
| 指数退避 | `delay = 2 ** attempt` | `Math.pow(2, attempt)` |
| 可选类型 | `Optional[SyncClient]` | `@Nullable SyncClient` |

---

## 核心铁律（面试考点）

1. **clickhouse-driver 是同步库**，用 run_in_executor 丢线程池执行
2. **run_in_executor 期望同步函数**，传 async 函数会静默失败
3. **懒加载单例**：get_client 第一次调用才创建
4. **指数退避**：1s → 2s，避免压垮 ClickHouse
5. **3 次失败丢弃**：缺陷，生产要加死信队列
6. **三层职责**：get_client（连接）/ _retry_insert（重试）/ insert_*（SQL）
7. **insert 用参数化（安全）**，query 用 f-string 拼接（SQL 注入风险！）
8. **聚合查询是 OLAP 主场**：group by + avg/sum，ClickHouse 秒杀
9. **tool_stats 是物化视图**：预先聚合，查询比现算快
10. **静默失败比丢弃更危险**：没日志，以为成功，数据其实没写
