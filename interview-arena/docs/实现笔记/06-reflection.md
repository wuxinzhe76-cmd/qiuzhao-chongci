# 机制6:反思与自修正层实现笔记

## 一、这一层主要实现了什么

Agent不只向外解决问题,还向内自查自检。ReAct是你在做题,Reflection是你做完后回头检查。当前反思机制分散在两处:LlmInvoker的修复重试(结构化输出校验失败->修复指令->重试1次)和ReActExecutor的纠正提示(无action/白名单外工具/重复调用)。本设计将分散的反思能力收敛到reflection/包,统一轮次限制。

## 二、伪代码

```java
// ReflectionService 统一入口
public class ReflectionService {
    // 输出校验(3层)
    public <T> String validate(T response) {
        // 第1层:JSON解析(已由Spring AI .entity()完成)
        // 第2层:Bean Validation
        Set<ConstraintViolation<T>> violations = validator.validate(response);
        if (!violations.isEmpty()) {
            return violations.stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .reduce((a, b) -> a + "; " + b).orElse("校验失败");
        }
        // 第3层:业务语义校验
        if (response instanceof AiInterviewResponseDTO dto) {
            if (dto.getReplyToUser().length() > 2000) return "reply_to_user过长";
        }
        return null; // 校验通过
    }

    // 修复重试(带修复指令回罐LLM)
    public <T> T repairRetry(PromptRequest system, PromptRequest user, Class<T> type) {
        for (int attempt = 1; attempt <= MAX_REPAIR_RETRIES; attempt++) {
            String repairText = user.getTemplate()
                + "\n\n【上次输出校验失败,请修复】\n错误: " + lastError
                + "\n请严格按JSON Schema重新输出。";
            PromptRequest repaired = PromptRequest.of(repairText, user.getParams());
            T retryResponse = llmInvoker.invoke(system, repaired, type);
            if (validate(retryResponse) == null) return retryResponse;
        }
        throw new ValidationFailedException("修复重试超次数");
    }
}

// ReAct纠正提示
public String buildCorrectionPrompt(ReActStep step) {
    if (step.getAction() == null && step.getFinalAnswer() == null) {
        return "Observation: 你的输出既没有action也没有final_answer,请二选一。";
    }
    if (!allowedTools.contains(step.getAction())) {
        return "Observation: 工具'" + step.getAction() + "'不存在,可用工具见系统提示词。";
    }
    if (isDuplicateCall(step)) {
        return "Observation: 你重复了与上一步完全相同的调用,请基于已有结果给出final_answer。";
    }
    return null;
}
```

## 三、Harness 实现了哪些内容

1. **输出校验3层**:JSON解析(Spring AI .entity()) -> Bean Validation(JSR303) -> 业务语义校验(长度/范围/业务规则)。
2. **修复重试**:校验失败->生成修复指令(ErrorClassifier分类错误+给出修复建议)->带修复指令回罐LLM重试1次。这是Reflexion中的Self-Reflection:告诉模型"你哪里错了,怎么改"。
3. **纠正提示**:ReAct循环中模型输出异常时(无action/白名单外工具/重复调用),生成纠正提示回灌scratchpad,占一步,模型下一轮可以纠正。
4. **反思轮次限制**:MAX_REPAIR_RETRIES=1(修复重试)/ MAX_STEPS=5(ReAct含纠正步)/ 反思最多2-3轮不能无限循环/ 反思失败后走降级链不无限重试。

## 四、面试题(含RAG)

**Q1: 反思和普通错误处理有什么区别?**
A: 区别在于谁在反思。普通错误处理:代码(try-catch)发现错误,代码(固定降级逻辑)决定怎么改。Reflection:代码检测异常+LLM评估,LLM生成修复建议。普通错误处理是确定性的,Reflection是模型参与的智能纠错。

**Q2: Reflexion范式是什么?你的项目实现了吗?**
A: Reflexion是2023年提出的反思框架,四个角色:Actor(执行ReAct)->Evaluator(评估结果)->Self-Reflection(生成语言化反思总结"我错在没考虑边界条件")->Memory(存储反思经验,下次避免重复犯错)。关键点:反思结论是语言化的(文字),不是数值reward,叫"语言化强化学习"。我的项目第一版只实现了修复重试和纠正提示,完整Reflexion范式(跨会话反思记忆)是后续迭代。

**Q3: 为什么反思不能无限循环?**
A: 反思本身也可能变成问题:反思->发现问题->修正->再反思->再发现问题->无限循环。反思过度每步都反思Token消耗翻倍。约束:反思最多2-3轮,反思失败后走降级链不无限重试。

**Q4: 在RAG场景中,Agentic RAG的"isContextSufficient"判断算反思吗?**
A: 算。AgenticRagService每轮检索后LLM判断"检索到的资料是否足以回答用户问题",这是反思的信息完整性评估。不够用则改写query再检索,这是反思后的纠正动作。与普通RAG区别:普通RAG检索一次就生成,Agentic RAG有反思环节(判断够不够->不够则改写->再检索),最多3轮。
