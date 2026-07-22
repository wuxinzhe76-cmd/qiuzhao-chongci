package com.charles.interview.arena.agent.llm.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.harness.common.CircuitBreaker;
import com.charles.interview.arena.agent.planning.harness.StructuredErrorHandler;
import com.charles.interview.arena.agent.planning.harness.StructuredErrorHandler.StructuredError;
import com.charles.interview.arena.agent.harness.common.TokenBudget;
import com.charles.interview.arena.agent.llm.prompt.PromptRequest;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ConstraintViolation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * LLM 调用器
 * <p>
 * 职责：封装 LLM 调用的全部工程化逻辑，与工具层解耦。
 * <p>
 * 工程化能力（Harness L2/L4/L5）：
 * 1. TokenBudget 预算检查（L5 熵管理）
 * 2. CircuitBreaker 熔断保护（L2 工具治理）
 * 3. 指数退避重试（L4 反馈循环）
 * 4. 错误分类处理（L4 反馈循环）
 * 5. 结构化输出三层校验 + 修复重试（L4 反馈循环）
 * 6. Spring AI 原生参数注入（.text().param()，框架层转义）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmInvoker {

    private final ChatClient chatClient;
    private final TokenBudget tokenBudget;
    private final CircuitBreaker circuitBreaker;
    private final StructuredErrorHandler structuredErrorHandler;

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static final int MAX_RETRIES = 2;
    private static final long INITIAL_BACKOFF_MS = 1000;
    private static final int MAX_REPAIR_RETRIES = 1;

    /**
     * 调用 LLM，返回结构化结果
     * <p>
     * 完整流程：Token预算检查 -> 熔断器检查 -> LLM调用(原生参数注入) -> 三层校验 -> 修复重试
     */
    public <T> LlmResult<T> invoke(PromptRequest systemPrompt, PromptRequest userPrompt, Class<T> responseType) {
        // 1. Token 预算检查
        int estimatedTokens = tokenBudget.estimateTokens(systemPrompt.getTemplate() + userPrompt.getTemplate());
        if (!tokenBudget.checkBudget(estimatedTokens)) {
            log.warn("Token 预算不足，跳过 LLM 调用: estimated={}", estimatedTokens);
            return LlmResult.failure("Token 预算不足", "BUDGET_EXCEEDED");
        }

        // 2. 熔断器 OPEN 时直接返回
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN) {
            log.warn("熔断器处于 OPEN 状态，直接返回失败");
            return LlmResult.failure("熔断器已打开，请稍后重试", "CIRCUIT_OPEN");
        }

        try {
            T response = doCallAndValidate(systemPrompt, userPrompt, responseType);

            if (response == null) {
                log.warn("LLM 返回 null");
                return LlmResult.failure("LLM 返回 null", "NULL_RESPONSE");
            }

            String responseText = response.toString();
            int actualTokens = tokenBudget.estimateTokens(
                    systemPrompt.getTemplate() + userPrompt.getTemplate() + responseText);
            tokenBudget.recordUsage(actualTokens);

            return LlmResult.success(response);

        } catch (CircuitBreaker.CircuitBreakerOpenException e) {
            log.warn("熔断器打开: {}", e.getMessage());
            return LlmResult.failure("熔断器已打开", "CIRCUIT_OPEN");

        } catch (ValidationFailedException e) {
            log.warn("结构化输出校验失败: {}", e.getMessage());
            return LlmResult.failure(e.getMessage(), "VALIDATION_FAILED");

        } catch (Exception e) {
            StructuredError error = structuredErrorHandler.createError(e, "LLM 调用失败");
            log.error("LLM 调用失败 | errorType={}, fixInstructions={}",
                    error.getErrorType(), error.getFixInstructions());

            return switch (error.getErrorType()) {
                case TRANSIENT -> retryWithBackoff(systemPrompt, userPrompt, responseType);
                case SEMANTIC -> LlmResult.failure(
                        "LLM 调用失败[SEMANTIC]: " + e.getMessage(), error.getErrorType().name());
                case STRUCTURAL -> LlmResult.failure(
                        "LLM 调用失败[STRUCTURAL]: " + e.getMessage(), error.getErrorType().name());
            };
        }
    }

    /**
     * 调用 LLM + 三层校验 + 修复重试
     */
    private <T> T doCallAndValidate(PromptRequest systemPrompt, PromptRequest userPrompt, Class<T> responseType) {
        T response = doCall(systemPrompt, userPrompt, responseType);
        if (response == null) return null;

        // 第1次校验
        String validationError = validate(response);
        if (validationError == null) return response;

        // 校验失败 -> 修复重试（追加错误信息到 User Prompt）
        log.warn("第1次校验失败，执行修复重试: {}", validationError);
        String repairText = userPrompt.getTemplate()
                + "\n\n【上次输出校验失败，请修复】\n错误: " + validationError
                + "\n请严格按 JSON Schema 重新输出。";
        PromptRequest repairedUserPrompt = PromptRequest.of(repairText, userPrompt.getParams());

        for (int attempt = 1; attempt <= MAX_REPAIR_RETRIES; attempt++) {
            T retryResponse = doCall(systemPrompt, repairedUserPrompt, responseType);
            if (retryResponse == null) continue;

            String retryError = validate(retryResponse);
            if (retryError == null) {
                log.info("修复重试第{}次校验通过", attempt);
                return retryResponse;
            }
            log.warn("修复重试第{}次仍失败: {}", attempt, retryError);
        }

        throw new ValidationFailedException("校验失败（修复重试" + MAX_REPAIR_RETRIES + "次仍不通过）: " + validationError);
    }

    /**
     * 三层校验
     */
    private <T> String validate(T response) {
        // 第2层：Bean Validation
        Set<ConstraintViolation<T>> violations = validator.validate(response);
        if (!violations.isEmpty()) {
            return violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Bean Validation 失败");
        }

        // 第3层：业务语义校验
        if (response instanceof com.charles.interview.arena.model.dto.interview.AiInterviewResponseDTO dto) {
            return validateBusiness(dto);
        }
        return null;
    }

    private String validateBusiness(com.charles.interview.arena.model.dto.interview.AiInterviewResponseDTO dto) {
        if (dto.getReplyToUser() != null && dto.getReplyToUser().length() > 2000) {
            return "reply_to_user 长度超过 2000 字符";
        }
        return null;
    }

    /**
     * 指数退避重试
     */
    private <T> LlmResult<T> retryWithBackoff(PromptRequest systemPrompt, PromptRequest userPrompt, Class<T> responseType) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            long backoff = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
            long jitter = (long) (Math.random() * backoff * 0.3);
            log.warn("瞬态错误，第{}次重试，等待{}ms", attempt, backoff + jitter);

            try {
                Thread.sleep(backoff + jitter);
                T retryResponse = doCallAndValidate(systemPrompt, userPrompt, responseType);
                if (retryResponse != null) {
                    log.info("第{}次重试成功", attempt);
                    return LlmResult.success(retryResponse);
                }
            } catch (CircuitBreaker.CircuitBreakerOpenException e) {
                return LlmResult.failure("熔断器已打开", "CIRCUIT_OPEN");
            } catch (ValidationFailedException e) {
                return LlmResult.failure(e.getMessage(), "VALIDATION_FAILED");
            } catch (Exception retryEx) {
                log.warn("第{}次重试仍失败: {}", attempt, retryEx.getMessage());
                if (attempt == MAX_RETRIES) {
                    return LlmResult.failure("重试次数用尽: " + retryEx.getMessage(), "TRANSIENT");
                }
            }
        }
        return LlmResult.failure("重试次数用尽", "TRANSIENT");
    }

    /**
     * 实际 LLM 调用（Spring AI 原生参数注入 + 熔断器包裹）
     */
    private <T> T doCall(PromptRequest systemPrompt, PromptRequest userPrompt, Class<T> responseType) {
        Supplier<T> aiCall = () -> {
            var spec = chatClient.prompt();

            // System Prompt：原生 .text().param() 注入
            spec.system(sys -> {
                sys.text(systemPrompt.getTemplate());
                if (systemPrompt.getParams() != null) {
                    systemPrompt.getParams().forEach(sys::param);
                }
            });

            // User Prompt：原生 .text().param() 注入
            spec.user(usr -> {
                usr.text(userPrompt.getTemplate());
                if (userPrompt.getParams() != null) {
                    userPrompt.getParams().forEach(usr::param);
                }
            });

            return spec.call().entity(responseType);
        };

        return circuitBreaker.executeProtected(aiCall);
    }

    private static class ValidationFailedException extends RuntimeException {
        public ValidationFailedException(String message) { super(message); }
    }
}
