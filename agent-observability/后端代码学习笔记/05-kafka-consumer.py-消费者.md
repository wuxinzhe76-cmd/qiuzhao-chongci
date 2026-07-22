# Agent 可观测性后端 · 学习笔记 05：kafka/consumer.py（消费者）

> 📅 学习日期：2026-06-29
> 🎯 目标：理解 Kafka 消费者的生命周期、消费主循环、数据解析、批量写入
> 👤 背景：Java 后端转 AI Agent，对照 Spring Boot KafkaListener 理解 AIOKafkaConsumer

---

## 文件概述

`consumer.py` 是**真正落库的角色**。从 Kafka 拉消息 → 按 span_type 分到 5 个桶 → 桶满 50 条或 5 秒超时就刷盘写 ClickHouse。

**Java 类比**：相当于 Spring 的 `@KafkaListener` + 一个后台消费线程 + 批量写入 Service。

完整代码 283 行，分 5 块讲：
1. 导入 + 全局变量 + 常量
2. start_consumer / stop_consumer（生命周期）
3. consume_loop（核心消费主循环）
4. parse_* 数据解析（5 种）+ calculate_cost
5. flush_* 批量写入（5 种）

---

## Consumer 在整个链路里的位置

```
SDK → Backend(collect) → Kafka(暂存) → 【Consumer】→ ClickHouse
                                        ↑ 你在这里
```

**Consumer 才落库！** Producer 只丢数据进 Kafka，Consumer 从 Kafka 拉数据写 ClickHouse。

---

## 块 1：导入 + 全局变量

### 导入 ClickHouse 写入函数

```python
from ..clickhouse.client import (
    insert_traces,
    insert_metrics,
    insert_prompts,
    insert_tool_calls,
    insert_sessions,
    _retry_insert,
)
```

Consumer 落库就是调这些函数。相当于导入 5 个 MyBatis Mapper。

### 全局变量（2 个，比 Producer 多一个）

```python
_consumer: AIOKafkaConsumer = None       # Kafka 消费者实例（全局单例）
_consumer_task: asyncio.Task = None      # 后台消费任务（协程）
```

**`_consumer_task` 是 Consumer 独有的**。Producer 是被动的（来请求才发），Consumer 是主动的（自己不停拉），需要后台任务持着死循环。

**Java 类比**：相当于一个后台线程 `Thread consumerThread`。

### BATCH_SIZE 常量

```python
BATCH_SIZE = 50
```

攒批阈值：每个桶攒够 50 条就刷盘。太小频繁写库性能差，太大延迟久。配合 5 秒超时，保证数据最多 5 秒延迟。

---

## 块 2：start_consumer / stop_consumer（生命周期）

### start_consumer

```python
async def start_consumer() -> None:
    """启动 Kafka 消费者"""
    global _consumer, _consumer_task

    try:
        _consumer = AIOKafkaConsumer(
            settings.kafka_topic,
            bootstrap_servers=settings.kafka_bootstrap_servers,
            group_id=settings.kafka_group_id,
            auto_offset_reset="earliest",
            enable_auto_commit=True,
            value_deserializer=lambda m: json.loads(m.decode("utf-8")),
            max_poll_records=100,
        )
        await _consumer.start()
        logger.info(f"Kafka consumer started, topic: {settings.kafka_topic}")
        _consumer_task = asyncio.create_task(consume_loop())
    except Exception as e:
        logger.error(f"Failed to start Kafka consumer: {e}")
        raise
```

**做了 3 件事**：

| 步骤 | 代码 | 干啥 |
|------|------|------|
| ① 创建 | `AIOKafkaConsumer(...)` | 配置参数，创建消费者对象（还没连） |
| ② 连接 | `await _consumer.start()` | 真正连接 Kafka，加入消费组 |
| ③ 启动循环 | `asyncio.create_task(consume_loop())` | 开一个后台协程跑死循环 |

**跟 init_producer 区别**：Consumer 多了 `create_task`，因为要一直跑死循环。

#### `asyncio.create_task` 详解

```python
_consumer_task = asyncio.create_task(consume_loop())
```

启动一个后台协程，跑 `consume_loop` 函数，返回一个 Task 对象。

**`consume_loop` 是死循环**（while True），如果不放 Task 里，会卡住 start_consumer 永远返回不了。放进 Task 后，它在后台跑，start_consumer 立即返回。

