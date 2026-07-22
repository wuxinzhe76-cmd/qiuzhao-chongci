package com.charles.interview.arena.agent.perception.validation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.perception.model.RawInput;

/**
 * 输入格式校验器
 * <p>
 * 职责:校验输入的基本格式(非空/长度/字段完整性)。
 * 不涉及安全检测(那是 InputGuardrail 的职责)。
 */
@Component
public class InputFormatValidator {

    /** 文本最大长度(防超长输入撑爆上下文) */
    @Value("${perception.max-text-length:10000}")
    private int maxTextLength;

    /** 文本最小长度(防空输入) */
    private static final int MIN_TEXT_LENGTH = 1;

    /**
     * 校验输入格式
     *
     * @param input 原始输入
     * @throws IllegalArgumentException 格式不合法
     */
    public void validate(RawInput input) {
        if (input == null) {
            throw new IllegalArgumentException("输入不能为空");
        }

        if (input.text() == null || input.text().isBlank()) {
            throw new IllegalArgumentException("文本内容不能为空");
        }

        if (input.text().length() < MIN_TEXT_LENGTH) {
            throw new IllegalArgumentException("文本内容过短");
        }

        if (input.text().length() > maxTextLength) {
            throw new IllegalArgumentException(
                    "文本内容超过最大长度限制: " + input.text().length() + " > " + maxTextLength);
        }
    }

    /**
     * 校验文本格式(轻量版,不依赖 RawInput)
     *
     * @param text 文本
     * @return true 表示格式合法
     */
    public boolean isValid(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return text.length() <= maxTextLength;
    }
}
