# Agent 可观测性后端 · 学习笔记 04：kafka/producer.py（生产者）

> 📅 学习日期：2026-06-29
> 🎯 目标：理解 Kafka 生产者的初始化、配置参数、非阻塞投递、回调机制
> 👤 背景：Java 后端转 AI Agent，对照 Spring Boot KafkaTemplate 理解 AIOKafkaProducer

---

## 文件概述

`producer.py` 是**写入链路的 Kafka 投递端**。collect.py 收到 SDK 数据校验后，调 `send_batch()` 把数据丢进 Kafka。

**Java 类比**：相当于 Spring 的 `KafkaTemplate` 配置类 + 一个 Service 方法。

完整代码 88 行，分 4 块讲：
1. 导入 + 全局变量
2. init_producer（初始化）
3. close_producer（关闭）
4. send_batch（核心：非阻塞投递）

---

## Producer 在整个链路里的位置

```
SDK → Backend(collect) → 【Producer → Kafka】→ Consumer → ClickHouse
                         ↑ 你在这里
```

**Producer 不落库！只丢数据进 Kafka，快速返回。真正落库的是 Consumer。**

---

## 块 1：导入 + 全局变量

### 下划线命名习惯

Python 的 PEP 8 规范规定变量名和函数名用下划线命名（snake_case），不用驼峰。

| 命名 | 含义 | 类比 Java |
|------|------|-----------|
| `producer` | 普通变量，谁都能用 | `public` |
| `_producer` | 约定为内部使用 | `private`（但 Python 不强制） |
| `__producer` | 双下划线，改名混淆 | `private` + 改名 |
| `__producer__` | 前后双下划线，魔术方法 | 无 |

**关键**：Python 没有真正的 private（不像 Java 有访问修饰符）。`_producer` 只是约定，技术上能从外部访问但不规范。

### None 是什么

```python
_producer: AIOKafkaProducer = None
```

`None` 是 Python 的空值，等于 Java 的 `null`。不是 "no"。

**为什么要先赋 None**：模块加载时还没创建 producer，先占位。等 `init_producer()` 调用时才真正赋值。

### 全局变量

```python
_producer: AIOKafkaProducer = None   # 全局单例，启动前是空的
```

跟 Spring 的单例 Bean 类似，但 FastAPI 没容器，手动用全局变量实现单例。

---

## 块 2：init_producer（初始化）

```python
async def init_producer() -> None:
    """初始化 Kafka 生产者"""
    global _producer
    try:
        _producer = AIOKafkaProducer(
            bootstrap_servers=settings.kafka_bootstrap_servers,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            acks=1,
            max_batch_size=16384,
            linger_ms=10,
            compression_type="gzip",
        )
        await _producer.start()
        logger.info(f"Kafka producer started, servers: {settings.kafka_bootstrap_servers}")
    except Exception as e:
        logger.error(f"Failed to start Kafka producer: {e}")
        raise
```

### 1. `global _producer` 声明

Python 函数内部想修改模块级变量，必须声明 `global`，否则 Python 会以为你在函数里新建一个同名局部变量。

**Java 类比**：Java 里 `static` 字段在方法里直接赋值就行，不需要声明。Python 必须显式 `global`。

### 2. try/except 异常捕获

| Python | Java | 含义 |
|--------|------|------|
| `try:` | `try {` | 开始捕获异常 |
| `except Exception as e:` | `catch (Exception e)` | 捕获异常 |
| `raise` | `throw e;` | 重新抛出（记完日志再抛） |

**为什么要 catch 了再 raise**：为了记日志。catch 一下打个 error 日志，再 raise 让上层（FastAPI 启动流程）感知到启动失败。

**Fail Fast 原则**：Kafka 启动失败 → 整个应用启动失败，带病不上线。

### 3. Kafka 配置参数详解

#### `value_serializer` —— 序列化器

```python
value_serializer=lambda v: json.dumps(v).encode("utf-8"),
```

把 Python 对象转成字节流，才能发到 Kafka：
- `lambda` = 匿名函数（= Java `(v) -> ...`）
- `json.dumps(v)` = dict 转 JSON 字符串（= Java `ObjectMapper.writeValueAsString`）
- `.encode("utf-8")` = 字符串转字节流（Kafka 只收 bytes）