**Java 类比**：
```java
Thread consumerThread = new Thread(() -> {
    while (true) { consume(); }   // 死循环
});
consumerThread.start();
this.consumerThread = consumerThread;
```

**关键区别**：
- Java Thread：真多线程，操作系统调度
- Python asyncio Task：单线程协程，事件循环调度（没有线程切换开销）

### Kafka Consumer 配置参数

#### `group_id` —— 消费组

Kafka 用消费组实现负载均衡：
- **同一个 group**：多个 Consumer 分摊消费（一条消息只被组内一个 Consumer 处理）
- **不同 group**：每个 group 各自消费全量消息（广播）

#### `auto_offset_reset="earliest"` —— 首次消费从哪开始

| 值 | 含义 |
|----|------|
| `"earliest"` | 从最早的消息开始读（读历史消息，不丢数据） |
| `"latest"` | 从最新的消息开始读（之前的都不要） |

本项目用 earliest：首次启动把 Kafka 里积攒的历史消息都读一遍。

#### `enable_auto_commit=True` —— 自动提交 offset

**offset 是什么**：Consumer 读到哪了的"书签"。
- Consumer 读到第 50 条 → offset = 50
- Consumer 挂了 → 重启 → 从 offset 50 继续（不重复读）

auto_commit=True：Kafka 客户端定期自动提交 offset（默认 5 秒）。简单但可能重复消费。

#### `value_deserializer` —— 反序列化

```python
value_deserializer=lambda m: json.loads(m.decode("utf-8"))
```

Producer 端 serialize（dict → bytes），Consumer 端 deserializer（bytes → dict），反过来的操作。

```python
# Producer:
value_serializer=lambda v: json.dumps(v).encode("utf-8")
# dict → JSON 字符串 → bytes

# Consumer:
value_deserializer=lambda m: json.loads(m.decode("utf-8"))
# bytes → UTF-8 字符串 → dict
```

**Java 类比**：Producer 用 JsonSerializer，Consumer 用 JsonDeserializer，成对出现。

#### `max_poll_records=100` —— 每次最多拉多少

一次 poll 最多拉 100 条，防止一次拉太多处理不过来。跟 BATCH_SIZE(50) 不同：max_poll_records 是 Kafka 客户端单次拉取上限，BATCH_SIZE 是刷盘阈值。

### stop_consumer

```python
async def stop_consumer() -> None:
    """停止 Kafka 消费者"""
    global _consumer, _consumer_task

    if _consumer_task:
        _consumer_task.cancel()           # ← 发送取消信号
        try:
            await _consumer_task           # ← 等它真正结束
        except asyncio.CancelledError:
            pass                            # ← 吞掉取消异常

    if _consumer:
        await _consumer.stop()             # ← 断开 Kafka 连接
        _consumer = None
        logger.info("Kafka consumer stopped")
```

**做了 2 件事**：
1. 先 cancel Task（停止死循环）
2. 再 stop Consumer（断开 Kafka 连接）

**为什么先停 Task 再 stop Consumer**：如果先 stop Consumer，consume_loop 里还在调 `_consumer.getone()`，会报错"Consumer 已关闭"。

**Java 类比**：
```java
if (consumerThread != null) {
    consumerThread.interrupt();      // ← cancel
    consumerThread.join();            // ← await task
}
if (consumer != null) {
    consumer.close();
    consumer = null;
}
```

| Java | Python |
|------|--------|
| `thread.interrupt()` | `task.cancel()` |
| `thread.join()` | `await task` |
| `InterruptedException` | `CancelledError` |

### 什么时候调用

在 main.py 的 lifespan 里（跟 init_producer 一起）：

```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    await init_producer()        # ← 启动生产者
    await start_consumer()       # ← 启动消费者
    yield
    await stop_consumer()       # ← 关闭消费者
    await close_producer()     # ← 关闭生产者
```

**启动顺序**：Producer 先启动（要能发），Consumer 后启动（要能收）。
**关闭顺序**：Consumer 先停（停止拉），Producer 后停（发完）。

---

## 块 3：consume_loop（核心消费主循环）

这是 Consumer 的灵魂，真正干活的地方。

### 函数结构

