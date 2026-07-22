package com.charles.interview.arena.agent.core;

/**
 * Agent 执行结果
 */
public record AgentResult(
        boolean success,
        String reply,
        String action,
        int mastery,
        String errorMessage
) {
    public static AgentResult success(String reply, String action, int mastery) {
        return new AgentResult(true, reply, action, mastery, null);
    }

    public static AgentResult failure(String errorMessage) {
        return new AgentResult(false, null, null, 0, errorMessage);
    }
}
