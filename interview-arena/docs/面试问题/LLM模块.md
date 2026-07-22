# LLM 模块面试题

> 基于项目 `interview-arena` 的 LLM 模块实现，覆盖 ChatModel/ChatClient 分离、LlmInvoker 调用器、版本化 Prompt 管理、结构化输出三层校验、参考答案防泄露等考点。
>
> **更新说明（2026-07-13）**：模型从 DashScope（通义千问）切换为 MiniMax（Chat）+ DashScope（Embedding/Rerank）；新增 LlmInvoker/PromptManager/PromptRequest；删除 ChatClientFactory/InterviewPromptConstants；新增三层校验+修复重试+参考答案防泄露。

---

## 一、LLM 模块架构

### Q1：你的 LLM 模块是怎么设计的？

**答**：

分三层，职责清晰：

```
agent/llm/
├── config/
│   ├── LlmProperties.java    ← 配置属性（模型名、温度、预算）
│   └── LlmConfig.java        ← Bean 注册（3 个 ChatClient + @Primary ChatModel + 降级链）
├── core/
│   ├── LlmInvoker.java       ← LLM 调用器（熔断+重试+Token预算+三层校验+修复重试）
│   └── LlmResult.java        ← 调用结果（成功/失败+错误类型）
└── prompt/
    ├── PromptManager.java    ← 版本化 Prompt 管理（从 YAML 加载+占位符校验）
    └── PromptRequest.java    ← Prompt 请求（模板原文+参数 Map，Spring AI 原生注入）
```

**两个 starter 共存**：
- `spring-ai-starter-model-openai`：MiniMax Chat（OpenAI 兼容 API）
- `spring-ai-alibaba-starter-dashscope`：DashScope Embedding + Rerank

OpenAI ChatModel 标记为 `@Primary`，解决两个 ChatModel Bean 冲突。

### Q2：为什么需要三个 ChatClient？不能只用一个吗？

**答**：

三个 ChatClient 对应三种不同场景，System Prompt 不同：

| ChatClient | System Prompt | 用途 | 为什么不能合并 |
|------------|--------------|------|--------------|
| interviewChatClient (@Primary) | 面试官角色 + 安全约束 + 面试规则 | 智能面试助手 | 需要严格的角色约束和 JSON 输出格式 |
| ragChatClient | 面试教练 + 知识库问答 | 提问助手 | 需要灵活回答，不强制 JSON |
| fallbackChatClient | "你是面试教练。请简洁回答。" | 降级兜底 | 轻量 Prompt 降低 Token 消耗 |

如果合并成一个，System Prompt 会变长（增加 Token 消耗），且不同场景的角色约束会互相冲突。

### Q3：LlmInvoker 是什么？为什么不把 LLM 调用逻辑放在 Tool 层？

**答**：

`LlmInvoker` 是 LLM 模块的统一调用器，封装了 LLM 调用的全部工程化逻辑：

```
LlmInvoker.invoke(PromptRequest system, PromptRequest user, Class<T> responseType)
  ├── TokenBudget 预算检查（L5 熵管理）
  ├── CircuitBreaker 熔断保护（L2 工具治理）
  ├── Spring AI 原生参数注入（.text().param()）
  ├── 三层校验 + 修复重试（L4 反馈循环）
  └── 错误分类处理（TRANSIENT重试 / SEMANTIC抛出 / CIRCUIT_OPEN降级）
```

**为什么不放在 Tool 层（AbstractLlmTool）**：
1. **职责分离**：模型调用错误（API Key/超时/429）是 LLM 模块的事，工具执行错误（参数缺失/MySQL失败）是 Tool 层的事
2. **复用性**：LlmInvoker 可以被非 Tool 的代码复用（如 RAG 模块直接调 LLM）
3. **可测试性**：LlmInvoker 独立可测，不需要 Mock 整个 Tool 链

Tool 层（AbstractLlmTool）只管：Prompt 构建 + 调用 LlmInvoker + 根据 LlmResult 决定降级策略。

