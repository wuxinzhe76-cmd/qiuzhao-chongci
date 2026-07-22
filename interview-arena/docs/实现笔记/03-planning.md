# 机制3:规划与推理层实现笔记

## 一、这一层主要实现了什么

推理(ReAct)负责单步怎么想(评估回答/追问/换题/结束),规划(代码编排)负责多步怎么组织(确定性步骤代码控制+非确定性决策委托LLM)。面试场景流程确定(start->pick->evaluate->route),不需要复杂DAG/任务分解,但需要目标漂移防护和错误分类。包含:ReActExecutor(MAX_STEPS=5) + GoalTracker(正则检测用户提问) + ErrorClassifier(三分类) + LoopDetector(循环检测)。

## 二、伪代码

```java
// ReActExecutor 循环
public ReActResult run(ReActRequest request) {
    for (int step = 1; step <= MAX_STEPS; step++) {
        // Token预算检查
        if (!tokenBudget.checkBudget(estimatedTokens)) {
            return ReActResult.failure("Token预算不足");
        }
        // 熔断器保护LLM调用
        LlmResult<ReActStep> llmResult = circuitBreaker.call(
            () -> llmInvoker.invoke(systemPrompt, userPrompt, ReActStep.class));

        ReActStep step = llmResult.getData();
        // 1. 有final_answer -> 结束
        if (step.getFinalAnswer() != null) {
            return ReActResult.success(step.getFinalAnswer(), traces);
        }
        // 2. 白名单校验(模型幻觉出不存在工具)
        if (!request.getAllowedTools().contains(step.getAction())) {
            scratchpad.append("Observation: 工具不存在");
            continue;
        }
        // 3. 重复调用检测
        if (isDuplicateCall(step)) {
            scratchpad.append("Observation: 重复调用,请换路");
            continue;
        }
        // 4. 执行工具(sessionId/userId由代码注入,模型无法伪造)
        ToolResult observation = toolExecutor.execute(step.getAction(), input);
        // 5. Observation回罐
        scratchpad.append("Thought: " + step.getThought());
        scratchpad.append("Action: " + step.getAction());
        scratchpad.append("Observation: " + observation);
    }
    return ReActResult.failure("超过最大步数");
}

// GoalTracker 目标漂移检测(正则)
public DriftResult checkDrift(Long sessionId, String userInput) {
    boolean isQuestion = matchesPattern(userInput,
        "什么是|为什么|怎么|请讲讲|帮我解释|详细说说");
    boolean isShort = userInput.length() >= 5 && userInput.length() <= 30;
    boolean hasQuestionMark = userInput.endsWith("?");
    if (isQuestion || (isShort && hasQuestionMark)) {
        int violationCount = incrementViolation(sessionId);
        if (violationCount == 1) return DriftResult.warn("请不要主动提问");
        else return DriftResult.forceNextQuestion();
    }
    return DriftResult.pass();
}

// ErrorClassifier 错误三分类
public ErrorType classify(Exception e) {
    if (isNetworkError(e) || isRateLimit(e)) return TRANSIENT; // 重试
    if (isParamError(e) || isValidationError(e)) return SEMANTIC; // 修正参数
    if (isToolNotFound(e) || isMethodFailure(e)) return STRUCTURAL; // 重规划
}
```

## 三、Harness 实现了哪些内容

4项增强:
1. **目标追踪与漂移检测(L5熵管理)**:GoalTracker用正则检测用户是在提问还是回答。第1次警告,第2次强制换题。漂移对话不写入对话历史(防LLM被带偏)但落库MySQL(保证记录完整)。第一版保持正则检测(零成本<1ms),后续升级向量相似度。
2. **错误分类与重规划决策(L4反馈循环)**:三分类(TRANSIENT瞬态->重试/SEMANTIC语义->修正参数/STRUCTURAL结构->重规划)。优先级:重试>修正参数>重规划>降级>人工接管。
3. **循环检测(L5熵管理)**:LoopDetector检测连续相同操作(maxSameAction=3)/最大轮次(maxRounds=10)/Ping-Pong检测。防止ReAct死循环烧Token。
4. **生产约束**:max_iterations=5(MAX_STEPS)/token_budget=100k/20k/5k/replan_limit=1(MAX_REPAIR_RETRIES)。模型不能自己决定终止,代码强制到maxRounds就结束。

## 四、面试题(含RAG)

**Q1: 为什么不用Spring AI Alibaba的ReactAgent?**
A: 之前用过ReactAgent后改用代码编排。原因:1)面试场景是Workflow不是Agent,工具调用时序确定(抽题->评估->保存->路由),不需要LLM自主决策调哪个工具。2)可控性:ReactAgent的ReAct循环是LLM自主控制,可能死循环/调错工具;代码编排每步确定。3)Token成本:ReactAgent每轮让LLM决策调哪个工具,多一次LLM调用;代码编排直接调工具,省一次。

**Q2: ReAct和你的编排模式有什么区别?**
A: ReAct是推理模式(单步怎么想),Orchestrator是编排层(多步怎么组织)。Orchestrator用代码控制确定性步骤(记忆写入/轮次推进/护栏检查),把非确定性决策委托给ReAct循环(评估回答/追问换题)。10步中只有第6步是模型决策,其余9步是代码控制。这是把流程控制权从模型手里拿回到代码手里。

**Q3: 目标漂移检测为什么不用LLM判断?**
A: 三个原因:1)省Token:每次检测调LLM多一次API调用,正则零成本。2)速度:正则<1ms,LLM 2-5秒,漂移检测在热路径不能慢。3)可靠性:LLM自己可能已漂移(这就是问题),让已漂移的LLM判断是否漂移不可靠。代码层正则是确定性的。

**Q4: 在RAG+Agent场景中,Agentic RAG的迭代检索怎么实现?**
A: AgenticRagService实现迭代检索:用原query检索->rerank Top-K->LLM判断isContextSufficient->不够用则LLM改写query->回到检索。最多3轮。与普通RAG区别:普通RAG检索一次就生成,Agentic RAG检索->判断->改写->再检索->再判断直到够用。借鉴Google Agentic RAG,准确率提升34%。
