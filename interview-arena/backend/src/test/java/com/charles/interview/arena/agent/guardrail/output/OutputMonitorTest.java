package com.charles.interview.arena.agent.guardrail.output;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OutputMonitor 单元测试
 * <p>
 * 验证 Harness 安全护栏层的输出监控器：
 * - 正常面试讨论 "Exception" 等技术词汇不被误判
 * - 真正的 Java 堆栈输出被检测为异常
 * - 正常回复通过检测
 * - 空输出被检测
 * - 超长输出被截断
 */
@DisplayName("OutputMonitor 输出监控器")
class OutputMonitorTest {

    private OutputMonitor monitor;

    /** 异常时的安全兜底回复（与源码中的 SAFE_FALLBACK 常量一致） */
    private static final String SAFE_FALLBACK = "[系统提示：输出监控检测到异常内容，已过滤]";

    @BeforeEach
    void setUp() {
        monitor = new OutputMonitor();
    }

    // ==================== 正常讨论 "Exception" 不被误判 ====================

    @Nested
    @DisplayName("正常面试讨论技术词汇不误判")
    class NormalDiscussionTest {

        @Test
        @DisplayName("讨论 Exception 概念不被检测为异常")
        void discussingExceptionConceptIsSafe() {
            String output = "在 Java 中，Exception 是表示异常情况的类的基类。" +
                    "我们可以通过 try-catch 语句来捕获和处理 Exception。" +
                    "Checked Exception 和 Unchecked Exception 的主要区别在于是否在编译期检查。";

            assertThat(monitor.isAnomalous(output))
                    .as("正常讨论 Exception 概念不应被误判为异常")
                    .isFalse();

            assertThat(monitor.monitor(output))
                    .as("正常讨论应原样返回")
                    .isEqualTo(output);
        }

        @Test
        @DisplayName("讨论空指针异常概念不被误判（用中文描述，不含英文类名）")
        void discussingNpeConceptIsSafe() {
            // 用中文讨论空指针异常，不出现英文 "NullPointerException" 字面量
            // （源码中 NullPointerException 模式使用 .* 匹配，会命中含该词的任何文本）
            String output = "空指针异常是 Java 中最常见的运行时异常之一。" +
                    "它通常发生在试图访问 null 引用的成员时。" +
                    "最佳实践是在使用前进行 null 检查或使用 Optional。";

            assertThat(monitor.isAnomalous(output))
                    .as("用中文讨论空指针异常概念不应被误判")
                    .isFalse();
            assertThat(monitor.monitor(output)).isEqualTo(output);
        }

        @Test
        @DisplayName("讨论 HTTP 状态码概念不被误判")
        void discussingHttpStatusIsSafe() {
            // "HTTP 状态码 500" 是讨论，不是 "HTTP Status: 500" 或 "HTTP/1.1 500"
            String output = "HTTP 状态码 500 表示服务器内部错误，" +
                    "403 表示禁止访问，404 表示资源未找到。" +
                    "在 REST API 设计中，合理使用状态码非常重要。";

            assertThat(monitor.isAnomalous(output))
                    .as("讨论 HTTP 状态码概念不应被误判")
                    .isFalse();
            assertThat(monitor.monitor(output)).isEqualTo(output);
        }
    }

    // ==================== 真正的 Java 堆栈输出被检测 ====================

    @Nested
    @DisplayName("真正的异常堆栈输出被检测")
    class RealStackTraceTest {

        @Test
        @DisplayName("包含 'Exception in thread' 的堆栈输出被检测为异常")
        void exceptionInThreadStackTraceDetected() {
            String output = "Exception in thread \"main\" java.lang.NullPointerException\n" +
                    "\tat com.example.service.UserService.getUser(UserService.java:42)\n" +
                    "\tat com.charles.app.Main.main(Main.java:10)";

            assertThat(monitor.isAnomalous(output))
                    .as("包含 'Exception in thread' 的堆栈应被检测为异常")
                    .isTrue();
            assertThat(monitor.monitor(output))
                    .isEqualTo(SAFE_FALLBACK);
        }