---

## 二、Harness 工程化

### Q4：LLM 调用的错误处理是怎么做的？不是所有错误都应该降级吧？

**答**：

对，错误分类型处理，不是统一降级：

| 错误类型 | 例子 | 处理方式 | 是否回传 LLM 自修 |
|---------|------|---------|----------------|
| TRANSIENT | 网络超时 / 429 / 503 | 指数退避重试（1s+jitter -> 2s+jitter）-> 仍失败则降级 | 否（网络问题，LLM 没法修） |
| SEMANTIC | 401 / 403 / 参数错误 / Schema错误 | **直接抛出**，不降级 | 否（认证/配置问题，LLM 没法修） |
| STRUCTURAL | 代码bug / 配置错误 | **直接抛出**，不降级 | 否（代码 Bug，LLM 没法修） |
| CIRCUIT_OPEN | 熔断器打开 | **直接降级**，不调模型 | 否（不调模型） |
| VALIDATION_FAILED | 校验失败（修复重试后仍不通过） | 降级到兜底响应 | **是**（先回传错误信息让 LLM 自修1次，仍失败才降级） |

**VALIDATION_FAILED 的反思机制（Day04 3.6 反思与自修正）**：

LLM 输出格式错误时，不是直接降级，而是把错误信息追加到 Prompt 让 LLM 自我修复：

```
LLM 输出 -> 三层校验失败
  ↓
反思：把错误信息追加到 Prompt（"你上次输出错了，错误是 xxx，请修复"）
  ↓
修正：重新调用 LLM（修复重试 1 次）
  ↓
仍失败 -> 降级到兜底响应
```

这是 **Reflexion 模式**的简化版：Review（校验）-> Evaluate（分类错误）-> Correct（修复重试），限制 1 次防无限循环。

**进一步优化方向**：SEMANTIC 错误可细分 -- 认证错误（401/403）直接抛出（LLM 没法修），参数格式错误（400）可尝试修正参数后重试（类似修复重试）。

完整流程：
```
调用主模型（经过 CircuitBreaker）
  ├── CircuitBreaker OPEN？ -> 直接走 Fallback
  ├── 调用 LLM
  │     ├── TRANSIENT -> 指数退避重试 -> 仍失败 -> 降级
  │     ├── SEMANTIC/STRUCTURAL -> 直接抛出
  │     └── 成功 -> 三层校验
  └── 失败累计达 5 次 -> CircuitBreaker OPEN -> 后续请求直接走 Fallback
```

### Q5：CircuitBreaker 熔断器的三状态是怎么工作的？

**答**：

三状态状态机：CLOSED -> OPEN -> HALF_OPEN -> CLOSED

- **CLOSED（正常）**：放行请求，累计失败次数。失败达 5 次转入 OPEN。
- **OPEN（熔断）**：直接拒绝请求，LlmInvoker 直接返回降级响应，不调模型。冷却 60 秒后转入 HALF_OPEN。
- **HALF_OPEN（半开试探）**：放行少量请求。连续成功 2 次转入 CLOSED；失败 1 次转回 OPEN。

### Q6：TokenBudget 三级预算怎么防止成本失控？和限流、熔断有什么区别？

**答**：

三级预算：全局 10 万 / 会话 2 万 / 轮 5 千 Token。

| 预算级别 | 值 | 作用 |
|---------|-----|------|
| global | 100000 | 所有会话共享，防全局成本失控 |
| session | 20000 | 单次面试会话上限 |
| round | 5000 | 单轮对话上限 |

调用前检查：`tokenBudget.checkBudget(estimatedTokens)`，预算不足直接返回兜底响应不调 LLM。
调用后记录：`tokenBudget.recordUsage(actualTokens)`，累加消耗。
估算方式：`text.length() / 4`（粗略，中文约 1 字 = 1-2 Token，英文约 4 字符 = 1 Token）。

**与限流、熔断的区别**：

