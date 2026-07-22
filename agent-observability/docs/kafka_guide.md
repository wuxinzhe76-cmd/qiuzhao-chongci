# Kafka 指南

---

## 一、Kafka 为什么会出现

### 一个真实业务场景

以电商下单为例：

```
用户下单
  ↓
订单系统
  ├── 扣库存
  ├── 创建物流
  ├── 发送短信
  ├── 增加积分
  └── 同步数据仓库
```

如果全部同步调用，问题很明显：
- 响应时间越来越长
- 一个系统故障可能导致整个业务失败
- 系统之间耦合严重
- 后续增加新业务系统成本很高

所以要把非核心流程拆出来异步处理。

### Kafka 怎么解决

订单系统只负责核心业务，完成后发一条消息：

```
订单系统
  ↓
Kafka
  ├── 库存系统
  ├── 物流系统
  ├── 短信系统
  ├── 积分系统
  └── 数据仓库
```

各个系统按自己的需要消费消息。耦合降低了，吞吐也上去了。

### 回到 Agent-Insight

同样的道理。假设没有 Kafka，架构是这样的：

```
SDK 采集 → FastAPI 接收 → 直接写 ClickHouse
```

问题：
- ClickHouse 擅长批量写入，不擅长单条高频写入。1000 个 Agent 同时上报，FastAPI 直接写 ClickHouse 会被拖垮
- ClickHouse 临时故障（重启、升级），数据要么丢失，要么 FastAPI 得自己做重试缓存
- 以后想加告警服务、数据分析服务，FastAPI 得同时往三个地方写

加一层 Kafka：

```
SDK 采集 → FastAPI 接收 → Kafka → Consumer → ClickHouse
                                    ↓
                              告警服务（未来）
                                    ↓
                              数据分析（未来）
```

Kafka 做了三件事：缓冲、解耦、容错。

### 本章小结

Kafka 本质上是一个事件总线。它的价值不是存消息，而是让多个系统通过事件通信。

---

## 二、整体架构

Kafka 由这几个角色组成：

```
Producer → Topic → Partition → Broker → Consumer
```

| 组件 | 作用 |
|------|------|
| Producer | 发送消息 |
| Consumer | 消费消息 |
| Topic | 消息分类 |
| Partition | Topic 的分区，也是数据存储单位 |
| Broker | Kafka 服务节点 |
| Consumer Group | 消费者组，实现并发消费 |

---

## 三、Topic 与 Partition

### Topic

Topic 就是消息的分类。比如：

```
order     payment     user     log
```

订单数据发到 `order`，支付数据发到 `payment`。

项目里用的是 `agent_insight_spans`，所有上报数据都进这个 Topic。

### 为什么需要 Partition

如果一个 Topic 只有一个 Partition：

```
Topic(order)
  ↓
Partition 0
```

所有数据写同一个文件，数据量大了就是瓶颈。

所以 Kafka 把 Topic 拆成多个 Partition：

```
Topic(order)
  ↓
P0   P1   P2   P3
```

Producer 把消息分散到不同 Partition，支持并发读写。

### Partition 的两个作用

**第一，提高吞吐量。** 多个 Partition 同时写入。

**第二，保证局部有序。** Kafka 只保证同一个 Partition 内消息有序。所以相同业务的数据通常按 Key 落到同一个 Partition。比如：

```
user1 → P0
user1 → P0
user1 → P0
```

user1 的所有消息都进 P0，顺序不会乱。

### 本章小结

Topic 组织消息，Partition 提高吞吐量并保证局部有序。

---

## 四、Broker、Leader 与 Follower

Broker 就是一台 Kafka 服务器，一个 Broker 可以保存多个 Partition。

```
Broker1
  ├── P0
  ├── P1
  └── P2
```

为了保证数据可靠，每个 Partition 有多个副本：

```
P0
  ├── Leader
  ├── Follower
  └── Follower
```

注意：Leader 和 Follower 描述的是 Partition 副本的角色，不是 Broker 的角色。同一个 Broker 可以同时拥有 Leader 和 Follower：

```
Broker1
  ├── P0 Leader
  ├── P1 Follower
  └── P2 Leader
```

### Leader

唯一对外提供服务的副本：
- 接收 Producer 写入
- 提供 Consumer 读取
- 管理 Follower 同步

