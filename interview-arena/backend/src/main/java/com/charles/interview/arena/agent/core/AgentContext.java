package com.charles.interview.arena.agent.core;

import java.util.List;
import java.util.Map;

/**
 * Agent 上下文(单次 LLM 调用的临时视图)
 * 由 ContextAssembler 从 Memory+State 组装,不持久化。
 */
public record AgentContext(
        String systemPrompt,
        List<Map<String, String>> messages,
        int tokenUsage
) {}