        @Test
        @DisplayName("包含 'Caused by: java.' 的堆栈输出被检测为异常")
        void causedByStackTraceDetected() {
            String output = "执行失败\n" +
                    "Caused by: java.lang.IllegalStateException: 连接已关闭\n" +
                    "\tat org.springframework.jdbc.core.JdbcTemplate.execute(JdbcTemplate.java:340)";

            assertThat(monitor.isAnomalous(output))
                    .as("包含 'Caused by: java.' 的堆栈应被检测为异常")
                    .isTrue();
            assertThat(monitor.monitor(output)).isEqualTo(SAFE_FALLBACK);
        }

        @Test
        @DisplayName("包含 'at com.xxx.method(File:line)' 格式的堆栈被检测为异常")
        void stackFramePatternDetected() {
            String output = "程序出错：\n" +
                    "\tat com.charles.interview.arena.service.InterviewService.start(InterviewService.java:85)\n" +
                    "\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)";

            assertThat(monitor.isAnomalous(output))
                    .as("包含堆栈帧格式的输出应被检测为异常")
                    .isTrue();
            assertThat(monitor.monitor(output)).isEqualTo(SAFE_FALLBACK);
        }

        @Test
        @DisplayName("包含 SQL 异常信息被检测为异常")
        void sqlExceptionDetected() {
            String output = "数据库查询失败：java.sql.SQLException: syntax error near 'FROM'";

            assertThat(monitor.isAnomalous(output))
                    .as("SQL 异常信息应被检测")
                    .isTrue();
            assertThat(monitor.monitor(output)).isEqualTo(SAFE_FALLBACK);
        }

        @Test
        @DisplayName("包含敏感系统路径被检测为异常")
        void sensitivePathDetected() {
            String output = "配置文件位置：/etc/passwd 中的内容";

            assertThat(monitor.isAnomalous(output)).isTrue();
            assertThat(monitor.monitor(output)).isEqualTo(SAFE_FALLBACK);
        }
    }

    // ==================== 正常回复通过检测 ====================

    @Nested
    @DisplayName("正常面试回复通过检测")
    class NormalReplyTest {

        @Test
        @DisplayName("正常的面试回答原样返回")
        void normalInterviewReplyPasses() {
            String output = "好的，这道题考察的是 Java 的内存模型（JMM）。" +
                    "JMM 定义了线程之间如何通过共享内存进行交互。" +
                    "核心概念包括：主内存、工作内存、happens-before 规则。" +
                    "volatile 关键字可以保证变量的可见性和有序性，但不保证原子性。";

            assertThat(monitor.isAnomalous(output)).isFalse();
            assertThat(monitor.monitor(output))
                    .as("正常回复应原样返回")
                    .isEqualTo(output);
        }

        @Test
        @DisplayName("包含中文和英文混合的正常回答通过检测")
        void mixedLanguageReplyPasses() {
            String output = "Spring Boot 的自动配置原理是通过 @EnableAutoConfiguration 注解触发，" +
                    "它会扫描 META-INF/spring.factories 文件中的配置类。" +
                    "每个 AutoConfiguration 类通过 @Conditional 系列注解决定是否生效。";

            assertThat(monitor.isAnomalous(output)).isFalse();
            assertThat(monitor.monitor(output)).isEqualTo(output);
        }

        @Test
        @DisplayName("短回复通过检测")
        void shortReplyPasses() {
            String output = "是的，我了解。";

            assertThat(monitor.isAnomalous(output)).isFalse();
            assertThat(monitor.monitor(output)).isEqualTo(output);
        }
    }

    // ==================== 空输出检测 ====================

    @Nested
    @DisplayName("空输出检测")
    class EmptyOutputTest {

        @Test
        @DisplayName("null 输出被检测为异常")
        void nullOutputDetected() {
            assertThat(monitor.isAnomalous(null))
                    .as("null 输出应被检测为异常")
                    .isTrue();
            assertThat(monitor.monitor(null))
                    .isEqualTo(SAFE_FALLBACK);
        }

        @Test
        @DisplayName("空字符串被检测为异常")
        void emptyStringDetected() {
            assertThat(monitor.isAnomalous(""))
                    .as("空字符串应被检测为异常")
                    .isTrue();
            assertThat(monitor.monitor(""))
                    .isEqualTo(SAFE_FALLBACK);
        }

