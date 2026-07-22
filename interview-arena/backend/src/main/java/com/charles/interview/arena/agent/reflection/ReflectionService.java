package com.charles.interview.arena.agent.reflection;

import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.charles.interview.arena.agent.reflection.harness.CorrectionPromptBuilder;
import com.charles.interview.arena.agent.reflection.harness.OutputValidator;
import com.charles.interview.arena.agent.reflection.harness.RepairRetryHandler;
import com.charles.interview.arena.agent.reflection.harness.ReflectionLimitPolicy;
import com.charles.interview.arena.agent.reflection.model.ReflectionResult;

/**
 * 反思服务(反思层统一入口)
 * <p>
 * 职责:将分散的反思能力收敛到统一入口。
 * <p>
 * 两类反思:
 * 1. 输出校验 + 修复重试(LLM 返回后校验,失败则带修复提示重试)
 * 2. ReAct 纠正提示(循环内 LLM 输出异常时,构建纠正提示回灌)
 * <p>
 * 反思轮次限制:最多 2-3 轮,失败走降级。
 * <p>
 * 不负责:
 * - 完整 Reflexion 范式(Actor->Evaluator->Self-Reflection->Memory,后续迭代)
 * - 信息完整性反思(信息不足->补检索,后续迭代)
 */
@Service
public class ReflectionService {

    private static final Logger log = LoggerFactory.getLogger(ReflectionService.class);

    private final OutputValidator outputValidator;
    private final RepairRetryHandler repairRetryHandler;
    private final CorrectionPromptBuilder correctionPromptBuilder;
    private final ReflectionLimitPolicy limitPolicy;

    public ReflectionService(
            OutputValidator outputValidator,
            RepairRetryHandler repairRetryHandler,
            CorrectionPromptBuilder correctionPromptBuilder,
            ReflectionLimitPolicy limitPolicy) {
        this.outputValidator = outputValidator;
        this.repairRetryHandler = repairRetryHandler;
        this.correctionPromptBuilder = correctionPromptBuilder;
        this.limitPolicy = limitPolicy;
    }

    // ==================== 1. 输出校验 ====================

    /**
     * 校验 LLM 输出(不重试)
     *
     * @param response LLM 返回对象
     * @return 校验结果
     */
    public <T> ReflectionResult validate(T response) {
        return outputValidator.validate(response);
    }

    /**
     * 校验 LLM 输出 + 修复重试(最多 1 次)
     *
     * @param response      LLM 首次返回
     * @param retrySupplier 重试函数(传入修复提示,返回新结果)
     * @return 校验结果
     */
    public <T> ReflectionResult validateWithRetry(
            T response, Function<String, T> retrySupplier) {
        return repairRetryHandler.validateWithRetry(response, retrySupplier);
    }

    // ==================== 2. ReAct 纠正提示 ====================

    /**
     * 构建纠正提示:无 action 且无 final_answer
     */
    public String noActionNoAnswer() {
        return correctionPromptBuilder.noActionNoAnswer();
    }

    /**
     * 构建纠正提示:白名单外工具
     */
    public String toolNotAllowed(String invalidAction, java.util.List<String> allowedTools) {
        return correctionPromptBuilder.toolNotAllowed(invalidAction, allowedTools);
    }

    /**
     * 构建纠正提示:重复调用
     */
    public String duplicateCall(String action) {
        return correctionPromptBuilder.duplicateCall(action);
    }

    /**
     * 构建纠正提示:空结果
     */
    public String emptyResult(String action, String suggestion) {
        return correctionPromptBuilder.emptyResult(action, suggestion);
    }

    /**
     * 构建纠正提示:校验失败
     */
    public String validationFailed(String validationError) {
        return correctionPromptBuilder.validationFailed(validationError);
    }

    // ==================== 3. 轮次限制 ====================

    /**
     * 检查是否还能修复重试
     */
    public boolean canRetry(int currentAttempt) {
        return limitPolicy.canRetry(currentAttempt);
    }

    /**
     * 检查是否还能纠正(ReAct 循环内)
     */
    public boolean canCorrect(int correctionCount) {
        return limitPolicy.canCorrect(correctionCount);
    }

    /**
     * 检查是否超过总反思轮次(应走降级)
     */
    public boolean isReflectionExhausted(int reflectionRound) {
        return limitPolicy.isReflectionExhausted(reflectionRound);
    }

    /**
     * 获取轮次限制配置信息
     */
    public String getLimitInfo() {
        return String.format("maxRepairRetries=%d, maxCorrectionSteps=%d, maxReflectionRounds=%d",
                limitPolicy.getMaxRepairRetries(),
                limitPolicy.getMaxCorrectionSteps(),
                limitPolicy.getMaxReflectionRounds());
    }
}
