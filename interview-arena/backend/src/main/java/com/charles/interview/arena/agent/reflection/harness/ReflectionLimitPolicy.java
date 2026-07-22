package com.charles.interview.arena.agent.reflection.harness;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 反思轮次限制策略
 * <p>
 * 防止反思本身变成问题:
 * - 反思死循环:反思->发现问题->修正->再反思->再发现问题->无限循环
 * - 反思过度:每步都反思,Token 消耗翻倍
 * <p>
 * 约束:
 * - 修复重试:最多 1 次(MAX_REPAIR_RETRIES)
 * - ReAct 纠正步:包含在 MAX_STEPS=5 内
 * - 反思最多 2-3 轮,不能无限循环
 * - 反思失败后走降级链,不无限重试
 */
@Component
public class ReflectionLimitPolicy {

    /** LLM 输出修复重试次数(最多 1 次) */
    @Value("${reflection.max-repair-retries:1}")
    private int maxRepairRetries;

    /** ReAct 纠正步上限(包含在 MAX_STEPS 内) */
    @Value("${reflection.max-correction-steps:2}")
    private int maxCorrectionSteps;

    /** 总反思轮次上限(防死循环) */
    @Value("${reflection.max-reflection-rounds:3}")
    private int maxReflectionRounds;

    /**
     * 检查是否还能修复重试
     *
     * @param currentAttempt 当前已重试次数
     * @return true 表示还能重试
     */
    public boolean canRetry(int currentAttempt) {
        return currentAttempt < maxRepairRetries;
    }

    /**
     * 检查是否还能纠正(ReAct 循环内)
     *
     * @param correctionCount 当前已纠正次数
     * @return true 表示还能纠正
     */
    public boolean canCorrect(int correctionCount) {
        return correctionCount < maxCorrectionSteps;
    }

    /**
     * 检查是否超过总反思轮次
     *
     * @param reflectionRound 当前反思轮次
     * @return true 表示超限,应走降级
     */
    public boolean isReflectionExhausted(int reflectionRound) {
        return reflectionRound >= maxReflectionRounds;
    }

    public int getMaxRepairRetries() { return maxRepairRetries; }
    public int getMaxCorrectionSteps() { return maxCorrectionSteps; }
    public int getMaxReflectionRounds() { return maxReflectionRounds; }
}