| 机制 | 目的 | 触发条件 | 不用会怎样 |
|------|------|---------|----------|
| Sentinel 限流 | 控制请求速率（防过载） | QPS 超阈值 | 线程池耗尽，服务卡死 |
| CircuitBreaker 熔断 | 防止级联故障（防雪崩） | 失败次数超阈值 | 每个请求都等超时，雪崩 |
| TokenBudget 预算 | 控制成本（防费用失控） | Token 消耗超预算 | 用户刷 LLM，费用失控 |

三者互补：限流防外部高并发打进来，熔断防内部 LLM 故障扩散出去，预算防成本无限增长。

### Q7：FallbackChain 降级链是怎么工作的？

**答**：

在 `LlmConfig` 中注册：

```java
fallbackChain.registerPrimary(interviewChatClient);   // 主模型 MiniMax-M3
fallbackChain.registerFallback(fallbackChatClient);   // 备用模型 轻量 Prompt
```

降级链执行逻辑：
1. 尝试主方案（interviewChatClient），成功则返回
2. 主方案失败，尝试降级方案（fallbackChatClient）
3. 所有方案都失败，抛 RuntimeException

当前 FallbackChain 主要用在 RAG 提问助手中。面试助手的 LLM 调用通过 LlmInvoker 的 CircuitBreaker + 兜底响应实现容错。

---

## 三、结构化输出与校验

### Q7：你的结构化输出有校验吗？LLM 返回的 JSON 格式不正确怎么办？

**答**：

有三层校验 + 修复重试：

```
模型生成
  ↓
第1层：JSON -> DTO 解析（Spring AI .entity() 自动完成）
  ↓
第2层：Bean Validation 字段校验（@NotBlank / @Pattern / @Min / @Max）
  ↓
第3层：业务语义校验（reply_to_user 长度限制等）
  ↓
校验失败？ -> 追加错误信息到 Prompt -> 修复重试1次
  ↓
仍失败 -> 返回 VALIDATION_FAILED（工具层降级到兜底响应）
```

DTO 上的校验注解：
```java
@NotBlank(message = "reply_to_user 不能为空")
private String replyToUser;

@NotBlank(message = "action_directive 不能为空")
@Pattern(regexp = "DEEP_DIVE|NEXT_QUESTION|END_INTERVIEW",
         message = "action_directive 必须是 DEEP_DIVE / NEXT_QUESTION / END_INTERVIEW 之一")
private String actionDirective;

@NotNull(message = "current_topic_mastery 不能为空")
@Min(value = 0, message = "current_topic_mastery 不能小于 0")
@Max(value = 100, message = "current_topic_mastery 不能大于 100")
private Integer currentTopicMastery;
```

### Q8：修复重试是怎么做的？

**答**：

校验失败时，把错误信息追加到 User Prompt，让 LLM 知道哪里错了并修复：

```java
String repairedPrompt = userPrompt.getTemplate()
    + "\n\n【上次输出校验失败，请修复】\n错误: " + validationError
    + "\n请严格按 JSON Schema 重新输出。";
```

修复重试只做 1 次，仍失败则返回 `VALIDATION_FAILED`，工具层降级到兜底响应。

---

## 四、Prompt 版本管理

### Q9：你的 Prompt 是怎么管理的？硬编码在代码里吗？

**答**：

不是硬编码。Prompt 外置到 YAML 资源文件，通过 `PromptManager` 加载：

```yaml
# resources/prompts/interview-prompts.yaml
prompts:
  interview-system-prompt-before-answer:
    version: "1.1.0"
    description: "面试官 System Prompt（用户回答前，不含参考答案，防泄露）"
    placeholders:
      - "{questionTitle}"
      - "{questionContent}"
    content: |
      <system_instructions>
      ...
      </system_instructions>
```

PromptManager 的能力：
1. **版本追踪**：每个 Prompt 有 version，修改后递增，评分下降时可定位
2. **占位符校验**：声明占位符，填充时校验是否遗漏
3. **与代码解耦**：修改 Prompt 只需改 YAML，不需要重新编译