**Java 类比**：
```java
props.put("value.serializer", new JsonSerializer());  // 用现成的
// 或自己写 Serializer 类
```

#### `acks=1` —— 确认级别

| acks 值 | 含义 | 可靠性 | 性能 |
|---------|------|--------|------|
| `acks=0` | 发出去就不管 | 最低（可能丢） | 最快 |
| `acks=1` | 只要 Leader 确认就行（默认） | 中等 | 中 |
| `acks=all`(-1) | Leader + 所有 ISR 副本都确认 | 最高（不丢） | 最慢 |

**为什么用 `acks=1`**：可观测性数据不是钱（丢了能容忍），追求性能不拖累 FastAPI。

#### `max_batch_size=16384` —— 批次大小

Kafka 批量发送的核心机制。Producer 攒到 16KB 才发，或攒到 `linger_ms`（10ms）也发。

**Java Kafka Client**：`BATCH_SIZE_CONFIG`，默认也是 16384。

#### `linger_ms=10` —— 等待时间

Producer 发送一条消息后，最多等 10ms 看能不能再攒几条一起发（凑批）。用 10ms 延迟换 4 倍吞吐量。

**Java 类比**：`LINGER_MS_CONFIG`，一模一样。

#### `compression_type="gzip"` —— 压缩

对整个批次压缩后再发，减少网络 IO 70%+。

| 压缩 | 压缩率 | CPU | 适用 |
|------|--------|-----|------|
| none | 不压 | 0 | 追求最低延迟 |
| gzip | 高 | 高 | 网络贵、CPU 富余 |
| snappy | 中 | 低 | 平衡 |
| lz4 | 中 | 极低 | 推荐 |

### 4. `await _producer.start()` —— 真正连接 Kafka

`start()` 内部要做：解析地址 → 建 TCP → 握手 → 拉 metadata。这些都是网络 IO，`await` 让 FastAPI 在等的时候让出 CPU，别的请求还能进来。

**没有 await（同步版本）**：整个应用卡住，直到 Kafka 连上。

### 5. 序列化器注入机制（不是接口重写）

**关键认知**：`send` 不是抽象方法，是已经实现好的具体方法。序列化器在**创建对象时**传入，存在对象内部，`send` 调用时自动读 `self.value_serializer`。

```python
# 创建时注入
_producer = AIOKafkaProducer(
    value_serializer=lambda v: json.dumps(v).encode("utf-8"),
    ...
)
# send 内部自动用
async def send(self, topic, value):
    serialized = self.value_serializer(value)   # ← 自动调用存的 lambda
```

**Java 等价**：`props.put(VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class)`，构造时指定。

**想换序列化器**：改 init_producer 里 `value_serializer=` 这一行。

---

## 块 3：close_producer（关闭）

```python
async def close_producer() -> None:
    """关闭 Kafka 生产者"""
    global _producer
    if _producer:
        await _producer.stop()
        _producer = None
        logger.info("Kafka producer stopped")
```

### `if _producer:` 惯用判空

Python 的 `if 变量:` 是惯用写法，等价 `if 变量 is not None:`。

### `await _producer.stop()` 异步关闭

stop() 要做的事：flush 缓冲区（把还没发出去的批次强制发完）→ 断开 TCP → 释放资源。

**为什么不能省略**：不然缓冲区消息丢失 + Kafka Broker 资源泄漏。

**Java 类比**：`@PreDestroy` 自动调 `producer.close()`。

---

## 块 4：send_batch（核心：非阻塞投递）

```python
async def send_batch(data: List[Dict[str, Any]]) -> None:
    """
    投递一批数据到 Kafka（非阻塞）
    - 使用 send() 而非 send_and_wait()
    - 回调中记录成功/失败，不阻塞 FastAPI handler
    - 不会 raise 异常，保 Collector 的快速返回路径不受影响
    """
    if not _producer:
        raise RuntimeError("Kafka producer not initialized")

    batch_size = len(data)
    fut = await _producer.send(
        settings.kafka_topic,
        value=data,
    )
    fut.add_done_callback(
        lambda f: (
            _on_send_success(f.result(), batch_size)
            if f.exception() is None
            else _on_send_error(f.exception(), batch_size)
        )
    )
```

