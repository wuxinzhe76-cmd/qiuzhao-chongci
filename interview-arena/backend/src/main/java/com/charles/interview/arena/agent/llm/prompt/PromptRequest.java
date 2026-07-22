package com.charles.interview.arena.agent.llm.prompt;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Prompt 请求（模板 + 参数）
 * <p>
 * 封装 Prompt 模板原文和待注入参数，
 * 由 LlmInvoker 使用 Spring AI 原生 .text().param() 注入，
 * 而非手动 String.replace。
 */
@Data
@AllArgsConstructor
public class PromptRequest {

    /** 模板原文（含 {placeholder} 占位符，Spring AI 原生格式） */
    private String template;

    /** 待注入参数（key 不含花括号，如 "questionTitle"） */
    private Map<String, String> params;

    /**
     * 创建带参数的 Prompt 请求
     */
    public static PromptRequest of(String template, Map<String, String> params) {
        return new PromptRequest(template, params);
    }

    /**
     * 创建无参数的 Prompt 请求
     */
    public static PromptRequest of(String template) {
        return new PromptRequest(template, Map.of());
    }
}
