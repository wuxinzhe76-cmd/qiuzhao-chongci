# Agent 可观测性后端 · 学习笔记 03：api/collect.py（数据收集入口）

> 📅 学习日期：2026-06-29
> 🎯 目标：理解数据收集接口的校验、202 Accepted、Kafka 投递
> 👤 背景：Java 后端转 AI Agent，对照 Spring Boot 理解 FastAPI

---

## 文件概述

`collect.py` 是整个**写入链路的起点**。SDK 攒一批 span 数据，POST 到 `/api/v1/collect`，这个文件处理。

**Java 类比**：一个 `@RestController` 类，里面有个 `@PostMapping("/collect")` 方法。

完整代码 162 行，拆 4 块讲。

---

## 块 1：导入 + router 创建

```python
import logging
from typing import Any, Dict, List

from fastapi import APIRouter, HTTPException, status

from ..kafka.producer import send_batch

logger = logging.getLogger(__name__)
router = APIRouter()
```

### 导入三类东西

| 导入 | 作用 | Java 类比 |
|------|------|-----------|
| `logging` | 日志 | SLF4J `LoggerFactory.getLogger()` |
| `APIRouter` | 创建路由对象 | `@RestController` |
| `HTTPException` | 抛 HTTP 错误响应 | `ResponseStatusException` |
| `status` | HTTP 状态码常量 | `HttpStatus` 枚举 |
| `send_batch` | 投递 Kafka | `KafkaTemplate.send()` |

### 相对导入

`from ..kafka.producer` 的 `..` = 上一级目录（app/），即 `app/kafka/producer.py`。

---

## 块 2：必填字段定义 + validate_item 校验函数

### REQUIRED_FIELDS

```python
REQUIRED_FIELDS = {
    "trace": ["trace_id", "span_id", "name", "start_time", "end_time"],
    "llm_metrics": ["trace_id", "span_id"],
    "prompt": ["trace_id", "span_id"],
    "tool_call": ["trace_id", "span_id"],
    "session": ["session_id", "trace_id"],
}
```

字典定义每种 span_type 的必填字段。Java 类比 `Map<String, List<String>>`。

**为什么 trace 要求最多（5 个）**：要还原链路树，必须知道是谁、什么时候开始结束。
**其他类型只要 2 个**：trace_id + span_id 能关联到 trace 表即可。

### validate_item 函数

```python
def validate_item(item: Dict[str, Any]) -> None:
    if not isinstance(item, dict):           # ① 检查是不是 dict
        raise HTTPException(400, "...")

    span_type = item.get("span_type", "trace")  # ② 拿 span_type，默认 trace

    required = REQUIRED_FIELDS.get(span_type, REQUIRED_FIELDS["trace"])  # ③ 查必填字段
    missing = [f for f in required if f not in item]                     # ④ 挑缺失字段
    if missing:                                                          # ⑤ 有缺失就报错
        raise HTTPException(400, f"Missing required fields: {missing}")
```

### Python 语法点

| 语法 | 含义 | Java 类比 |
|------|------|-----------|
| `isinstance(item, dict)` | 判断类型 | `item instanceof Map` |
| `item.get("key", default)` | 取值，有默认值 | `getOrDefault()` |
| `[f for f in required if f not in item]` | 列表推导式 | Stream filter |
| `if missing:` | 空列表 = false | `!missing.isEmpty()` |
| `f"..."` | f-string 字符串插值 | `String.format()` |

### Java Bean Validation 对比

本项目用**手动校验**（if 判断 + 抛异常），因为 5 种 span_type 必填字段不同，一个 DTO 搞不定。Java 通常用 `@NotBlank` 注解，适合固定结构。

---

## 块 3：collect_data 主函数（核心）

```python
@router.post("/collect", status_code=status.HTTP_202_ACCEPTED)
async def collect_data(data: List[Dict[str, Any]]):
```

### 函数签名

| 部分 | 含义 | Java 类比 |
|------|------|-----------|
| `@router.post("/collect")` | 注册 POST 接口 | `@PostMapping("/collect")` |
| `status_code=202` | 成功返回 202 | `@ResponseStatus(ACCEPTED)` |
| `async def` | 异步函数 | 无直接对应（CompletableFuture） |
| `data: List[Dict[str, Any]]` | 参数 + 类型注解 | `@RequestBody List<Map<String, Object>>` |