### Follower

不处理客户端请求，只做两件事：
- 同步 Leader 的数据
- Leader 故障时接管服务

### 本章小结

Leader 负责读写，Follower 负责同步和容灾。Kafka 的高可用靠的是副本机制。

---

## 五、Message、Log 与 Offset

### Message

Producer 发送的就是 Message：

```json
{
  "orderId": 1001
}
```

### Log

Kafka 收到 Message 后，追加到 Partition 中：

```
Partition
  Offset 0   MessageA
  Offset 1   MessageB
  Offset 2   MessageC
```

这些 Message 顺序排列，就是 Kafka 说的 Log。注意这里的 Log 不是运行日志，而是一组按顺序存储的消息。

### Offset

Offset 是消息在 Partition 中的位置编号：

```
Offset 0
Offset 1
Offset 2
```

Consumer 会记录自己消费到哪个 Offset，程序重启后可以继续消费，而不是从头开始。

### 本章小结

Partition 保存一系列 Message，顺序排列形成 Log，Offset 标识消息位置和消费进度。

---

## 六、一条消息的生命周期

```
Producer
  ↓
Topic
  ↓
Partition
  ↓
Leader
  ↓
写入 Commit Log
  ↓
Follower 同步
  ↓
Consumer 消费
  ↓
提交 Offset
```

这就是 Kafka 所有核心概念之间的关系。

---

## 七、Kafka 为什么性能高

几个关键设计：

- **顺序写磁盘**：消息追加到文件末尾，避免随机 IO
- **Partition 水平扩展**：数据分散到多个 Partition，并行处理
- **批量发送**：多条消息打包发送，减少网络开销
- **零拷贝**：用 sendfile 系统调用，数据从磁盘直接到网卡，不经过用户空间，降低 CPU 消耗
- **PageCache**：利用操作系统的文件缓存，减少 JVM 垃圾回收
- **Pull 模型**：Consumer 主动拉取，便于控制消费速度

这些设计共同决定了 Kafka 可以支撑非常高的吞吐量。

---

## 八、典型应用场景

| 场景 | 作用 |
|------|------|
| 异步处理 | 下单后发短信、发优惠券 |
| 削峰填谷 | 双十一订单缓冲 |
| 日志采集 | 前端埋点、应用日志 |
| 实时计算 | Flink 消费 Kafka 数据 |
| 数据同步 | MySQL Binlog → Kafka → ES |
| AI Agent | Agent 事件采集、Trace、Metrics |

---

## 九、在 Agent-Insight 项目中的角色

### 数据流转

```
SDK → FastAPI → Kafka → Consumer → ClickHouse
      (50ms)  (写入)  (缓冲)  (批量)  (存储)
```

### FastAPI 端（Producer）

```python
# backend/app/kafka/producer.py
async def send_batch(data: List[Dict[str, Any]]) -> None:
    """投递一批数据到 Kafka（非阻塞）"""
    fut = await _producer.send(settings.kafka_topic, value=data)
    fut.add_done_callback(
        lambda f: _on_send_success(f.result(), len(data))
        if f.exception() is None
        else _on_send_error(f.exception(), len(data))
    )
```

关键点：
- 用 `send()` 而不是 `send_and_wait()`，不阻塞 FastAPI
- 回调记录成功/失败，不抛异常
- FastAPI 收到数据后立即返回 202 Accepted

### Consumer 端

```python
# backend/app/kafka/consumer.py
async def consume_loop() -> None:
    batches = {"trace": [], "metrics": [], "prompt": [], ...}
    
    while True:
        msg = await asyncio.wait_for(_consumer.getone(), timeout=5.0)
        # 解析数据，按类型分到不同 batch
        
        if len(batch) >= 50:  # 攒够 50 条
            await flush_to_clickhouse(batch)
```

关键点：
- 批量消费，攒够 50 条或每 5 秒刷一次
- 按 `span_type` 分流到不同表
- 写入 ClickHouse 失败会重试（指数退避）

### 为什么选 Kafka

| 考量 | 直接写 | 用 Kafka |
|------|--------|---------|
| 响应时间 | 受 ClickHouse 影响 | 毫秒级 |
| 下游故障 | 数据丢失 | 数据暂存 |
| 扩展性 | 改代码 | 加 Consumer |
| 吞吐量 | 受最慢环节限制 | 高吞吐 |

