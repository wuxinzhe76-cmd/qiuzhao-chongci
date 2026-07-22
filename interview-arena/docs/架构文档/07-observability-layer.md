# 可观测性层 - 架构设计文档

## 一、这一层解决什么问题

Agent 运行时是"黑盒":一次用户请求会经过感知、记忆、规划、工具、编排、反思多个层,每层又可能多次调用 LLM 和工具。出了问题(回答质量差/延迟高/成本异常/工具失败),无法定位是哪一层、哪一步、哪次调用导致的。

传统日志(Logging)只能看单条日志,无法串起完整调用链;传统指标(Metrics)只能看聚合数据,无法还原单次请求。Agent 需要的是**链路追踪(Tracing)**:一次请求的所有 Span(LLM 调用/工具调用/记忆操作/编排步骤)串成树状调用链,每个 Span 记录耗时、状态、属性(模型名/Token 数/工具名等)。

可观测性层作为真正的横切关注点,提供统一的链路追踪 API,采集各层 Span 数据,异步批量上报到后端(ClickHouse),供前端 Dashboard 展示和分析。不阻塞业务、不侵入业务代码、线程隔离。

## 二、实现了哪些内容

| 文件路径 | 职责 |
|---------|------|
| `observability/ObservabilityService.java` | 可观测性统一入口(显式 API + 便捷方法) |
| `observability/TraceContext.java` | 追踪上下文(ThreadLocal,线程隔离) |
| `observability/SpanData.java` | Span 数据模型(record,含 7 种 Kind) |
| `observability/AsyncBatchUploader.java` | 异步批量上报器(缓冲+定时+批量 POST) |

### SpanData 7 种 Kind

| Kind | 含义 | 采集层 |
|------|------|--------|
| `LLM` | LLM 调用 | llm/core/ |
| `TOOL` | 工具调用 | tool/api/ |
| `MEMORY` | 记忆操作 | memory/ |
| `ORCHESTRATE` | 编排操作 | orchestration/ |
| `PERCEPTION` | 感知操作 | perception/ |
| `PLANNING` | 规划操作 | planning/ |
| `REFLECTION` | 反思操作 | reflection/ |

### SpanData 数据结构

```java
public record SpanData(
    String traceId,           // 追踪 ID(一次请求一个)
    String spanId,            // Span ID(一次操作一个)
    String parentSpanId,      // 父 Span ID(构建调用链树)
    String name,              // Span 名称(如 "llm.call")
    String kind,              // Span 类型(LLM/TOOL/MEMORY/...)
    long startTimeMs,         // 开始时间
    long endTimeMs,           // 结束时间
    long durationMs,          // 耗时
    String status,            // 状态(SUCCESS/FAILURE)
    Map<String, Object> attributes  // 属性(model/tokenCount/toolName 等)
) {}
```

## 三、架构设计

### 数据流

```
各层业务代码
   ↓ 调用 ObservabilityService.recordSpan
SpanData 入缓冲队列(ConcurrentLinkedQueue)
   ↓ 定时刷新(默认 5 秒)或达批量阈值(默认 50 条)
AsyncBatchUploader 批量 POST
   ↓ HTTP POST /api/collect
agent-observability 后端(Python)
   ↓
Kafka -> ClickHouse
   ↓
前端 Dashboard(调用链可视化/指标分析/性能监控)
```

### 两种使用方式

```java
// 方式 1:显式 API(精细控制)
TraceContext ctx = observabilityService.startSpan("llm.call", SpanData.Kind.LLM);
try {
    // 业务逻辑
    observabilityService.addAttribute("model", "MiniMax-M3");
    observabilityService.addAttribute("tokenCount", 1500);
} finally {
    observabilityService.endSpan(SpanData.Kind.LLM, "SUCCESS");
}

// 方式 2:便捷方法(自动计时)
observabilityService.recordSpan("tool.execute", SpanData.Kind.TOOL, () -> {
    return toolExecutor.execute("pickQuestion", input);
});
```

### ThreadLocal 追踪上下文

```
请求进入 -> startTrace 生成 traceId,存入 ThreadLocal
   ↓
每层操作 -> startSpan 生成 spanId,parentSpanId 指向上一个
   ↓ (同一线程内,TraceContext.getCurrent() 获取当前上下文)
操作结束 -> endSpan 计算 durationMs,构建 SpanData,入缓冲队列
   ↓
请求结束 -> ThreadLocal 清理(防内存泄漏)
```

