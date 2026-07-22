# 机制5:编排与调度层实现笔记

## 一、这一层主要实现了什么

Orchestrator是Agent的中枢神经,协调Model+Tools+Memory三者交互。核心价值:把流程控制权从模型手里拿回到代码手里。确定性步骤代码控制,非确定性决策委托LLM。当前最大问题:两个Agent编排模式不一致(面试有InterviewOrchestrator,询问助手没有)。本设计统一AgentOrchestrator接口,询问助手从QuickAskService升级为AskOrchestrator。

## 二、伪代码

```java
// 统一 Orchestrator 接口
public interface AgentOrchestrator {
    OrchestratorResult orchestrate(OrchestratorRequest request);
}

// 面试 Orchestrator 10步编排
public InterviewAnswerVO answerInterview(InterviewAnswerDTO dto, Long userId) {
    // 1. 输入清洗(代码控制)
    String sanitized = inputGuardrail.sanitize(dto.getAnswer());
    // 2. 目标漂移检测(代码控制)
    DriftResult drift = goalTracker.checkDrift(sessionId, sanitized);
    if (drift.isDrift()) return handleDrift(drift);
    // 3. 记忆写入(代码控制)
    memoryFacade.recordTurn(sessionId, turn);
    // 4. 轮次推进(代码控制)
    agentStateStore.incrementRound(sessionId);
    // 5. 循环检测(代码控制)
    if (loopDetector.detectLoop(sessionId, sanitized)) return handleNextQuestion();
    // 6. ReAct决策(模型决策)
    ReActResult result = reActExecutor.run(request);
    // 7. 答案泄露检测(代码控制)
    if (leakDetector.isLeaked(result, question.getAnswer())) result = safeResponse();
    // 8. 输出监控(代码控制)
    result = outputMonitor.monitor(result);
    // 9. 三层控制(代码控制)
    ActionDirective directive = threeLayerController.applyControl(
        result.getDirective(), questionRound, totalRound);
    // 10. 指令路由(代码路由)
    return routeDirective(directive);
}

// ThreeLayerController 三层控制
public ActionDirectiveEnum applyControl(String aiDirective, long questionRound, long totalRound) {
    ActionDirectiveEnum directive = ActionDirectiveEnum.fromValue(aiDirective);
    // 代码兜底1:单题>3轮强制换题
    if (questionRound > maxQuestionRounds && directive == DEEP_DIVE) {
        directive = NEXT_QUESTION;
    }
    // 代码兜底2:总轮次>=10强制结束
    if (totalRound >= maxRounds) {
        directive = END_INTERVIEW;
    }
    return directive;
}
```

## 三、Harness 实现了哪些内容

1. **控制流管理**:状态机(面试流程:创建->提问->等待回答->评价->追问/换题/结束)+ 循环(ReAct while循环)。第一版不需要DAG并行。
2. **生产约束四重**:轮次熔断(单题3轮/总10轮/ReAct 5步)+ 超时控制(PDF报告60s)+ 权限规则(READ/WRITE/EXECUTE/CRITICAL)+ 成本限流(TokenBudget 100k/20k/5k + Sentinel QPS)。
3. **ThreeLayerController三层控制**:AI主导(正常范围AI决定)+ 代码兜底(超限强制覆盖)+ 用户主动(随时结束)。模型不能自己决定终止,代码强制到maxRounds就结束。
4. **统一AgentOrchestrator接口**:面试和询问助手对齐编排模式,AskOrchestrator从QuickAskService升级。

## 四、面试题(含RAG)

**Q1: Orchestrator和Service的职责怎么分?**
A: Service是薄层(参数校验+权限校验+委托编排),不含业务逻辑。Orchestrator是业务编排层,调用Tool执行操作,调用Memory管理记忆,调用Harness做安全防护,调用ThreeLayerController做路由决策。分开的原因是Service可能被其他入口复用(如MQ Consumer直接调Orchestrator跳过Service的HTTP校验)。

**Q2: 三层控制的核心思想是什么?**
A: AI主导决策,但代码做兜底。AI返回action_directive(DEEP_DIVE/NEXT_QUESTION/END_INTERVIEW),代码兜底1:单题>3轮强制换题;代码兜底2:总轮次>=10强制结束。这是"信任但验证"原则:信任AI的决策能力,但用代码验证边界条件。

**Q3: 面试报告生成为什么走MQ异步?**
A: 报告生成涉及LLM调用(10-30s)+PDF生成(10-30s),总耗时20-60秒。同步的话用户要等60秒,体验差。MQ异步优势:可靠投递(消息持久化,进程崩了不丢任务)+ 解耦(生产者和消费者分离)+ 手动ACK(消费失败可选择重试或丢弃)。

**Q4: 询问助手的降级链路是怎么设计的?**
A: ReAct失败时走降级链路:RetrievalRouter关键词路由+确定性检索+单次生成。具体:1)关键词路由判定RAG_ONLY/MEMORY_ONLY/HYBRIC;2)HybridRetriever混合检索+Rerank精排;3)MultiStrategyMemoryRetriever记忆检索;4)ChatClient单次生成。保证LLM决策能力不可用时询问功能仍然可用。
