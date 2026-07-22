package com.charles.interview.arena.agent.planning.harness;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * 结构化错误处理器（Structured Error Handler）
 * <p>
 * 关联 Harness 层：L4 可靠性层
 * 关联八股题号：Day04 #9 反思、Day05 结构化错误反馈与自修复
 * <p>
 * 核心职责：
 * 将 Agent 运行时产生的异常分类为三种类型，并生成包含修复指令的结构化错误信息，
 * 让 Agent 能够理解错误原因并尝试自修复（而非简单地重试或崩溃）。
 * <p>
 * 三种错误类型：
 * <pre>
 * ┌──────────────┬──────────────────────┬────────────────┬──────────┐
 * │ 错误类型      │ 含义                  │ 是否可重试      │ 修复策略  │
 * ├──────────────┼──────────────────────┼────────────────┼──────────┤
 * │ TRANSIENT    │ 瞬时错误（网络超时）   │ 可重试          │ 等待后重试│
 * │ SEMANTIC     │ 语义错误（参数错误）   │ 需修正后重试    │ 修正参数  │
 * │ STRUCTURAL   │ 结构性错误（代码bug）  │ 不可重试        │ 修复代码  │
 * └──────────────┴──────────────────────┴────────────────┴──────────┘
 * </pre>
 *
 * <pre>
 * 使用示例：
 *   try {
 *       agent.run();
 *   } catch (Exception e) {
 *       StructuredErrorHandler handler = new StructuredErrorHandler();
 *       StructuredError error = handler.createError(e);
 *       if (error.isRetryable()) {
 *           // Agent 可以根据 fixInstructions 修正后重试
 *           agent.run();
 *       }
 *   }
 * </pre>
 */
@Slf4j
public class StructuredErrorHandler {

    /**
     * 错误类型枚举
     */
    public enum ErrorType {
        /**
         * 瞬时错误：网络超时、服务暂时不可用、限流等
         * <p>
         * 可重试，通常等待一段时间后重试即可成功。
         */
        TRANSIENT,

        /**
         * 语义错误：参数错误、格式不匹配、权限不足等
         * <p>
         * 需要修正输入或参数后才能重试。
         */
        SEMANTIC,

        /**
         * 结构性错误：代码 bug、配置错误、数据损坏等
         * <p>
         * 不可重试，需要人工介入修复代码或配置。
         */
        STRUCTURAL
    }

    /** 瞬时错误模式：网络超时、连接重置、限流等 */
    private static final Pattern TRANSIENT_PATTERN = Pattern.compile(
            "(?i).*(timeout|timed out|connection reset|rate limit|429|503|temporarily unavailable|retry).*");

    /** 语义错误模式：参数错误、格式不匹配、权限不足等 */
    private static final Pattern SEMANTIC_PATTERN = Pattern.compile(
            "(?i).*(illegal argument|invalid parameter|bad request|400|401|403|unauthorized|forbidden|null pointer|NumberFormatException).*");

    /**
     * 根据异常类型创建结构化错误
     * <p>
     * 自动分类错误类型，生成修复指令和重试建议。
     *
     * @param e 原始异常
     * @return 结构化错误对象
     */
    public StructuredError createError(Exception e) {
        ErrorType errorType = classifyError(e);
        String fixInstructions = generateFixInstructions(errorType, e);
        boolean retryable = errorType == ErrorType.TRANSIENT || errorType == ErrorType.SEMANTIC;

        log.info("结构化错误: type={}, retryable={}, message={}", errorType, retryable, e.getMessage());

        return new StructuredError(errorType, e.getMessage(), fixInstructions, retryable);
    }

    /**
     * 根据异常类型创建结构化错误（带上下文信息）
     * <p>
     * 自动分类错误类型，生成修复指令和重试建议。
     * 上下文信息会附加到错误消息中，便于排查。
     *
     * @param e       原始异常
     * @param context 上下文描述（如 "AI面试调用失败"）
     * @return 结构化错误对象
     */
    public StructuredError createError(Exception e, String context) {
        ErrorType errorType = classifyError(e);
        String fixInstructions = generateFixInstructions(errorType, e);
        boolean retryable = errorType == ErrorType.TRANSIENT || errorType == ErrorType.SEMANTIC;
        String message = context + ": " + e.getMessage();

        log.info("结构化错误: type={}, retryable={}, context={}, message={}",
                errorType, retryable, context, e.getMessage());

        return new StructuredError(errorType, message, fixInstructions, retryable);
    }

    /**
     * 分类错误类型
     * <p>
     * 根据异常消息内容匹配模式，判断是瞬时错误、语义错误还是结构性错误。
     *
     * @param e 异常
     * @return 错误类型
     */
    public ErrorType classifyError(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            message = e.getClass().getSimpleName();
        }

        if (TRANSIENT_PATTERN.matcher(message).matches()) {
            return ErrorType.TRANSIENT;
        }
        if (SEMANTIC_PATTERN.matcher(message).matches()) {
            return ErrorType.SEMANTIC;
        }
        return ErrorType.STRUCTURAL;
    }

    /**
     * 根据错误类型生成修复指令
     *
     * @param errorType 错误类型
     * @param e         原始异常
     * @return 修复指令字符串
     */
    private String generateFixInstructions(ErrorType errorType, Exception e) {
        return switch (errorType) {
            case TRANSIENT -> "瞬时错误，建议等待 3-5 秒后重试。如果连续失败，检查网络连接和下游服务状态。";
            case SEMANTIC -> "语义错误，请检查输入参数格式和类型。原始异常: " + e.getClass().getSimpleName();
            case STRUCTURAL -> "结构性错误，不可自动重试。请检查代码逻辑和配置。原始异常: " + e.getClass().getSimpleName();
        };
    }

    /**
     * 结构化错误信息
     * <p>
     * 包含错误类型、错误消息、修复指令和是否可重试标志，
     * 供 Agent 理解错误并决策下一步行动。
     */
    public static class StructuredError {
        /** 错误类型 */
        private final ErrorType errorType;

        /** 错误消息 */
        private final String message;

        /** 修复指令（告诉 Agent 怎么修） */
        private final String fixInstructions;

        /** 是否可重试 */
        private final boolean retryable;

        /**
         * 构造结构化错误
         *
         * @param errorType       错误类型
         * @param message         错误消息
         * @param fixInstructions 修复指令
         * @param retryable       是否可重试
         */
        public StructuredError(ErrorType errorType, String message, String fixInstructions, boolean retryable) {
            this.errorType = errorType;
            this.message = message;
            this.fixInstructions = fixInstructions;
            this.retryable = retryable;
        }

        public ErrorType getErrorType() {
            return errorType;
        }

        public String getMessage() {
            return message;
        }

        public String getFixInstructions() {
            return fixInstructions;
        }

        public boolean isRetryable() {
            return retryable;
        }

        @Override
        public String toString() {
            return String.format("StructuredError{type=%s, retryable=%s, message='%s', fix='%s'}",
                    errorType, retryable, message, fixInstructions);
        }
    }
}