### send_batch vs send：谁是谁

| 方法 | 谁写的 | 在哪 | 干啥 |
|------|--------|------|------|
| **send_batch** | 项目自己写的 | producer.py | 包装层，加业务逻辑 |
| **send** | Kafka 库提供的 | AIOKafkaProducer 类内部 | 真正发数据给 Kafka |

**关系**：send_batch 内部调用 send。send_batch 是壳，send 是芯。

### 逐行拆解

#### ① 判空

```python
if not _producer:
    raise RuntimeError("Kafka producer not initialized")
```

producer 没初始化就抛错。`RuntimeError` = Java 的 `RuntimeException`。

#### ② 获取长度

```python
batch_size = len(data)
```

记下这批数据有多少条，后面回调记日志要用。

#### ③ 异步发送

```python
fut = await _producer.send(
    settings.kafka_topic,   # ← topic 名字，值="agent-spans"
    value=data,             # ← 要发的数据
)
```

**`settings.kafka_topic`**：从 config.py 读的 topic 名，值是 `"agent-spans"`。Kafka topic 类比快递分拣口，数据投到哪个口。

**`fut` 是什么**：Future 对象，代表"一个还没完成的异步任务"。

send 方法内部干的事：
```
① 序列化 data → bytes（快，CPU 操作）
② 把 bytes 放进发送缓冲区（快，内存操作）
③ 把"发送任务"交给后台 IO 线程（不等它完成）
④ 返回一个 Future 对象 ← fut 就是这个
```

**关键**：send 不会等 Kafka 真正确认，它把任务丢给后台就返回了。

**为什么 fut 能用 add_done_callback**：因为 send 返回的就是 Future 对象，Future 类自带 add_done_callback 方法。跟 Java 的 `Future.addCallback()` 一样。

#### ④ 注册回调

```python
fut.add_done_callback(
    lambda f: (
        _on_send_success(f.result(), batch_size)
        if f.exception() is None
        else _on_send_error(f.exception(), batch_size)
    )
)
```

等价于：
```python
def 回调函数(f):
    if f.exception() is None:                  # 没异常 = 成功
        metadata = f.result()                  # 拿结果（topic/分区/offset）
        _on_send_success(metadata, batch_size)  # 记 debug 日志
    else:                                      # 有异常 = 失败
        exc = f.exception()
        _on_send_error(exc, batch_size)        # 记 error 日志
```

**执行时机**：回调在 send_batch 返回后才执行（当 Kafka 真正确认后），此时 collect.py 早就返回 202 给 SDK 了。

### 为什么用 send() 不用 send_and_wait()

| 方法 | 行为 | 后果 |
|------|------|------|
| `send_and_wait()` | 发完等 Kafka 确认才返回 | 阻塞 FastAPI，高并发退化为串行 |
| `send()` | 发出去立即返回 Future | 不阻塞，FastAPI 立即返回 202 |

### 为什么不抛异常

即使 Kafka 发送失败，send_batch 也不抛异常，只在回调里记 error 日志。因为 collect.py 调用 send_batch 后要返回 202 给 SDK，如果这里抛异常，collect.py 会返回 500，SDK 会重试——但数据其实已经丢进 Kafka 了（只是确认失败），重试会重复发送。

**设计权衡**：牺牲"SDK 感知失败"换"快速返回不阻塞"。

### 回调函数

```python
def _on_send_success(metadata, batch_size: int) -> None:
    """发送成功回调"""
    logger.debug(f"Kafka send OK: topic={metadata.topic}, ...")

def _on_send_error(exc, batch_size: int) -> None:
    """发送失败回调（仅记录日志，SDK 侧有重试机制兜底）"""
    logger.error(f"Kafka send FAILED after async write, ...")
```

成功记 debug，失败记 error。只记日志，不通知 SDK。

---

## 完整调用链

