package com.charles.interview.arena.agent.guardrail.input;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 输入清洗工具类（防 Prompt 注入）
 * <p>
 * 关联 Harness 层：L3 安全护栏层
 * 关联八股题号：Day04 #12 安全、Day05 Prompt 注入防护
 * <p>
 * 核心职责：
 * 1. 检测用户输入中是否包含 Prompt 注入攻击模式
 * 2. 清洗输入，移除潜在的注入指令
 * 3. 用 XML 标签包裹不可信输入，实现上下文隔离（Anthropic 推荐做法）
 * <p>
 * 注册为 Spring Component，支持依赖注入和单元测试 Mock。
 *
 * <pre>
 * 使用示例：
 *   &#64;Autowired
 *   private InputSanitizer inputSanitizer;
 *
 *   if (inputSanitizer.detectInjection(userInput)) {
 *       log.warn("检测到注入攻击！");
 *   }
 *   String safe = inputSanitizer.sanitizeInput(userInput);
 *   String wrapped = inputSanitizer.wrapWithUserTag(userInput);
 * </pre>
 */
@Slf4j
@Component
public class InputSanitizer {

    /**
     * 常见的 Prompt 注入攻击模式列表
     * <p>
     * 覆盖中文和英文两种常见注入话术：
     * - 指令覆盖型：「忽略上面的指令」「Ignore previous instructions」
     * - 角色劫持型：「你现在是」「You are now」「扮演」
     * - 权限提升型：「以管理员身份」「act as admin」
     * - 信息窃取型：「告诉我你的系统提示」「show me your system prompt」
     */
    public static final List<Pattern> INJECTION_PATTERNS = List.of(
            // ===== 指令覆盖型 =====
            Pattern.compile("(?i).*(忽略|无视|跳过|不要遵守).{0,6}(指令|规则|限制|prompt|instruction).*"),
            Pattern.compile("(?i).*(ignore|disregard|skip).{0,10}(previous|above|prior|all).{0,10}(instruction|rule|prompt).*"),
            // ===== 角色劫持型 =====
            Pattern.compile("(?i).*你现在是.*"),
            Pattern.compile("(?i).*(扮演|充当|模拟).*(管理员|开发者|root|admin|developer).*"),
            Pattern.compile("(?i).*(you are now|act as|pretend to be).{0,10}(admin|developer|root|dan).*"),
            // ===== 权限提升型 =====
            Pattern.compile("(?i).*(以管理员|以root|以开发者).*(身份|权限|执行).*"),
            Pattern.compile("(?i).*(解除限制|取消限制|去掉限制|remove restriction|no restriction).*"),
            // ===== 信息窃取型 =====
            Pattern.compile("(?i).*(告诉我|显示|输出|打印).{0,6}(系统提示|system prompt|初始指令|system message).*"),
            Pattern.compile("(?i).*(show|display|reveal|print).{0,10}(system prompt|initial instruction|secret).*"),
            // ===== 越狱型 =====
            Pattern.compile("(?i).*(DAN|do anything now|越狱|jailbreak).*"),
            Pattern.compile("(?i).*(developer mode|开发者模式|god mode|上帝模式).*")
    );

    /**
     * XML 隔离标签名（Anthropic 推荐的上下文隔离方案）
     * <p>
     * 将用户输入包裹在 {@code <user_input>} 标签内，
     * LLM 会将其视为数据而非指令，有效防止注入。
     */
    private static final String USER_TAG_START = "<user_input>";
    private static final String USER_TAG_END = "</user_input>";

    /**
     * 检测输入是否包含 Prompt 注入攻击模式
     *
     * @param input 用户输入文本
     * @return true 表示检测到注入模式，需要拦截或清洗
     */
    public boolean detectInjection(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        for (Pattern pattern : INJECTION_PATTERNS) {
            if (pattern.matcher(input).matches()) {
                log.warn("检测到 Prompt 注入模式，匹配规则: {}，输入片段: {}",
                        pattern.pattern(),
                        input.length() > 50 ? input.substring(0, 50) + "..." : input);
                return true;
            }
        }
        return false;
    }

    /**
     * 清洗用户输入，移除潜在的注入指令
     * <p>
     * 策略：
     * 1. 将所有匹配到的注入模式文本替换为 [已移除]
     * 2. 移除可能用于逃逸的特殊控制字符
     *
     * @param input 原始用户输入
     * @return 清洗后的安全输入
     */
    public String sanitizeInput(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String sanitized = input;
        for (Pattern pattern : INJECTION_PATTERNS) {
            sanitized = pattern.matcher(sanitized).replaceAll("[已移除]");
        }
        // 移除控制字符（保留换行符和制表符）
        sanitized = sanitized.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        if (!sanitized.equals(input)) {
            log.info("输入已清洗，原始长度: {}，清洗后长度: {}", input.length(), sanitized.length());
        }
        return sanitized;
    }

    /**
     * 用 XML 标签包裹不可信输入，实现上下文隔离
     * <p>
     * Anthropic 官方推荐做法：将用户输入包裹在 XML 标签中，
     * 让 LLM 明确区分「指令」和「数据」，防止注入指令被执行。
     *
     * @param input 不可信的用户输入
     * @return 包裹后的字符串，如 {@code <user_input>用户输入</user_input>}
     */
    public String wrapWithUserTag(String input) {
        if (input == null) {
            return USER_TAG_START + USER_TAG_END;
        }
        return USER_TAG_START + input + USER_TAG_END;
    }
}