### 配置要点

```yaml
# docker-compose.yml
kafka:
  image: confluentinc/cp-kafka:7.5.0
  environment:
    KAFKA_PROCESS_ROLES: broker,controller  # KRaft 模式，不用 Zookeeper
    KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:29093
```

项目用 KRaft 模式（Kafka 3.x 新特性），不需要 Zookeeper，部署更简单。

---

## 十、动手体验

### 启动 Kafka

```bash
docker-compose up -d kafka
```

### 查看 Topic

```bash
docker exec -it kafka bash

# 列出所有 Topic
kafka-topics --bootstrap-server localhost:9092 --list

# 查看 Topic 详情
kafka-topics --bootstrap-server localhost:9092 --describe --topic agent_insight_spans
```

### 手动发消息

```bash
# 启动控制台生产者
kafka-console-producer --bootstrap-server localhost:9092 --topic test_topic

# 输入消息（每行一条）
{"test": "hello"}
{"test": "world"}
```

### 手动消费消息

```bash
# 另一个终端
kafka-console-consumer --bootstrap-server localhost:9092 --topic test_topic --from-beginning
```

### 用 Python 测试

```python
# 生产者
from aiokafka import AIOKafkaProducer
import asyncio, json

async def send():
    producer = AIOKafkaProducer(
        bootstrap_servers='localhost:9092',
        value_serializer=lambda v: json.dumps(v).encode('utf-8')
    )
    await producer.start()
    await producer.send('test_topic', {'message': 'hello'})
    await producer.stop()

asyncio.run(send())

# 消费者
from aiokafka import AIOKafkaConsumer
import asyncio

async def consume():
    consumer = AIOKafkaConsumer(
        'test_topic',
        bootstrap_servers='localhost:9092',
        auto_offset_reset='earliest'
    )
    await consumer.start()
    try:
        async for msg in consumer:
            print(msg.value)
    finally:
        await consumer.stop()

asyncio.run(consume())
```

---

## 十一、常见问题

**Q：Kafka 会丢消息吗？**

默认配置下可能丢。要保证不丢：
- Producer：`acks=all`（所有副本确认）
- Broker：`replication.factor >= 3`（多副本）
- Consumer：手动提交 offset（处理完再提交）

项目里用的是 `acks=1`（Leader 确认就行），因为日志数据丢几条影响不大，优先保证性能。

**Q：Kafka 和 RabbitMQ 有什么区别？**

|  | Kafka | RabbitMQ |
|--|-------|---------|
| 设计目标 | 流式处理、日志 | 消息队列 |
| 吞吐量 | 百万级/秒 | 万级/秒 |
| 消息保留 | 保留一段时间 | 消费完就删 |
| 顺序保证 | Partition 内有序 | 单队列有序 |
| 适用场景 | 日志、流数据 | 任务队列、RPC |

**Q：Consumer Group 是什么？**

一组 Consumer 共同消费一个 Topic。同一个 Group 里，每个 Partition 只被一个 Consumer 消费。不同 Group 之间互不影响，都可以消费全部数据。

项目里用 `agent_insight_group` 这个 Group，多个 Consumer 实例会自动分摊 Partition。

**Q：KRaft 模式是什么？**

Kafka 3.x 的新特性，用 Raft 协议管理集群元数据，替代了 Zookeeper。部署更简单，性能更好。

项目里 `KAFKA_PROCESS_ROLES: broker,controller` 就是 KRaft 模式，一个节点同时做 Broker 和 Controller。

---

## 十二、延伸阅读

| 资源 | 链接 |
|------|------|
| Kafka 官方文档 | https://kafka.apache.org/documentation |
| Kafka 设计原理 | https://kafka.apache.org/documentation/#design |
| 项目中的 Producer | `backend/app/kafka/producer.py` |
| 项目中的 Consumer | `backend/app/kafka/consumer.py` |

---

Kafka 是一个高性能的消息队列，在 Agent-Insight 项目中起到了缓冲、解耦、容错的作用。FastAPI 收到 SDK 上报的数据后，直接扔给 Kafka 就返回，不用等 ClickHouse 写完。Consumer 按自己的节奏从 Kafka 取数据，批量写入 ClickHouse。这样即使 ClickHouse 临时挂了，数据也不会丢。