```
collect.py 收到 HTTP 请求
  ↓
await send_batch(data)              ← 项目自己写的方法
  ↓
① if not _producer: raise          ← 判空
② fut = await _producer.send()      ← Kafka 库提供的方法
   ├─ 序列化 data → bytes
   ├─ bytes 放进缓冲区
   └─ 返回 Future
③ fut.add_done_callback(...)        ← 注册回调
  ↓
返回 collect.py → 返回 202 给 SDK
  ↓
（10ms 后）Kafka 确认完成 → Future 完成 → 自动调回调
  ├─ 成功 → _on_send_success() → 记 debug 日志
  └─ 失败 → _on_send_error() → 记 error 日志
```

---

## Java 等价实现（对照参考）

```java
@Configuration
public class KafkaProducerConfig {
    private static KafkaTemplate<String, Object> producer;

    @PostConstruct   // ← 对应 init_producer
    public void initProducer() {
        try {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
            props.put(ProducerConfig.ACKS_CONFIG, "1");
            props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
            props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
            props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip");
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

            DefaultKafkaProducerFactory<String, Object> factory =
                new DefaultKafkaProducerFactory<>(props);
            producer = new KafkaTemplate<>(factory);
            log.info("Kafka producer started");
        } catch (Exception e) {
            log.error("Failed to start Kafka producer: {}", e.getMessage());
            throw new RuntimeException(e);   // ← 对应 raise
        }
    }

    @PreDestroy   // ← 对应 close_producer
    public void closeProducer() {
        if (producer != null) {
            producer.flush();
            producer.close();
            producer = null;
        }
    }

    // = send_batch
    public void sendBatch(List<Map<String, Object>> data) {
        if (producer == null) throw new RuntimeException("not initialized");
        int batchSize = data.size();
        producer.send(new ProducerRecord<>("agent-spans", data), new Callback() {
            @Override
            public void onCompletion(RecordMetadata meta, Exception ex) {
                if (ex == null) onSendSuccess(meta, batchSize);
                else onSendError(ex, batchSize);
            }
        });
    }
}
```

| Python | Java |
|--------|------|
| `send_batch()` | `sendBatch()` |
| `_producer.send()` | `producer.send()` |
| `settings.kafka_topic` | `"agent-spans"` |
| `fut`（Future） | `Future<RecordMetadata>` |
| `fut.add_done_callback()` | `Callback.onCompletion()` |
| `f.exception()` | `Exception ex` |
| `f.result()` | `RecordMetadata meta` |

---

## 核心铁律（面试考点）

1. **Producer 不落库**，只丢数据进 Kafka，快速返回
2. **Producer 是被动的**：来一个 HTTP 请求调一次 send_batch
3. **`global` 声明**：Python 函数内改全局变量必须显式声明
4. **序列化器在构造时注入**，send 内部自动调用，不是接口重写
5. **`send()` 非阻塞**：丢进缓冲区立即返回，不等 Kafka 确认
6. **`send_and_wait()` 阻塞**：高并发下不能用
7. **失败不抛异常**：只记日志，避免 SDK 误重试导致重复发送
8. **回调只记日志**：不通知 SDK
9. **Kafka 配置 4 参数**：acks=1 / max_batch_size=16384 / linger_ms=10 / compression=gzip
10. **Fail Fast**：启动失败 → 应用启动失败

---

## Python vs Java 语法对照

| 概念 | Python | Java |
|------|--------|------|
| 空值 | `None` | `null` |
| 运行时异常 | `RuntimeError` | `RuntimeException` |
| 抛异常 | `raise` | `throw` |
| 重新抛出 | `raise`（无参数） | `throw e;` |
| 判空 | `if not _producer:` | `if (producer == null)` |
| 判真值 | `if _producer:` | `if (producer != null)` |
| 匿名函数 | `lambda v: ...` | `(v) -> ...` |
| 全局变量声明 | `global _producer` | 不需要 |
| 异步 | `async/await` | `CompletableFuture` / Reactor |
| 序列化 | `json.dumps(v).encode()` | `ObjectMapper.writeValueAsBytes()` |
| Future 回调 | `fut.add_done_callback()` | `future.addCallback()` |
