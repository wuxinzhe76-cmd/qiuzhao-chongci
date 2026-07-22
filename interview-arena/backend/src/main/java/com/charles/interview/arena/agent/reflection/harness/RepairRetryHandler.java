package com.charles.interview.arena.agent.reflection.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.reflection.model.ReflectionResult;

/**
 * 修复重试处理器
 * <p>
 * 职责:校验失败时,带修复提示重新调用 LLM。
 * <p>
 * 流程:
 * 1. OutputValidator 校验失败 -> 生成修复提示
 * 2. 带修复提示重新调 LLM(最多 1 次)
 * 3. 再次校验 -> 通过则返回 / 失败则走降级
 * <p>
 * 这是 Reflexion 中的 Self-Reflection:
 * 告诉模型"你哪里错了,怎么改"。
 */
@Component
public class RepairRetryHandler {

    private static final Logger log = LoggerFactory.getLogger(RepairRetryHandler.class);

    private final OutputValidator outputValidator;
    private final ReflectionLimitPolicy limitPolicy;

    public RepairRetryHandler(OutputValidator outputValidator, ReflectionLimitPolicy limitPolicy) {
        this.outputValidator = outputValidator;
        this.limitPolicy = limitPolicy;
    }

    /**
     * 带修复重试的校验
     * <p>
     * 如果第一次校验失败,带修复提示重试一次。
     * 超过重试次数则返回失败结果(调用方走降级)。
     *
     * @param response      LLM 首次返回
     * @param retrySupplier 重试时调 LLM 的方式(传入修复提示,返回新结果)
     * @param <T>           返回类型
     * @return 校验结果(通过或最终失败)
     */
    public <T> ReflectionResult validateWithRetry(
            T response,
            java.util.function.Function<String, T> retrySupplier) {

        // 第一次校验
        ReflectionResult result = outputValidator.validate(response);
        if (result.valid()) {
            return result; // 校验通过,无需重试
        }

        log.warn("首次校验失败,尝试修复重试: {}", result.errorMessage());

        // 检查是否还能重试
        if (!limitPolicy.canRetry(0)) {
            log.warn("修复重试次数已用尽,走降级");
            return result;
        }

        // 带修复提示重新调 LLM
        if (result.repairPrompt() != null) {
            T retryResponse = retrySupplier.apply(result.repairPrompt());
            if (retryResponse != null) {
                ReflectionResult retryResult = outputValidator.validate(retryResponse);
                if (retryResult.valid()) {
                    log.info("修复重试成功");
                    return retryResult;
                }
                log.warn("修复重试仍失败: {}", retryResult.errorMessage());
                return retryResult;
            }
        }

        return result; // 返回原始失败结果
    }
}
