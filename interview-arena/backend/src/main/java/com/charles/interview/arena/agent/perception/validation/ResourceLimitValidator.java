package com.charles.interview.arena.agent.perception.validation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 资源限制校验器
 * <p>
 * 职责:预估 Token 消耗,防止超长输入导致成本失控。
 * 感知层的资源治理:Token 预估 / 请求频率 / 并发限制。
 */
@Component
public class ResourceLimitValidator {

    /** 单次请求最大 Token 预估(防 Abandoned Consumption) */
    @Value("${perception.max-token-estimate:8000}")
    private int maxTokenEstimate;

    /** 粗略的字符到 Token 换算比(中文约 1.5 字/token,英文约 4 字/token,取折中) */
    private static final double CHARS_PER_TOKEN = 2.5;

    /**
     * 预估文本的 Token 消耗
     *
     * @param text 输入文本
     * @return 预估 Token 数
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }

    /**
     * 检查 Token 预算是否超限
     *
     * @param text 输入文本
     * @return true 表示超限
     */
    public boolean isTokenBudgetExceeded(String text) {
        return estimateTokens(text) > maxTokenEstimate;
    }

    /**
     * 检查资源限制
     *
     * @param text 输入文本
     * @throws IllegalStateException 资源超限
     */
    public void check(String text) {
        int estimated = estimateTokens(text);
        if (estimated > maxTokenEstimate) {
            throw new IllegalStateException(
                    "Token 预算超限: 预估 " + estimated + " > 上限 " + maxTokenEstimate);
        }
    }

    public int getMaxTokenEstimate() {
        return maxTokenEstimate;
    }
}
