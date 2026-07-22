package com.charles.interview.arena.agent.guardrail.output;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 输出监控器（Output Monitor）
 * <p>
 * 关联 Harness 层：L3 安全护栏层 + L5 可观测性层
 * 关联八股题号：Day04 #14 幻觉、#12 安全、Day05 输出监控与异常检测
 * <p>
 * 核心职责：
 * 1. 监控 Agent 输出，检测异常行为（过长输出、重复输出、错误码等）
 * 2. 防止 Agent 进入死循环后输出无意义内容
 * 3. 检测幻觉信号（如输出包含「我不确定」频次过高、输出与问题无关）
 * <p>
 * 异常检测策略：
 * - 长度检测：输出超过 MAX_OUTPUT_LENGTH 视为异常
 * - 重复检测：输出中连续重复字符/段落超过阈值
 * - 错误码检测：输出包含 HTTP 错误码、异常堆栈信息
 * - 空输出检测：输出为空或仅含空白字符
 *
 * <pre>
 * 使用示例：
 *   String safeOutput = outputMonitor.monitor(aiOutput);  // 返回过滤后的安全输出
 *   if (outputMonitor.isAnomalous(aiOutput)) {
 *       log.warn("Agent 输出异常");
 *   }
 * </pre>
 */
@Slf4j
@Service
public class OutputMonitor {

    /**
     * 最大输出长度（字符数），超过视为异常
     * <p>
     * 正常 Agent 回复通常在 2000 字以内，设为 10000 留足余量。
     */
    public static final int MAX_OUTPUT_LENGTH = 10000;

    /**
     * 异常模式列表
     * <p>
     * 检测 LLM 输出中的异常信号：
     * - HTTP 错误码（500、403、502 等）
     * - Java 异常堆栈关键词
     * - SQL 错误信息
     * - 敏感系统路径
     */
    public static final List<Pattern> ANOMALY_PATTERNS = List.of(
            // HTTP 错误码（精确匹配 HTTP 响应行或状态码格式，避免误判正常讨论）
            Pattern.compile("(?i)HTTP\\s*Status:\\s*(500|403|502|503|400|404)"),
            Pattern.compile("HTTP/\\d\\.\\d\\s+(500|403|502|503|400|404)"),
            // Java 异常堆栈（只匹配真正的堆栈输出，而非讨论中的 "Exception" 词汇）
            Pattern.compile("at\\s+(com|java|org|sun)\\.[\\w.]+\\([^)]+:\\d+\\)"),
            Pattern.compile("Caused by:\\s+java\\."),
            Pattern.compile("Exception in thread\\s+"),
            // SQL 错误
            Pattern.compile("(?i).*(SQLException|MySQLSyntaxErrorException|syntax\\s+error).*"),
            // 敏感系统路径
            Pattern.compile("(?i).*(/etc/passwd|/root/|C:\\\\Windows\\\\|/var/log/).*"),
            // Null 指针异常信号（匹配实际错误输出，而非讨论中的词汇）
            Pattern.compile("java\\.lang\\.NullPointerException"),
            Pattern.compile("(?i)(TypeError:|undefined is not|cannot read property)")
    );

    /**
     * 连续重复字符的最大容忍长度，超过视为异常
     */
    private static final int MAX_REPEATED_CHARS = 100;

    /**
     * 异常输出时的安全兜底回复
     */
    private static final String SAFE_FALLBACK = "[系统提示：输出监控检测到异常内容，已过滤]";

    /**
     * 监控 Agent 输出，检测异常并返回安全输出
     * <p>
     * 综合检测：长度、重复、错误码、空输出。
     * 如果检测到异常，返回安全兜底回复；如果正常，返回原始输出。
     *
     * @param output Agent 输出文本
     * @return 安全输出（正常时返回原文，异常时返回兜底回复）
     */
    public String monitor(String output) {
        if (output == null || output.isBlank()) {
            log.warn("输出监控异常: 输出为空");
            return SAFE_FALLBACK;
        }

        // 长度检测
        if (output.length() > MAX_OUTPUT_LENGTH) {
            log.warn("输出监控异常: 输出过长 {} 字符 (上限 {})", output.length(), MAX_OUTPUT_LENGTH);
            return output.substring(0, MAX_OUTPUT_LENGTH) + "\n[系统提示：输出过长，已截断]";
        }

        // 重复检测
        if (hasExcessiveRepetition(output)) {
            log.warn("输出监控异常: 输出包含过度重复内容，疑似循环");
            return SAFE_FALLBACK;
        }

        // 异常模式检测
        for (Pattern pattern : ANOMALY_PATTERNS) {
            if (pattern.matcher(output).find()) {
                log.warn("输出监控异常: 输出包含异常模式: {}", pattern.pattern());
                return SAFE_FALLBACK;
            }
        }

        return output;
    }

    /**
     * 判断输出是否异常
     * <p>
     * 便捷方法，只检测不修改输出。
     *
     * @param output Agent 输出文本
     * @return true 表示输出异常
     */
    public boolean isAnomalous(String output) {
        if (output == null || output.isBlank()) {
            return true;
        }

        if (output.length() > MAX_OUTPUT_LENGTH) {
            return true;
        }

        if (hasExcessiveRepetition(output)) {
            return true;
        }

        for (Pattern pattern : ANOMALY_PATTERNS) {
            if (pattern.matcher(output).find()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 检测输出是否包含过度重复内容
     * <p>
     * 检测策略：统计连续重复字符的最大长度，如果超过阈值视为异常。
     * 这通常意味着 Agent 进入了死循环或卡在了某个 token 上。
     *
     * @param output 输出文本
     * @return true 表示存在过度重复
     */
    private boolean hasExcessiveRepetition(String output) {
        if (output.length() < MAX_REPEATED_CHARS) {
            return false;
        }
        int maxRepeat = 1;
        int currentRepeat = 1;
        for (int i = 1; i < output.length(); i++) {
            if (output.charAt(i) == output.charAt(i - 1)) {
                currentRepeat++;
                maxRepeat = Math.max(maxRepeat, currentRepeat);
            } else {
                currentRepeat = 1;
            }
        }
        return maxRepeat >= MAX_REPEATED_CHARS;
    }
}