```
consume_loop()
├─ ① 准备 5 个桶（batches）
├─ ② 准备刷盘映射表（flush_map）
├─ ③ 定义超时常量
├─ ④ while True 死循环
│   ├─ 4a. 从 Kafka 拉一条（带 5 秒超时）
│   ├─ 4b. 解析消息 + 分到桶
│   └─ 4c. 检查每个桶，满了就刷盘
└─ ⑤ finally：关闭前刷残留
```

### ① 准备 5 个桶

```python
batches: Dict[str, List[Dict[str, Any]]] = {
    "trace": [],
    "metrics": [],
    "prompt": [],
    "tool_call": [],
    "session": [],
}
```

5 个空列表，准备攒数据。每个桶对应一种 span_type。

**为什么分桶**：Kafka 里数据是混合的（一个批次可能含 5 种 span_type），但 ClickHouse 有 5 张表各自字段不同，必须分流后分别写。

**注意 key 不一致**：桶的 key 是 `"metrics"`，但 span_type 是 `"llm_metrics"`。所以分流时要做映射。

### ② 刷盘映射表（策略模式）

```python
flush_map = {
    "trace": (insert_traces, flush_traces),
    "metrics": (insert_metrics, flush_metrics),
    "prompt": (insert_prompts, flush_prompts),
    "tool_call": (insert_tool_calls, flush_tool_calls),
    "session": (insert_sessions, flush_sessions),
}
```

一个字典，key 是桶名，value 是元组（tuple），装了 2 个函数。

**元组 = 不可变列表**。`(f1, f2)` 是 tuple，`[f1, f2]` 是 list。区别：list 可改，tuple 不可改。

**为什么要这个映射表**：方便动态调用，不用写 5 个 if-else：

```python
# ❌ 不用映射表，要写 5 个 if
if key == "trace":
    await flush_traces(batch)
elif key == "metrics":
    await flush_metrics(batch)
# ... 又臭又长

# ✅ 用映射表，一行搞定
await flush_map[key][1](batch)
```

**这就是"策略模式"**：用字典 + 函数引用，代替一堆 if-else。Python 比 Java 简洁很多。

**Java 等价（策略模式）**：Java 要定义接口 + 5 个实现类，Python 直接用函数引用。

### ③ 超时常量

```python
FLUSH_INTERVAL = 5.0   # 5 秒
```

即使桶没满 50 条，5 秒也强制刷盘。保证数据最多延迟 5 秒落库。

### ④ while True 死循环

#### 4a. 从 Kafka 拉一条消息（带超时）

```python
while True:
    try:
        msg = await asyncio.wait_for(
            _consumer.getone(),       # ← 从 Kafka 拉一条
            timeout=FLUSH_INTERVAL,   # ← 最多等 5 秒
        )
```

`_consumer.getone()` 从 Kafka 拉一条消息。**注意**：Kafka 里一条消息可能装多个 span（因为 Producer 发的是 List）。

`asyncio.wait_for` 给 `getone()` 加超时。两种结果：
- 5 秒内拉到消息 → `msg = 消息`，往下走
- 5 秒没拉到 → 抛 `TimeoutError`，走 except

**为什么要超时**：如果没消息一直等，桶里残留数据永远不刷盘。超时机制保证 5 秒必刷一次。

#### 4b. 解析消息 + 分到桶

```python
        data = msg.value                                    # 拿到消息内容
        items = data if isinstance(data, list) else [data]  # 统一成 list

        for item in items:
            span_type = item.get("span_type", "trace")
            parsed = parse_item(item)                       # ← 解析！在 append 之前

            if span_type == "llm_metrics":
                batches["metrics"].append(parsed)           # ← append 的是 parsed
            elif span_type == "prompt":
                batches["prompt"].append(parsed)
            elif span_type == "tool_call":
                batches["tool_call"].append(parsed)
            elif span_type == "session":
                batches["session"].append(parsed)
            else:
                batches["trace"].append(parsed)
```

**`isinstance(data, list)` 是啥**：三元表达式，data 是 list 就用 data，不是就包成 `[data]`。保证 items 永远是 list。

**关键认知**：`append` 进桶的是 `parsed`（解析后的 dict），不是原始 `item`。parse 在 append 之前就执行了！

#### 4c. 检查每个桶，满了就刷盘

```python
        for key, batch in batches.items():
            if len(batch) >= BATCH_SIZE:
                await flush_map[key][1](batch)
                batches[key] = []
```

**`for key, batch in batches.items()` 拆解**：