FastAPI 根据类型注解自动：
1. 解析 JSON body → Python `List[Dict]`
2. 校验类型（不是数组自动返回 422）

### 为什么返回 202 而不是 200

| 状态码 | 含义 | 什么时候用 |
|--------|------|-----------|
| 200 OK | 事情做完了 | 查询接口（数据已查到） |
| **202 Accepted** | 请求已接受，但还没处理完 | **异步任务**（数据收到但没写库） |

**本接口没有写数据库**，只把数据丢给 Kafka。真正写 ClickHouse 是后台 Consumer。所以返回 202 = "收到了，排队中，你不用等"。

### 函数体流程

```python
# ① 基础校验
if not data:                          # 空数组 → 400
    raise HTTPException(400, "Empty data array")

if not isinstance(data, list):        # 非 list → 400（双重保险）
    raise HTTPException(400, "Must be a JSON array")

# ② 逐条业务校验
for i, item in enumerate(data):
    try:
        validate_item(item)
    except HTTPException:
        raise                         # 已知异常，直接抛
    except Exception as e:
        raise HTTPException(400, ...) # 未知异常，包装

# ③ 投递 Kafka
try:
    await send_batch(data)
    return {"status": "accepted", "count": len(data), "message": "..."}
except Exception as e:
    logger.error(...)
    raise HTTPException(500, ...)     # Kafka 投递失败 → 500
```

### enumerate 是什么

`enumerate(data)` = 遍历时同时拿索引和元素：
```python
for i, item in enumerate(data):
    # i=0, item=data[0]
    # i=1, item=data[1]
```
Java 类比：`for (int i=0; i<data.size(); i++)`。

### 两个 except 的逻辑

```python
except HTTPException:
    raise                    # 校验失败异常，原封不动抛出（保留原始错误）
except Exception as e:
    raise HTTPException(...) # 意料之外的异常，包装成 HTTPException
```

Java 类比：
```java
catch (HTTPException e) { throw e; }
catch (Exception e) { throw new HTTPException(400, ...); }
```

### raise 是什么

`raise` = 抛出异常 = Java 的 `throw`。抛出后函数立即中断，FastAPI 自动把 HTTPException 转成 HTTP 错误响应返回给客户端。

| Python | Java |
|--------|------|
| `raise HTTPException(400, "...")` | `throw new ResponseStatusException(400, "...")` |
| `except Exception as e:` | `catch (Exception e)` |
| `try:` | `try {` |

### 整批拒绝设计

有一条不合格，整批拒绝。因为一批数据通常属于同一次 Agent 执行（同一个 trace_id），部分写入会导致链路不完整。

---

## 错误处理链路（重点）

### 三阶段失败排查

```
SDK → FastAPI → Kafka → Consumer → ClickHouse
         ①          ②         ③
```

| 阶段 | 失败原因 | 谁发现 | 错误在哪看 |
|------|---------|--------|-----------|
| ① FastAPI → Kafka | Kafka 挂了 | collect.py 的 catch | HTTP 500 返回 SDK + logger.error |
| ② Kafka → Consumer | Consumer 挂了 | consumer.py 的 catch | Consumer 日志 |
| ③ Consumer → ClickHouse | ClickHouse 挂了 | _retry_insert 的 catch | "discarding N records" 日志 |

### SDK 能感知 vs 不能感知

- **① 失败**：SDK 收到 500，知道失败了，可重试 ✅
- **② ③ 失败**：SDK 已拿到 202 走了，不知道 ❌

### 本项目的缺陷

重试 3 次失败后**丢弃数据**（`discarding N records`），没有兜底。生产环境应该：
1. 死信队列（DLQ）：失败消息丢到专门 topic
2. 本地落盘：失败消息写文件
3. 告警：日志出现 "discarding" 触发告警

---

## Span / Trace / span_type 概念详解

### Trace = 一次 Agent 执行的全家福

```
Trace（trace_id="trace-001"）
├── Span 1 (span_id="s1", name="llm_call", 800ms)
├── Span 2 (span_id="s2", name="query_db", 200ms, parent="s1")
│   ├── Span 2.1 (build_sql, 10ms, parent="s2")
│   └── Span 2.2 (exec_query, 180ms, parent="s2")
└── Span 3 (span_id="s3", name="llm_call", 1200ms, parent="s1")
```

