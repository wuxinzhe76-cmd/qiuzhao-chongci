package com.charles.interview.arena.agent.planning.model;

import java.util.Map;

/**
 * 规划决策结果
 * <p>
 * 规划层输出:决定下一步做什么 + 原因 + 参数。
 * 编排层根据此结果执行实际操作(调工具/调LLM/写记忆)。
 *
 * @param action 动作(下一步做什么)
 * @param reason 决策原因(为什么选这个动作,用于日志和调试)
 * @param params 动作参数(如 difficulty/targetTopic 等)
 * @param source 决策来源(LLM/CONSTRAINT/RECOVERY)
 */
public record PlanningDecision(
        PlanningAction action,
        String reason,
        Map<String, Object> params,
        DecisionSource source
) {
    private static final Map<String, Object> EMPTY_PARAMS = Map.of();

    /** LLM 决策合法 */
    public static PlanningDecision fromLlm(PlanningAction action, String reason) {
        return new PlanningDecision(action, reason, EMPTY_PARAMS, DecisionSource.LLM);
    }

    /** 硬约束覆盖 LLM 决策 */
    public static PlanningDecision fromConstraint(PlanningAction action, String reason) {
        return new PlanningDecision(action, reason, EMPTY_PARAMS, DecisionSource.CONSTRAINT);
    }

    /** 恢复策略降级 */
    public static PlanningDecision fromRecovery(PlanningAction action, String reason) {
        return new PlanningDecision(action, reason, EMPTY_PARAMS, DecisionSource.RECOVERY);
    }

    /**
     * 决策来源
     */
    public enum DecisionSource {
        LLM,          // LLM 自主决策(软约束)
        CONSTRAINT,   // 代码硬约束覆盖
        RECOVERY      // 恢复策略降级
    }
}
