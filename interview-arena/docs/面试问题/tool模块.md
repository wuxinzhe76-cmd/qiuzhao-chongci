# Tool 模块面试题

> 基于项目 `interview-arena` 的 Tool 模块实现，覆盖 LangChain 工具模式、Harness 工程化、高并发防护等考点。

---

## 一、工具架构设计

### Q1：你的工具模块是怎么设计的？为什么不用 Spring AI 的 @Tool 注解？

**答**：

我的工具模块参考了 LangChain 的 BaseTool 模式，分三层：

```
ToolExecutor（执行器：限流 + 权限 + 审计 + 异常兜底）
  └── ToolRegistry（注册中心：Spring 自动注入所有 Tool Bean）
        └── Tool 接口（getName/getDescription/getPermissionLevel/execute）
              ├── PickQuestionTool（抽题，READ）
              ├── EvaluateAnswerTool（评估回答，READ，调 LLM）
              ├── GenerateOpeningTool（生成开场，READ，调 LLM）
              ├── GenerateTransitionTool（过渡话术，READ，调 LLM）
              ├── SaveRecordTool（保存记录，WRITE）
              ├── GetQuestionDetailTool（获取题目，READ，Redis 缓存）
              └── GenerateReportTool（生成报告，WRITE，发 MQ）
```

不用 @Tool 注解的原因：@Tool 是给 LLM 做 Function Calling 用的，由 LLM 自主决定调用哪个工具。但我的面试助手是**代码编排模式**（Workflow），工具调用时序是确定的（抽题 -> 评估 -> 保存 -> 路由），不需要 LLM 自主决策。自己实现 Tool 接口能更灵活地控制权限、限流和审计。

### Q2：ToolExecutor 和 ToolRegistry 的职责为什么要分开？

**答**：

单一职责原则：
- **ToolRegistry** 只负责工具的注册和查找（`get(name)` / `list()` / `contains(name)`），是纯数据结构。
- **ToolExecutor** 负责工具的**执行治理**：限流（Sentinel）、权限检查（ToolPermission）、审计日志、异常兜底。

分开的好处是 ToolRegistry 可以被其他组件复用（如 MCP Server 暴露工具列表时不经过 Executor 的限流），而 ToolExecutor 可以在不影响 Registry 的情况下调整治理策略。

### Q3：你的工具接口为什么有 getPermissionLevel()？三种权限级别怎么用？

**答**：

三级权限模型参考了 Harness L3 Security Guard：

| 级别 | 含义 | ToolExecutor 行为 | 示例工具 |
|------|------|-------------------|---------|
| READ | 只读 | 直接放行 | PickQuestion、GetQuestionDetail |
| WRITE | 写操作 | 记录审计日志后放行 | SaveRecord、GenerateReport |
| CRITICAL | 危险操作 | 拦截，需人工审批（HITL） | deleteUser（未实现，预留） |

ToolExecutor 在 `execute()` 里先调 `checkPermission()`，CRITICAL 级别直接返回 `ToolResult.failure("CRITICAL 操作需人工审批")`。这防止 Agent 越权调用危险工具。

---

## 二、Harness 工程化

### Q4：工具调用层做了哪些 Harness 防护？

**答**：

四道防线，按调用顺序：

1. **Sentinel 限流**：每个工具独立 QPS 限制（如 evaluateAnswer=10 QPS，pickQuestion=20 QPS），防止高并发打 MySQL。在 `ToolExecutor.execute()` 入口用 `SphU.entry(toolName)` 包裹。

2. **权限检查**：READ/WRITE/CRITICAL 三级，CRITICAL 需人工审批。

3. **审计日志**：记录工具名、sessionId、执行耗时、成功/失败，便于排查问题。

4. **异常兜底**：`try-catch` 包裹 `tool.execute()`，异常时返回 `ToolResult.failure()` 而非向上抛出，保证 Orchestrator 不会因工具异常而崩溃。

### Q5：LLM 工具（EvaluateAnswerTool 等）和普通工具（PickQuestionTool）的 Harness 防护有什么区别？

**答**：

普通工具只有 ToolExecutor 层的四道防线。LLM 工具额外多三层，通过 `AbstractLlmTool` 基类实现：

