package com.charles.interview.arena.agent.reflection.harness;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.reflection.model.ErrorCorrection;
import com.charles.interview.arena.agent.reflection.model.ReflectionResult;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * 输出校验器(3 层校验)
 * <p>
 * 职责:校验 LLM 输出是否合法,生成修复提示。
 * <p>
 * 3 层校验:
 * 1. JSON 解析(由 Spring AI .entity() 完成,不在此处理)
 * 2. Bean Validation(JSR303,@NotNull/@NotBlank 等)
 * 3. 业务语义校验(长度/范围/业务规则)
 * <p>
 * 校验失败 -> 生成修复提示(给 LLM 重试用)
 */
@Component
public class OutputValidator {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /** 业务校验:reply_to_user 最大长度 */
    private static final int MAX_REPLY_LENGTH = 2000;

    /**
     * 校验 LLM 输出
     *
     * @param response LLM 返回的对象
     * @return 校验结果(valid 表示通过)
     */
    public <T> ReflectionResult validate(T response) {
        if (response == null) {
            return ReflectionResult.invalid(
                    "NULL_RESPONSE", "LLM 返回 null",
                    ErrorCorrection.LOGIC_ERROR,
                    "请重新输出,不要返回空结果。");
        }

        // 第 2 层:Bean Validation
        Set<ConstraintViolation<T>> violations = validator.validate(response);
        if (!violations.isEmpty()) {
            String errorMsg = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Bean Validation 失败");
            String repairPrompt = "上次输出校验失败: " + errorMsg
                    + "\n请严格按 JSON Schema 重新输出。";
            return ReflectionResult.invalid(
                    "VALIDATION_FAILED", errorMsg,
                    ErrorCorrection.PARAM_ERROR, repairPrompt);
        }

        // 第 3 层:业务语义校验
        String businessError = validateBusiness(response);
        if (businessError != null) {
            String repairPrompt = "上次输出业务校验失败: " + businessError
                    + "\n请修正后重新输出。";
            return ReflectionResult.invalid(
                    "BUSINESS_VALIDATION_FAILED", businessError,
                    ErrorCorrection.PARAM_ERROR, repairPrompt);
        }

        return ReflectionResult.pass();
    }

    /**
     * 业务语义校验
     * <p>
     * 针对特定 DTO 做业务规则校验。
     */
    private <T> String validateBusiness(T response) {
        // 面试响应 DTO 的业务校验
        if (response instanceof com.charles.interview.arena.model.dto.interview.AiInterviewResponseDTO dto) {
            if (dto.getReplyToUser() != null && dto.getReplyToUser().length() > MAX_REPLY_LENGTH) {
                return "reply_to_user 长度超过 " + MAX_REPLY_LENGTH + " 字符";
            }
            if (dto.getActionDirective() != null) {
                String directive = dto.getActionDirective().trim().toUpperCase();
                if (!directive.equals("DEEP_DIVE") && !directive.equals("NEXT_QUESTION")
                        && !directive.equals("END_INTERVIEW")) {
                    return "action_directive 必须是 DEEP_DIVE/NEXT_QUESTION/END_INTERVIEW,实际: " + directive;
                }
            }
        }
        return null; // 校验通过
    }
}
