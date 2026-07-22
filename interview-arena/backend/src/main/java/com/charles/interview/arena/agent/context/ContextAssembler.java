package com.charles.interview.arena.agent.context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.core.AgentContext;
import com.charles.interview.arena.agent.memory.working.WorkingMemoryService;
import com.charles.interview.arena.agent.runtime.state.AgentStateStore;

/**
 * 上下文组装器(简化版)
 * <p>
 * 职责:从 Memory + State 组装 AgentContext(单次 LLM 调用的临时视图)。
 * <p>
 * 第一版简化版:
 * [固定保留] + [最近N轮原文] + [Token截断]
 * <p>
 * 后续迭代升级为完整 6 步压缩工作流:
 * Step1:固定保留 / Step2:旧历史摘要 / Step3:RAG检索
 * Step4:排序去重 / Step5:最近N轮原文 / Step6:Token裁剪
 */
@Component
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    private final WorkingMemoryService workingMemoryService;
    private final AgentStateStore agentStateStore;

    public ContextAssembler(WorkingMemoryService workingMemoryService,
                            AgentStateStore agentStateStore) {
        this.workingMemoryService = workingMemoryService;
        this.agentStateStore = agentStateStore;
    }

    /**
     * 组装 Agent 上下文(简化版)
     *
     * @param systemPrompt 系统提示词(固定保留)
     * @param sessionId    面试会话 ID
     * @param userInput    当前用户输入
     * @return AgentContext
     */
    public AgentContext assemble(String systemPrompt, Long sessionId, String userInput) {
        List<Map<String, String>> messages = new ArrayList<>();

        // 1. 固定保留:最近 N 轮对话原文(滑动窗口)
        List<String> recentHistory = workingMemoryService.getRecentHistory(sessionId);
        for (String record : recentHistory) {
            // 格式: "role:content"
            int colonIdx = record.indexOf(':');
            if (colonIdx > 0) {
                String role = record.substring(0, colonIdx);
                String content = record.substring(colonIdx + 1);
                messages.add(Map.of("role", role, "content", content));
            } else {
                messages.add(Map.of("role", "system", "content", record));
            }
        }

        // 2. 当前用户输入
        if (userInput != null && !userInput.isBlank()) {
            messages.add(Map.of("role", "user", "content", userInput));
        }

        // 3. Token 预估(粗略)
        int tokenEstimate = estimateTokens(systemPrompt) + messages.stream()
                .mapToInt(m -> estimateTokens(m.get("content")))
                .sum();

        log.info("上下文组装: sessionId={}, messages={}, estimatedTokens={}",
                sessionId, messages.size(), tokenEstimate);

        return new AgentContext(systemPrompt, messages, tokenEstimate);
    }

    /**
     * 粗略 Token 预估(中文约1.5字/token,英文约4字/token,取折中2.5)
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / 2.5);
    }
}