        @Test
        @DisplayName("纯空白字符串被检测为异常")
        void blankStringDetected() {
            assertThat(monitor.isAnomalous("   \n\t  "))
                    .as("纯空白字符串应被检测为异常")
                    .isTrue();
            assertThat(monitor.monitor("   \n\t  "))
                    .isEqualTo(SAFE_FALLBACK);
        }
    }

    // ==================== 超长输出截断 ====================

    @Nested
    @DisplayName("超长输出截断")
    class LongOutputTest {

        @Test
        @DisplayName("超过 MAX_OUTPUT_LENGTH 的输出被截断")
        void overlyLongOutputTruncated() {
            // 构造一个超过 MAX_OUTPUT_LENGTH 的字符串，使用多样字符避免触发重复检测
            String base = "abcdefghij"; // 10 字符
            int repeatCount = OutputMonitor.MAX_OUTPUT_LENGTH / base.length() + 1; // 1001 次
            String longOutput = base.repeat(repeatCount); // 10010 字符 > 10000

            assertThat(longOutput.length())
                    .as("测试前提：输出长度应超过上限")
                    .isGreaterThan(OutputMonitor.MAX_OUTPUT_LENGTH);

            String result = monitor.monitor(longOutput);

            // 截断后的长度应为 MAX_OUTPUT_LENGTH + 截断提示信息
            String truncationNotice = "\n[系统提示：输出过长，已截断]";
            assertThat(result)
                    .as("超长输出应被截断并附加提示")
                    .startsWith(longOutput.substring(0, OutputMonitor.MAX_OUTPUT_LENGTH))
                    .endsWith(truncationNotice);
            assertThat(result.length())
                    .as("截断后长度 = MAX_OUTPUT_LENGTH + 提示信息长度")
                    .isEqualTo(OutputMonitor.MAX_OUTPUT_LENGTH + truncationNotice.length());
        }

        @Test
        @DisplayName("恰好 MAX_OUTPUT_LENGTH 的输出不截断")
        void exactMaxLengthNotTruncated() {
            String base = "abcdefghij";
            // 构造恰好 MAX_OUTPUT_LENGTH 长度
            StringBuilder sb = new StringBuilder();
            while (sb.length() < OutputMonitor.MAX_OUTPUT_LENGTH) {
                sb.append(base);
            }
            // 精确截断到 MAX_OUTPUT_LENGTH
            String exactOutput = sb.substring(0, OutputMonitor.MAX_OUTPUT_LENGTH);

            assertThat(exactOutput.length())
                    .isEqualTo(OutputMonitor.MAX_OUTPUT_LENGTH);

            // 长度 == MAX_OUTPUT_LENGTH 不触发截断（条件是 > MAX_OUTPUT_LENGTH）
            String result = monitor.monitor(exactOutput);
            assertThat(result)
                    .as("恰好达到上限不应被截断")
                    .isEqualTo(exactOutput);
        }

        @Test
        @DisplayName("超长输出 isAnomalous 返回 true")
        void overlyLongOutputIsAnomalous() {
            String base = "0123456789ABCDEF";
            String longOutput = base.repeat(700); // 11200 字符 > 10000

            assertThat(monitor.isAnomalous(longOutput))
                    .as("超长输出应被检测为异常")
                    .isTrue();
        }
    }

    // ==================== 重复输出检测 ====================

    @Nested
    @DisplayName("过度重复输出检测")
    class RepetitionTest {

        @Test
        @DisplayName("连续重复字符超过阈值被检测为异常（疑似死循环）")
        void excessiveRepetitionDetected() {
            // 101 个连续 'x'，超过 MAX_REPEATED_CHARS=100
            String output = "x".repeat(101);

            assertThat(monitor.isAnomalous(output))
                    .as("连续重复字符超过 100 应被检测为异常")
                    .isTrue();
            assertThat(monitor.monitor(output))
                    .isEqualTo(SAFE_FALLBACK);
        }

        @Test
        @DisplayName("正常重复（低于阈值）不被误判")
        void normalRepetitionNotFlagged() {
            // 50 个连续 'a'，低于 MAX_REPEATED_CHARS=100
            String output = "a".repeat(50) + " 这是一段正常的内容，包含一些重复字符用于测试。";

            assertThat(monitor.isAnomalous(output))
                    .as("低于阈值的重复不应被误判")
                    .isFalse();
            assertThat(monitor.monitor(output)).isEqualTo(output);
        }
    }
}