`batches.items()` 是字典方法，返回 [(key, value), ...]。`for key, batch` 是解构赋值，一次性把 (key, value) 拆开。

| 变量 | 是什么 | 具体值 |
|------|--------|--------|
| `key` | 桶名（字符串） | "trace" / "metrics" / ... |
| `batch` | 桶里的 list | [span1, span2, ...] |

**注意**：这里的 `batches.items()` 是字典方法，跟上面的 `items` 变量完全无关。

**`flush_map[key][1](batch)` 拆解**（假设 key="trace"）：

```python
flush_map["trace"]        # → (insert_traces, flush_traces)
                           #    索引 [0]        索引 [1]
flush_map["trace"][1]     # → flush_traces  ← 取第二个（索引 1）
flush_map["trace"][1](batch)  # → flush_traces(batch)  ← 调用它
```

**索引对照**：
| 索引 | 函数 | 干啥 |
|------|------|------|
| `[0]` | `insert_traces` | ClickHouse 直接写入函数 |
| `[1]` | `flush_traces` | 带重试的刷盘（内部调 `_retry_insert`） |

用 `[1]` 因为要调带重试逻辑的 flush 函数。

#### 4d. 超时处理

```python
    except asyncio.TimeoutError:
        pass   # 超时后走定期刷新逻辑
```

5 秒没消息 → 超时 → 跳过拉取 → 直接走 4c 检查桶（可能桶里有残留，强制刷盘）。

**`pass` = 空操作**（啥也不干），Java 里 `{}` 空块。

### ⑤ 异常处理 + finally

```python
    except asyncio.CancelledError:           # ← 被 cancel 了
        logger.info("Consumer loop cancelled")
    except Exception as e:                  # ← 其他异常
        logger.error(f"Error in consumer loop: {e}")
        raise
    finally:                                 # ← 不管怎样都执行
        for key, batch in batches.items():
            if batch:                        # 桶里还有残留？
                await flush_map[key][1](batch)   # ← 强制刷盘！
                logger.info(f"Flushed remaining {len(batch)} {key} on shutdown")
```

**finally 干啥**：关闭前把桶里没刷的数据强制写 ClickHouse。否则 Consumer 被停 → 桶里 30 条没满 50 → 没刷盘 → 数据丢了。

**finally 保证不丢数据**：桶里有 30 条 → Consumer 要停了 → finally 强制 flush → 30 条写入 → 安全退出。

### 两个触发刷盘的条件

| 条件 | 含义 | 场景 |
|------|------|------|
| `len(batch) >= 50` | 桶满 50 条 | 高峰期数据多，很快满 |
| `FLUSH_INTERVAL = 5.0` 超时 | 5 秒没新消息 | 低谷期数据稀疏，避免积压 |

---

## 块 4：数据解析（parse_item + 5 个 parse_*）

### parse_item：调度器（路由到对应 parse）

```python
def parse_item(item: Dict[str, Any]) -> Dict[str, Any]:
    """将原始 item 解析为目标表字段"""
    span_type = item.get("span_type", "trace")
    parse_fn = PARSE_MAP.get(span_type, parse_trace)
    return parse_fn(item)
```

用 PARSE_MAP 路由到对应 parse 函数（策略模式）：

```python
PARSE_MAP = {
    "trace": parse_trace,
    "llm_metrics": parse_llm_metrics,
    "prompt": parse_prompt,
    "tool_call": parse_tool_call,
    "session": parse_session,
}
```

### `item["key"]` vs `item.get("key", default)`

| 写法 | 区别 | 找不到时 |
|------|------|---------|
| `item["trace_id"]` | 直接取 | 报错 KeyError（崩） |
| `item.get("trace_id", "")` | 取不到给默认 | 返回 ""（不崩） |

**必填字段用 `[]`**：没有就说明数据坏了，直接崩掉好过存脏数据。
**可选字段用 `.get()`**：取不到给默认值，容错。

### parse_trace（最简单）

```python
def parse_trace(item):
    return {
        "trace_id": item["trace_id"],
        "span_id": item["span_id"],
        "parent_span_id": item.get("parent_span_id", ""),
        "name": item.get("name", ""),
        "start_time": item.get("start_time", ""),
        "end_time": item.get("end_time", ""),
        "attributes": json.dumps(item.get("attributes", {})),
    }
```