### 异步批量上报机制

```
SpanData -> ConcurrentLinkedQueue(线程安全缓冲)
   ↓
触发条件:
   ├── 缓冲达 batchSize(默认 50)-> 立即 flush
   └── 定时器每 flushIntervalSeconds(默认 5s)-> 定时 flush
   ↓
flush:批量取出 -> JSON 序列化 -> WebClient POST /api/collect
   ↓
失败:放回队列(下次重试,不丢数据)
成功:日志记录上报条数
   ↓
优雅关闭:@PreDestroy 触发最终 flush,不丢剩余数据
```

## 四、关键设计决策

### 1. ThreadLocal 追踪上下文(线程隔离)

**Why**:一次请求在单线程内经过多层,需要把 traceId/spanId 贯穿所有层。ThreadLocal 实现线程隔离--每个线程有自己的 TraceContext,互不干扰。同一线程内,startSpan 自动继承父 SpanId,构建调用链树。相比显式传参(每个方法都加 traceId 参数),ThreadLocal 零侵入,业务代码不需要感知追踪上下文。请求结束时清理 ThreadLocal 防内存泄漏。

### 2. 异步批量上报(不阻塞业务)

**Why**:如果每次 Span 产生都同步 HTTP POST 到后端,会严重拖慢业务(每次网络往返 +50ms)。异步批量上报三步优化:
- **异步**:Span 入队列立即返回,业务不等待上报;
- **批量**:累积 50 条或 5 秒后批量发送,减少网络请求次数;
- **失败放回**:上报失败放回队列下次重试,不丢数据。

这样业务代码零延迟(入队列 <1ms),上报在后台线程异步进行,不阻塞主流程。

### 3. 对接 agent-observability(Python 后端 + ClickHouse)

**Why**:Java 后端只负责采集和上报,存储和分析交给专用后端。agent-observability 是 Python 实现的专用可观测性后端:
- **采集**:HTTP 接收 Span 数据(/api/collect);
- **存储**:Kafka 缓冲 -> ClickHouse 存储(列式存储,适合时序数据分析);
- **展示**:前端 Dashboard 可视化调用链/指标/性能。

技术栈分离:Java 做业务(强类型/高性能),Python 做数据分析(生态丰富/ClickHouse 集成好),各取所长。ClickHouse 列式存储对聚合查询(按层/按工具/按时间统计)性能极佳,比 MySQL 快百倍。

### 4. 7 种 Span Kind 覆盖所有层

**Why**:Agent 有 6 个机制层 + 编排层,每层操作特性不同(LLM 调用看 Token/工具调用看工具名/记忆操作看检索策略)。7 种 Kind 让每个 Span 能按层分类,前端 Dashboard 可按 Kind 筛选分析--比如只看 LLM 调用链分析成本,只看 TOOL 调用链分析工具成功率,只看 MEMORY 分析检索效率。如果只有一种 Kind,所有 Span 混在一起,无法分层分析,可观测性价值大减。

## 五、面试讲点

- 可观测性是真正的横切关注点:贯穿感知/记忆/规划/工具/编排/反思所有层,不是某一层的附属;
- 链路追踪:一次请求的所有 Span(LLM/工具/记忆/编排/感知/规划/反思)串成树状调用链,每个 Span 记录耗时/状态/属性;
- ThreadLocal 追踪上下文:线程隔离,traceId/spanId 自动贯穿所有层,零侵入业务代码;
- 异步批量上报:Span 入队列立即返回(零延迟),批量 50 条或 5 秒定时发送,不阻塞业务;
- 对接 agent-observability:Java 采集上报,Python 后端接收,Kafka->ClickHouse 存储,前端 Dashboard 展示;
- 7 种 Span Kind:覆盖所有层,可按层筛选分析(LLM 成本/工具成功率/记忆检索效率);
- SpanData 字段:traceId 串联请求/spanId 标识操作/parentSpanId 构建调用树/attributes 记录业务属性;
- 优雅关闭:@PreDestroy 触发最终 flush,不丢剩余数据;失败放回队列重试,保证数据不丢。
