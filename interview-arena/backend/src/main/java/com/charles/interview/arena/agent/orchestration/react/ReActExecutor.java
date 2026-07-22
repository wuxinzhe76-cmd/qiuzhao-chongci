package com.charles.interview.arena.agent.orchestration.react;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.harness.common.CircuitBreaker;
import com.charles.interview.arena.agent.harness.common.TokenBudget;
import com.charles.interview.arena.agent.llm.core.LlmInvoker;
import com.charles.interview.arena.agent.llm.core.LlmResult;
import com.charles.interview.arena.agent.llm.prompt.PromptManager;
import com.charles.interview.arena.agent.llm.prompt.PromptRequest;
import com.charles.interview.arena.agent.tool.api.ToolExecutor;
import com.charles.interview.arena.agent.tool.api.ToolInput;
import com.charles.interview.arena.agent.tool.api.ToolRegistry;
import com.charles.interview.arena.agent.tool.api.ToolResult;
import com.charles.interview.arena.agent.perception.parsing.ToolResultParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ReAct 执行器（Reason + Act 循环，两个 Agent 共用）
 * <p>
 * 循环协议：
 * <pre>
 * while (step <= MAX_STEPS):
 *   1. LlmInvoker 调 LLM（复用熔断/预算/结构化三层校验）-> ReActStep
 *   2. 有 final_answer -> 返回
 *   3. 有 action -> 白名单校验 -> ToolExecutor 执行（复用限流/权限/审计）
 *   4. Observation 追加进 scratchpad，进入下一步
 * 最后一步强制要求 final_answer；重复调用相同工具会被提示纠正。
 * </pre>
 * <p>
 * 安全设计：
 * - sessionId/userId 由代码注入 ToolInput，模型无法伪造身份
 * - 工具白名单按 Agent 隔离（面试助手看不到 webSearch，询问助手看不到 pickQuestion）
 * - MAX_STEPS 硬上限 + 重复调用检测，防止模型死循环烧 Token
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReActExecutor {

    private final LlmInvoker llmInvoker;
    private final ToolExecutor toolExecutor;
    private final ToolRegistry toolRegistry;
    private final PromptManager promptManager;
    private final ObjectMapper objectMapper;
    private final CircuitBreaker circuitBreaker;
    private final TokenBudget tokenBudget;
    private final ToolResultParser toolResultParser;

    /** 单次任务最大思考步数（含最后强制收敛步） */
    private static final int MAX_STEPS = 5;

    /** Observation 注入 prompt 的最大长度（防止大对象撑爆上下文） */
    private static final int MAX_OBSERVATION_LENGTH = 2000;

    /**
     * 执行 ReAct 循环
     */
    public ReActResult run(ReActRequest request) {
        String toolList = toolRegistry.renderToolPrompt(request.getAllowedTools());
        PromptRequest systemPrompt = promptManager.createRequest("react-system-prompt", Map.of(
                "persona", request.getPersona(),
                "toolList", toolList,
                "finalAnswerSpec", request.getFinalAnswerSpec()));

        List<ReActTrace> traces = new ArrayList<>();
        StringBuilder scratchpad = new StringBuilder("（尚未执行任何行动）\n");
        String lastActionKey = null;

        for (int step = 1; step <= MAX_STEPS; step++) {
            String stepHint = step == MAX_STEPS
                    ? "注意：这是最后一步，你必须输出 final_answer，不允许再调用工具。"
                    : "请输出下一步。";

            PromptRequest userPrompt = promptManager.createRequest("react-user-prompt", Map.of(
                    "task", request.getTask(),
                    "scratchpad", scratchpad.toString(),
                    "stepHint", stepHint));

            // Token 预算检查（防止烧 Token）
            String sessionKey = request.getSessionId() != null
                    ? request.getSessionId().toString() : "anonymous";
            int estimatedTokens = tokenBudget.estimateTokens(
                    request.getTask() + scratchpad.toString());
            if (!tokenBudget.checkBudget(estimatedTokens)) {
                log.warn("[ReAct] Token 预算不足，终止循环: session={}", sessionKey);
                return ReActResult.failure("Token 预算不足，终止 ReAct 循环", traces);
            }

            // 熔断器保护 LLM 调用（防止下游故障时持续重试）
            LlmResult<ReActStep> llmResult;
            try {
                llmResult = circuitBreaker.call(
                        () -> llmInvoker.invoke(systemPrompt, userPrompt, ReActStep.class));
            } catch (CircuitBreaker.CircuitBreakerOpenException e) {
                log.warn("[ReAct] 熔断器已打开，LLM 服务不可用: {}", e.getMessage());
                return ReActResult.failure("LLM 服务暂时不可用（熔断保护）", traces);
            }

            // 记录 Token 消耗
            tokenBudget.consume(sessionKey, estimatedTokens);
            if (!llmResult.isSuccess()) {
                return ReActResult.failure("LLM 调用失败: " + llmResult.getErrorMessage(), traces);
            }

            ReActStep reActStep = llmResult.getData();
            log.info("[ReAct] step {}/{}: action={}, thought={}",
                    step, MAX_STEPS, reActStep.getAction(), abbreviate(reActStep.getThought(), 200));

            // 1. 产出最终答案 -> 结束循环
            if (reActStep.getFinalAnswer() != null && !reActStep.getFinalAnswer().isEmpty()) {
                return ReActResult.success(reActStep.getFinalAnswer(), traces);
            }

            // 2. 既无 action 也无 final_answer -> 提示纠正（占一步）
            if (reActStep.getAction() == null || reActStep.getAction().isBlank()) {
                scratchpad.append("Observation: 你的输出既没有 action 也没有 final_answer，请二选一。\n");
                continue;
            }

            // 3. 白名单校验（模型幻觉出不存在/无权限的工具时给它纠错机会）
            String action = reActStep.getAction().trim();
            if (!request.getAllowedTools().contains(action) || !toolRegistry.contains(action)) {
                log.warn("[ReAct] 模型请求了白名单外的工具: {}", action);
                scratchpad.append("Observation: 工具 '").append(action)
                        .append("' 不存在或不可用，可用工具见系统提示词。\n");
                continue;
            }

            // 4. 重复调用检测（同 action + 同参数连续出现 -> 提示换路）
            String actionKey = action + ":" + toJson(reActStep.getActionInput());
            if (actionKey.equals(lastActionKey)) {
                log.warn("[ReAct] 检测到重复调用: {}", actionKey);
                scratchpad.append("Observation: 你重复了与上一步完全相同的调用，请基于已有结果给出 final_answer，或换一个行动。\n");
                continue;
            }
            lastActionKey = actionKey;

            // 5. 执行工具（sessionId/userId 由代码注入，模型参数只进 params）
            ToolInput input = ToolInput.builder()
                    .sessionId(request.getSessionId())
                    .userId(request.getUserId())
                    .build();
            if (reActStep.getActionInput() != null) {
                reActStep.getActionInput().forEach(input::with);
            }
            ToolResult observation = toolExecutor.execute(action, input);
            traces.add(new ReActTrace(action, reActStep.getActionInput(), observation));

            // 6. Observation 回灌
            scratchpad.append("Thought: ").append(reActStep.getThought()).append("\n")
                    .append("Action: ").append(action).append("\n")
                    .append("Action Input: ").append(toJson(reActStep.getActionInput())).append("\n")
                    .append("Observation: ").append(serializeObservation(observation)).append("\n");
        }

        return ReActResult.failure("超过最大步数 " + MAX_STEPS + " 仍未产出最终答案", traces);
    }

    private String serializeObservation(ToolResult result) {
        if (result == null) {
            return "（无结果）";
        }
        if (!result.isSuccess()) {
            return "工具执行失败: " + result.getErrorMessage();
        }
        return abbreviate(toJson(result.getData()), MAX_OBSERVATION_LENGTH);
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...(截断)";
    }
}