`json.dumps(attributes)` 把 dict 转成 JSON 字符串，因为 ClickHouse 存的是字符串，不能直接存 dict。

### parse_llm_metrics（最复杂，有成本计算）

```python
def parse_llm_metrics(item):
    attrs = item.get("attributes", {})
    model = item.get("model_name") or attrs.get("model_name", "unknown")
    input_tokens = item.get("input_tokens") or attrs.get("input_tokens", 0)
    output_tokens = item.get("output_tokens") or attrs.get("output_tokens", 0)
    return {
        "trace_id": item["trace_id"],
        "span_id": item["span_id"],
        "model_name": model,
        "prefill_ms": item.get("prefill_ms") or attrs.get("prefill_ms", 0),
        "decode_ms": item.get("decode_ms") or attrs.get("decode_ms", 0),
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "tps": item.get("tps") or attrs.get("tps", 0),
        "cost_usd": calculate_cost(model, input_tokens, output_tokens),
    }
```

**`item.get("xxx") or attrs.get("xxx", default)` 回退逻辑**：先从顶层取，取不到就从 attributes 里取，再取不到给默认值。

**为什么要两层回退**：SDK 上报的数据格式不统一，有的把 model_name 放顶层，有的放在 attributes 里。

### calculate_cost：算钱

```python
def calculate_cost(model_name: str, input_tokens: int, output_tokens: int) -> float:
    cost_map = {
        "gpt-4": (0.03, 0.06),           # 输入 $0.03/1k,输出 $0.06/1k
        "gpt-4-turbo": (0.01, 0.03),
        "gpt-3.5-turbo": (0.0005, 0.0015),
        "claude-3-opus": (0.015, 0.075),
        "claude-3-sonnet": (0.003, 0.015),
        "claude-3-haiku": (0.00025, 0.00125),
    }
    for key, (input_cost, output_cost) in cost_map.items():
        if key in model_name.lower():
            return (input_tokens / 1000 * input_cost) + (output_tokens / 1000 * output_cost)
    return (input_tokens / 1000 * 0.001) + (output_tokens / 1000 * 0.002)
```

**价格表**：6 个模型的价格（输入/输出 per 1k tokens）。
**匹配**：模型名包含 key 就匹配。
**兜底**：没匹配上的模型，用默认价 $0.001/$0.002。

**例子**：调 GPT-4，输入 1500 token，输出 800 token：
```
= (1500 / 1000 * 0.03) + (800 / 1000 * 0.06)
= 0.045 + 0.048
= 0.093 美元
```

**`for key, (input_cost, output_cost) in cost_map.items()`**：解构赋值，cost_map 的 value 是元组 `(0.03, 0.06)`，一次性拆成 2 个变量。

### 其他 parse 函数

| 函数 | 存什么 | 对应表 |
|------|--------|--------|
| `parse_prompt` | 用户问什么 + LLM 答什么 | prompt_logs |
| `parse_tool_call` | 工具入参/出参/耗时 | tool_calls |
| `parse_session` | 会话汇总（总 token/总成本/总步骤） | sessions |

---

## 块 5：flush_* 批量写入

```python
async def flush_traces(batch):    await _retry_insert(insert_traces, batch, "traces")
async def flush_metrics(batch):   await _retry_insert(insert_metrics, batch, "metrics")
async def flush_prompts(batch):   await _retry_insert(insert_prompts, batch, "prompts")
async def flush_tool_calls(batch):await _retry_insert(insert_tool_calls, batch, "tool_calls")
async def flush_sessions(batch):  await _retry_insert(insert_sessions, batch, "sessions")
```

5 个函数都是一行：调 `_retry_insert`，传 3 个参数。

**3 个参数**：
| 参数 | 是什么 |
|------|--------|
| `insert_traces` | 写入函数（ClickHouse 客户端的） |
| `batch` | 要写的数据（50 条已解析的） |
| `"traces"` | 表名（记日志用） |

**`_retry_insert` 干啥**：带重试的写入。调 `insert_traces` 写 ClickHouse，失败重试 3 次，3 次都失败丢弃。函数在 clickhouse/client.py 里。

**为什么要有 flush_* 这一层**：因为加了重试逻辑。insert_traces 直接写失败就崩，flush_traces 包了一层失败重试 3 次。

### 调用链