| 防护层 | 普通工具 | LLM 工具（AbstractLlmTool） |
|--------|---------|---------------------------|
| 限流 | ✅ Sentinel | ✅ Sentinel |
| 权限 | ✅ ToolPermission | ✅ ToolPermission |
| 审计 | ✅ 日志 | ✅ 日志 |
| 异常兜底 | ✅ ToolResult.failure | ✅ ToolResult.failure |
| **Token 预算** | ❌ 不需要 | ✅ 调用前检查 + 调用后记录 |
| **熔断器** | ❌ 不需要 | ✅ CircuitBreaker 包裹 LLM 调用 |
| **结构化错误** | ❌ 不需要 | ✅ StructuredErrorHandler 分类 + 修复指令 |

LLM 工具需要额外防护的原因：LLM 调用有成本（按 Token 计费）、可能超时、可能限流 429、输出可能异常。

### Q6：CircuitBreaker 熔断器的三状态是怎么工作的？

**答**：

三状态状态机：CLOSED -> OPEN -> HALF_OPEN -> CLOSED

- **CLOSED（正常）**：放行请求，累计失败次数。失败达 5 次转入 OPEN。
- **OPEN（熔断）**：直接拒绝请求，抛 `CircuitBreakerOpenException`。冷却 60 秒后转入 HALF_OPEN。
- **HALF_OPEN（半开试探）**：放行少量请求。连续成功 2 次转入 CLOSED；失败 1 次转回 OPEN。

在 `AbstractLlmTool.callLlm()` 里用 `circuitBreaker.executeProtected(supplier)` 包裹 ChatClient 调用。通义千问服务故障时，熔断器快速打开，不发请求直接走兜底响应，防止级联故障。

### Q7：StructuredErrorHandler 把错误分哪三类？为什么要分类？

**答**：

| 错误类型 | 匹配模式 | 可重试 | 修复策略 |
|---------|---------|--------|---------|
| TRANSIENT | timeout/429/503 | 可重试 | 等待 3-5 秒后重试 |
| SEMANTIC | 400/401/403/NullPointer | 需修正后重试 | 检查输入参数格式 |
| STRUCTURAL | 其他 | 不可重试 | 检查代码逻辑和配置 |

分类的原因：让 Agent 能理解错误原因并决策下一步，而不是简单地崩溃或盲目重试。比如 TRANSIENT 可以自动重试，SEMANTIC 需要修正参数后重试，STRUCTURAL 必须人工介入。

### Q8：TokenBudget 三级预算怎么防止成本失控？

**答**：

三级预算：全局 10 万 / 会话 2 万 / 轮 5 千 Token。

调用前检查：`tokenBudget.checkBudget(estimatedTokens)`，预算不足直接返回兜底响应不调 LLM。
调用后记录：`tokenBudget.recordUsage(actualTokens)`，累加消耗。

估算方式：`text.length() / 4`（粗略，中文约 1 字 = 1-2 Token，英文约 4 字符 = 1 Token）。

---

## 三、高并发防护

### Q9：GetQuestionDetailTool 为什么加 @Cacheable？缓存策略是什么？

**答**：

题目详情是**读多写少**的数据（面试中频繁查询同一题目，但题目内容很少更新），适合缓存。

策略：
- `@Cacheable(value = "question:detail", key = "#input.params['questionId']")`
- Redis 缓存，TTL 30 分钟
- `unless = "#result == null || !#result.isSuccess()"`：失败结果不缓存
- 提供 `@CacheEvict` 方法，题目更新时手动清缓存

效果：第一次查 MySQL（约 10ms），后续命中 Redis 缓存（约 1ms），减少 MySQL 压力。

### Q10：Sentinel 限流和 CircuitBreaker 熔断有什么区别？

**答**：

| 维度 | Sentinel 限流 | CircuitBreaker 熔断 |
|------|-------------|-------------------|
| 目的 | 控制流量（防过载） | 防止级联故障（防雪崩） |
| 触发条件 | QPS 超阈值 | 失败次数超阈值 |
| 拒绝方式 | 快速拒绝（返回"系统繁忙"） | 熔断打开（返回兜底响应） |
| 恢复方式 | QPS 下降后自动恢复 | 冷却 60s -> 半开试探 |
| 位置 | ToolExecutor 层（所有工具） | AbstractLlmTool 层（仅 LLM 工具） |

两者互补：Sentinel 防止外部高并发打进来，CircuitBreaker 防止内部 LLM 故障扩散出去。

### Q11：工具调用失败时为什么不向上抛异常？

**答**：

`ToolExecutor.execute()` 用 `try-catch` 包裹 `tool.execute()`，异常时返回 `ToolResult.failure()` 而非抛出。

