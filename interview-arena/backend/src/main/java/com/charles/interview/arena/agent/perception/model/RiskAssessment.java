package com.charles.interview.arena.agent.perception.model;

/**
 * 风险评估结果
 */
public record RiskAssessment(
        String level,    // LOW/MEDIUM/HIGH/CRITICAL/BLOCKED
        String reason
) {}