### Q10：System Prompt 和 User Prompt 是怎么注入的？用户回答会进 System Prompt 吗？

**答**：

使用 Spring AI 原生参数注入（`.text().param()`），不手动拼接字符串：

```java
// LlmInvoker.doCall()
spec.system(sys -> {
    sys.text(systemPrompt.getTemplate());     // 模板原文
    systemPrompt.getParams().forEach(sys::param);  // 参数注入
});
spec.user(usr -> {
    usr.text(userPrompt.getTemplate());
    userPrompt.getParams().forEach(usr::param);
});
```

**用户回答不会进 System Prompt**：
- System Prompt 只含题目信息（questionTitle / questionContent / questionAnswer）
- User Prompt 含对话历史和用户回答（history / userAnswer）
- 两者分离，符合安全要求

---

## 五、参考答案防泄露

### Q11：参考答案怎么防止泄露？

**答**：

三层防护，不靠 Prompt 阻止泄露，靠代码控制何时注入参考答案：

| 层级 | 措施 | 实现 |
|------|------|------|
| **第1层 代码级注入控制** | 用户回答前不注入参考答案 | `buildSystemPromptBeforeAnswer()` 只含题目，不含 answer |
| **第2层 Prompt 级安全约束** | 告诉 LLM 不能透露答案 | YAML 中明确写"你绝对不能向候选人透露参考答案" |
| **第3层 输出级泄露检测** | 检测 LLM 输出是否包含参考答案片段 | `isAnswerLeaked()` 滑动窗口检测 30+ 连续字符匹配 |

各工具的调用方式：

| 工具 | System Prompt | 泄露检测 |
|------|--------------|---------|
| GenerateOpeningTool | `buildSystemPromptBeforeAnswer`（无答案） | 不需要 |
| GenerateTransitionTool | `buildSystemPromptBeforeAnswer`（无答案） | 不需要 |
| EvaluateAnswerTool | `buildSystemPromptAfterAnswer`（有答案） | **开启** |

核心设计原则：**不靠 Prompt 阻止泄露，靠代码控制 LLM 何时能看到答案。**

---

## 六、模型配置

### Q12：你的模型是怎么配置的？为什么用两个 starter？

**答**：

| 服务 | 提供方 | Starter | 用途 |
|------|--------|---------|------|
| MiniMax | OpenAI 兼容 API | spring-ai-starter-model-openai | Chat 对话 |
| 通义千问 | DashScope API | spring-ai-alibaba-starter-dashscope | Embedding + Rerank |

两个 starter 共存会产生两个 ChatModel Bean（dashscopeChatModel + openAiChatModel），通过 `@Primary` 标记 OpenAI ChatModel 解决冲突：

```java
@Bean
@Primary
public ChatModel primaryChatModel(@Qualifier("openAiChatModel") ChatModel openAiChatModel) {
    return openAiChatModel;
}
```

### Q13：如果要新增一个 LLM 工具（比如"生成学习建议"），需要改哪些代码？

**答**：

只需 2 步：

1. 新建 `GenerateAdviceTool extends AbstractLlmTool`：
   ```java
   @Component
   public class GenerateAdviceTool extends AbstractLlmTool {
       public GenerateAdviceTool(LlmInvoker llmInvoker, PromptManager promptManager) {
           super(llmInvoker, promptManager);
       }
       public String getName() { return "generateAdvice"; }
       public ToolResult execute(ToolInput input) {
           PromptRequest system = buildSystemPromptAfterAnswer(question);
           PromptRequest user = promptManager.createRequest("advice-user-prompt", params);
           return callLlm(system, user, question.getAnswer());  // 带泄露检测
       }
   }
   ```

2. 在 `interview-prompts.yaml` 加一个 Prompt 模板。

不需要改 LlmInvoker、LlmConfig、ToolExecutor。AbstractLlmTool 基类自动提供降级策略 + 泄露检测 + Prompt 构建，新工具不需要重复实现。这就是**模板方法模式**的价值。