原因：
1. **Orchestrator 不应该因工具异常而崩溃**。面试进行到第 8 轮，EvaluateAnswerTool 异常不应导致整个面试接口 500。
2. **调用方可以根据 ToolResult.isSuccess() 做降级决策**。比如 LLM 工具失败返回兜底 DTO（action_directive=END_INTERVIEW），Orchestrator 能优雅结束面试。
3. **审计日志能记录完整的失败信息**，便于事后排查。

---

## 四、MCP 工具集成

### Q12：你的项目里 MCP 用在哪？为什么不全部用硬编码工具？

**答**：

MCP 用在面试报告 PDF 生成：
- **硬编码工具**（PickQuestion、SaveRecord 等）：面试主流程，确定性强、实时性高，用 Java 代码直接实现。
- **MCP 工具**（PDF Toolkit）：报告生成，耗时长、失败率高、非核心流程，通过 MCP 协议调用外部服务。

不全部用硬编码的原因：PDF 生成需要专门的库（pdf-lib），用 MCP 调用 PDF Toolkit MCP Server 比在 Java 里引入 PDF 库更轻量。且 MCP Server 可以被其他 AI Agent 复用（如 Claude Desktop 直接生成报告）。

### Q13：MCP 工具调用和硬编码工具调用的防护策略有什么区别？

**答**：

| 防护 | 硬编码工具 | MCP 工具 |
|------|-----------|---------|
| 限流 | ✅ Sentinel | ✅ Sentinel |
| 权限 | ✅ ToolPermission | ✅ ToolPermission |
| 异常兜底 | ✅ ToolResult.failure | ✅ ToolResult.failure |
| 熔断 | ❌ 不需要（MySQL 挂了应用也挂） | ✅ 需要（MCP Server 可能独立挂） |
| 超时 | ❌ 不需要（毫秒级） | ✅ 需要（CompletableFuture + 60s 超时） |
| 重试 | ❌ 不需要 | ✅ 可选（瞬断重试可能成功） |
| 降级 | ❌ 返回错误即可 | ✅ Markdown 降级（PDF 失败存 .md） |

MCP 工具防护更重的原因：跨进程调用，网络不可靠，服务可能不可用。

### Q14：PdfReportService 的超时控制是怎么实现的？

**答**：

用 `CompletableFuture.supplyAsync().or(60, TimeUnit.SECONDS).join()` 包裹整个生成流程：

```java
String result = CompletableFuture
    .supplyAsync(() -> doGenerateReportPdf(sessionId))  // 异步执行
    .or(60, TimeUnit.SECONDS)                           // 60秒超时
    .join();                                             // 等待结果
```

超时后抛 `CancellationException`，catch 后调 `saveMarkdownFallback()` 降级：不调 LLM（可能就是 LLM 导致的超时），只从 MySQL 拉对话记录拼纯文本 Markdown。

这样保证 MQ 消费线程不会因 PDF 生成 hang 死，最多阻塞 60 秒。

### Q15：为什么 PDF 生成走 MQ 异步而不是同步？

**答**：

PDF 生成涉及 LLM 调用（10-30s）+ PDF 渲染（10-30s），总耗时 20-60 秒。如果同步，用户点"结束面试"后要等 60 秒才能返回，体验极差。

用 RabbitMQ 异步：
1. 用户调 `POST /api/interview/end` -> 立即返回"面试已结束"
2. `GenerateReportTool` 发 MQ 消息 -> 非阻塞
3. `InterviewReportConsumer` 异步消费 -> 慢慢生成报告
4. 用户后续查报告（或推送通知）

MQ 的优势 vs CompletableFuture：
- 可靠性：消息持久化 + ACK，进程崩了不丢任务
- 解耦：生产者和消费者完全分离
- 重试：消息消费失败可重新入队

---

## 五、设计模式

### Q16：你的工具模块用了哪些设计模式？

**答**：

1. **策略模式**：Tool 接口 + 多个实现类，ToolExecutor 调用时传入工具名选择策略。
2. **工厂模式**：ChatClientFactory 按用途（INTERVIEW/RAG/FALLBACK）创建 ChatClient。
3. **模板方法模式**：AbstractLlmTool 基类定义 LLM 调用流程（预算检查 -> 熔断包裹 -> 结构化输出 -> 错误处理），子类只实现 Prompt 拼接。
4. **注册表模式**：ToolRegistry 集中管理所有工具，支持按名称查找。
5. **门面模式**：ToolExecutor 封装限流+权限+审计+异常兜底，对外只暴露 `execute(name, input)`。
6. **建造者模式**：ToolInput.builder().sessionId().with().build()。