- **trace_id**：标识一次完整执行
- **span_id**：标识一个步骤
- **parent_span_id**：标识父步骤，构建执行树

### span_type = 这条数据记录哪个侧面

| span_type | 记什么 | 存哪张表 |
|-----------|--------|---------|
| `trace` | 骨架（谁、什么时候、耗时） | agent_traces |
| `llm_metrics` | LLM 性能（模型、token、TPS） | llm_metrics |
| `prompt` | Prompt/Response 原文 | prompt_logs |
| `tool_call` | 工具详情（入参、出参、报错） | tool_calls |
| `session` | 会话汇总（总耗时、总成本） | sessions |

### 一次 LLM 调用产生几条数据

主要 3 条（trace + llm_metrics + prompt），都属于同一个 span_id。tool_call 只有触发 tool calling 才写；session 是会话级汇总，一次会话结束才写一条。

---

## ClickHouse vs MySQL

### OLTP vs OLAP

| | OLTP | OLAP |
|--|------|------|
| 干啥 | 日常业务增删改查 | 统计分析海量数据 |
| 典型操作 | `SELECT * FROM user WHERE id=1` | `SELECT avg(latency) GROUP BY model` |
| 例子 | MySQL、PostgreSQL | ClickHouse、Snowflake、Hive |

### 行存 vs 列存

- **MySQL（行存）**：数据按行存，查 avg(latency) 要读整行（浪费 IO）
- **ClickHouse（列存）**：数据按列存，查 avg(latency) 只读 latency 列（IO 少 80%+）

### SQL 不是关系型数据库专利

SQL 是查询语言标准，ClickHouse 用 SQL 语法但底层是列存。关系型一定用 SQL，但用 SQL 的不一定是关系型。

### 为什么选 ClickHouse

本项目查询场景：多模型效能对比、排行榜、聚合统计（avg/sum/count/group by），列存快 10-100 倍。数据写后不改，不需要事务。

---

## HTTP vs RPC

| 维度 | 裸 HTTP（本项目） | RPC（Dubbo/gRPC） |
|------|-----------------|-------------------|
| 本质 | 直接用 HTTP 协议 | 让远程调用像本地调用 |
| 写代码感觉 | 能感觉到在发 HTTP | 感觉不到，像调本地方法 |
| 要不要管 URL | 要 | 不要，框架帮处理 |
| 需要注册中心 | ❌ | ✅（Nacos/Zookeeper） |
| 跨语言 | ✅ 通用 | ⚠️ 有限 |
| 适合 | SDK 对外、跨语言 | 内部微服务高频调用 |

SDK 发数据用裸 HTTP（跨语言 + 简单），内部微服务用 RPC（高性能）。

---

## Spring Boot 对照表

| collect.py 做的事 | Spring Boot 对应 |
|------------------|-----------------|
| `router = APIRouter()` | `@RestController` |
| `@router.post("/collect")` | `@PostMapping("/collect")` |
| `data: List[Dict[str, Any]]` | `@RequestBody List<Map<String, Object>>` |
| `raise HTTPException(400, "...")` | `throw new ResponseStatusException(400, "...")` |
| `status.HTTP_202_ACCEPTED` | `HttpStatus.ACCEPTED` |
| `validate_item()` 手动校验 | `@Valid` + Bean Validation |
| `await send_batch(data)` | `kafkaTemplate.send()` |
| `logger = logging.getLogger(__name__)` | `LoggerFactory.getLogger()` |

---

## 一句话总结

1. **collect.py = Controller**，校验 + 投递 Kafka + 返回 202。
2. **202 而非 200**：诚实告诉客户端"还没写库，排队中"。
3. **整批拒绝**：有一条不合格全拒，保证链路完整。
4. **Kafka 不入库**：入库是 Consumer 的活，Kafka 只缓冲。
5. **错误处理三阶段**：① FastAPI→Kafka 失败 SDK 能感知(500)；②③ Consumer→ClickHouse 失败 SDK 不知道，靠日志排查。
6. **raise = throw**，FastAPI 自动把 HTTPException 转 HTTP 错误响应。
