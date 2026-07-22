package com.charles.interview.arena.agent.guardrail.input;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 安全护栏服务（Security Guard）
 * <p>
 * 关联 Harness 层：L3 安全护栏层
 * 关联八股题号：Day04 #12 安全、Day05 敏感信息泄露防护
 * <p>
 * 核心职责：
 * 1. 输入安全检查：调用 InputSanitizer 检测和清洗注入攻击
 * 2. 输出安全检查：检测 LLM 输出是否包含敏感信息（防止泄露）
 * 3. 敏感数据脱敏：对邮箱、手机号、身份证号进行脱敏处理
 * <p>
 * 设计模式：门面模式（Facade），对外暴露统一的 checkInput/checkOutput 接口，
 * 内部组合 InputSanitizer + 正则匹配完成完整的安全检查链。
 *
 * <pre>
 * 使用示例：
 *   securityGuard.checkInput(userInput);   // 检查输入
 *   securityGuard.checkOutput(llmOutput);   // 检查输出
 *   String masked = securityGuard.maskSensitiveData(text);  // 脱敏
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityGuard {

    /** 注入 InputSanitizer，用于检测和清洗 Prompt 注入 */
    private final InputSanitizer inputSanitizer;

    /**
     * 敏感信息正则模式列表
     * <p>
     * 用于检测和脱敏 LLM 输出中的敏感数据：
     * - 邮箱地址
     * - 手机号（中国大陆 11 位）
     * - 身份证号（18 位，最后一位可能是 X）
     */
    public static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            // 邮箱
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"),
            // 手机号（中国大陆 11 位，1 开头）
            Pattern.compile("1[3-9]\\d{9}"),
            // 身份证号（18 位，最后一位可能是 X）
            Pattern.compile("[1-9]\\d{5}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]"),
            // 银行卡号（16-19 位连续数字）
            Pattern.compile("\\b\\d{16,19}\\b")
    );

    /**
     * 检查输入安全性
     * <p>
     * 调用 InputSanitizer 检测是否包含注入攻击。
     * 如果检测到注入，返回 false 并记录日志。
     *
     * @param input 用户输入
     * @return true 表示输入安全，false 表示检测到注入攻击
     */
    public boolean checkInput(String input) {
        if (input == null || input.isBlank()) {
            return true;
        }
        boolean hasInjection = inputSanitizer.detectInjection(input);
        if (hasInjection) {
            log.warn("安全护栏：输入检测到 Prompt 注入攻击，已拦截");
            return false;
        }
        return true;
    }

    /**
     * 检查输出安全性
     * <p>
     * 检测 LLM 输出是否包含敏感信息（邮箱、手机号、身份证号、银行卡号）。
     * 如果检测到敏感信息，返回 false 并记录日志。
     *
     * @param output LLM 输出文本
     * @return true 表示输出安全，false 表示检测到敏感信息泄露
     */
    public boolean checkOutput(String output) {
        if (output == null || output.isBlank()) {
            return true;
        }
        for (Pattern pattern : SENSITIVE_PATTERNS) {
            if (pattern.matcher(output).find()) {
                log.warn("安全护栏：输出检测到敏感信息泄露，匹配模式: {}", pattern.pattern());
                return false;
            }
        }
        return true;
    }

    /**
     * 对文本中的敏感数据进行脱敏处理
     * <p>
     * 脱敏规则：
     * - 邮箱：只显示前 2 位和域名，如 ch***@example.com
     * - 手机号：中间 4 位用 * 替换，如 138****5678
     * - 身份证号：中间 8 位用 * 替换，如 110100********1234
     * - 银行卡号：只显示后 4 位，如 **** **** **** 1234
     *
     * @param text 原始文本
     * @return 脱敏后的文本
     */
    public String maskSensitiveData(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String masked = text;
        // 脱敏邮箱：ch***@example.com
        masked = SENSITIVE_PATTERNS.get(0).matcher(masked).replaceAll(mr -> {
            String email = mr.group();
            int atIndex = email.indexOf('@');
            if (atIndex <= 2) {
                return "***" + email.substring(atIndex);
            }
            return email.substring(0, 2) + "***" + email.substring(atIndex);
        });
        // 脱敏手机号：138****5678
        masked = SENSITIVE_PATTERNS.get(1).matcher(masked).replaceAll(mr -> {
            String phone = mr.group();
            return phone.substring(0, 3) + "****" + phone.substring(7);
        });
        // 脱敏身份证号：110100********1234
        masked = SENSITIVE_PATTERNS.get(2).matcher(masked).replaceAll(mr -> {
            String id = mr.group();
            return id.substring(0, 6) + "********" + id.substring(14);
        });
        // 脱敏银行卡号：**** **** **** 1234
        masked = SENSITIVE_PATTERNS.get(3).matcher(masked).replaceAll(mr -> {
            String card = mr.group();
            return "**** **** **** " + card.substring(card.length() - 4);
        });
        if (!masked.equals(text)) {
            log.info("安全护栏：敏感数据已脱敏处理");
        }
        return masked;
    }
}