```
consume_loop
  ↓ 桶满 50 条
await flush_map["trace"][1](batch)
  = flush_traces(batch)                    ← 块 5
  = _retry_insert(insert_traces, batch)    ← 重试逻辑
  = insert_traces(batch)                   ← 真正写 ClickHouse
```

**3 层调用**：
1. `flush_map[key][1]` → 动态找到 flush 函数
2. `flush_traces` → 薄封装
3. `_retry_insert` → 重试 3 次 → `insert_traces` → 写 ClickHouse

---

## 完整流程图

```
consume_loop 启动
  ↓
准备 5 个桶 + flush_map
  ↓
┌─→ while True:
│   │
│   ├─ try:
│   │   await asyncio.wait_for(
│   │     _consumer.getone(),         从 Kafka 拉一条（最多等 5 秒）
│   │     timeout=5.0
│   │   )
│   │   data = msg.value
│   │   items = data if list else [data]
│   │   for item in items:
│   │     parsed = parse_item(item)    解析！（在 append 之前）
│   │     按 span_type 放到对应桶
│   │              │
│   ├─ except TimeoutError: pass       5 秒没消息，跳过
│   │              │
│   │              ↓
│   │   for key, batch in batches.items():
│   │     if len >= 50:
│   │       flush_map[key][1](batch)   刷盘！
│   │       batches[key] = []           清空
│   │              │
└──────────────────┘  循环
                   │
         (被 cancel 时跳出)
                   ↓
    finally:
      for 每个桶:
        if 有残留:
          flush_map[key][1](batch)      强制刷残留
                   ↓
               安全退出
```

---

## Producer vs Consumer 完整对比

| 对比项 | Producer | Consumer |
|--------|----------|----------|
| **身份** | Backend 接口的帮手 | 后台独立协程 |
| **触发** | 来一个 HTTP 请求调一次 | 一直死循环跑 |
| **动作** | `send()` 丢数据进 Kafka | `getone()` 从 Kafka 拉数据 |
| **阻塞** | 不阻塞 FastAPI | 独立协程，谁都不卡 |
| **落库** | ❌ 不碰 ClickHouse | ✅ 批量写 ClickHouse |
| **失败处理** | 记日志，不抛异常 | 重试 3 次，失败丢弃 |
| **批量** | 一次发一批（来自 SDK） | 攒 50 条或 5 秒写一次 |
| **分流** | 不分流，整批发 | 按 span_type 分 5 桶 |
| **全局变量** | `_producer` 1 个 | `_consumer` + `_consumer_task` 2 个 |

---

## 核心铁律（面试考点）

1. **Consumer 是真正落库的角色**，Producer 只丢数据进 Kafka
2. **5 个桶按 span_type 分流**，对应 5 张 ClickHouse 表
3. **两个刷盘条件**：桶满 50 条 OR 5 秒超时
4. **parse 在 append 之前执行**，桶里装的是解析后的数据
5. **flush 是薄封装**，真正重试逻辑在 _retry_insert
6. **3 次重试失败丢弃数据**（缺陷，生产要加死信队列）
7. **finally 强制刷残留**：关闭前不丢数据
8. **先停 Task 再 stop Consumer**：反过来会报错
9. **启动顺序**：Producer 先 → Consumer 后；**关闭顺序**：Consumer 先 → Producer 后

---

## Python vs Java 语法对照

| 概念 | Python | Java |
|------|--------|------|
| 后台任务 | `asyncio.create_task(coro)` | `new Thread(runnable).start()` |
| 取消任务 | `task.cancel()` | `thread.interrupt()` |
| 等待任务 | `await task` | `thread.join()` |
| 取消异常 | `asyncio.CancelledError` | `InterruptedException` |
| 字典遍历 | `for k, v in d.items()` | `for (Entry<K,V> e : map.entrySet())` |
| 解构赋值 | `for key, batch in ...` | `entry.getKey() / getValue()` |
| 三元表达式 | `x if cond else y` | `cond ? x : y` |
| 判类型 | `isinstance(data, list)` | `data instanceof List` |
| 空操作 | `pass` | `{}` |
| 默认值 | `dict.get(key, default)` | `map.getOrDefault(key, default)` |
| 元组 | `(f1, f2)` | 无（用 Pair 或数组） |
| 函数引用 | `parse_trace`（直接用） | `this::parseTrace` |
| 策略模式 | 字典 + 函数引用 | 接口 + 实现类 |
