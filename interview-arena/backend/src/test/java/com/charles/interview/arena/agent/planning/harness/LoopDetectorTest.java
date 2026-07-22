package com.charles.interview.arena.agent.planning.harness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LoopDetector 单元测试
 * <p>
 * 验证 Harness 约束机制中的循环检测器：
 * - 多会话操作历史隔离
 * - 连续相同操作触发循环检测
 * - 不同操作不触发循环检测
 * - clear(sessionId) 只清理指定 session
 */
@DisplayName("LoopDetector 循环检测器")
class LoopDetectorTest {

    private LoopDetector detector;

    @BeforeEach
    void setUp() {
        // maxRounds=10, maxSameAction=3（与默认构造一致，显式传入更清晰）
        detector = new LoopDetector(10, 3);
    }

    // ==================== 会话隔离 ====================

    @Nested
    @DisplayName("会话隔离：不同 sessionId 的操作历史互不影响")
    class SessionIsolationTest {

        @Test
        @DisplayName("session A 的操作记录不影响 session B 的 actionCount")
        void sessionAHistoryDoesNotAffectSessionB() {
            Long sessionA = 1L;
            Long sessionB = 2L;

            // session A 记录 3 次操作
            detector.detectLoop(sessionA, "answer1");
            detector.detectLoop(sessionA, "answer2");
            detector.detectLoop(sessionA, "answer3");

            // session B 应该是空的
            assertThat(detector.getActionCount(sessionB))
                    .as("session B 不应受 session A 影响")
                    .isZero();
        }

        @Test
        @DisplayName("session A 触发循环后，session B 仍可正常检测")
        void sessionATriggeredLoopDoesNotBlockSessionB() {
            Long sessionA = 100L;
            Long sessionB = 200L;

            // session A 连续 3 次相同回答 -> 触发循环
            detector.detectLoop(sessionA, "sameAnswer");
            detector.detectLoop(sessionA, "sameAnswer");
            boolean aLooped = detector.detectLoop(sessionA, "sameAnswer");

            assertThat(aLooped).isTrue();
            assertThat(detector.getViolation(sessionA)).contains("连续相同操作");

            // session B 用不同的回答，不应触发循环
            boolean bLooped = detector.detectLoop(sessionB, "differentAnswer");
            assertThat(bLooped)
                    .as("session B 不应因 session A 的循环而误判")
                    .isFalse();
            assertThat(detector.getViolation(sessionB)).isNull();
        }
    }

    // ==================== 连续相同操作检测 ====================

    @Nested
    @DisplayName("连续相同操作检测")
    class ConsecutiveSameActionTest {

        @Test
        @DisplayName("连续相同操作 3 次触发循环检测")
        void threeConsecutiveSameActionsTriggersLoop() {
            Long sessionId = 1L;

            // 第 1 次：不触发
            boolean first = detector.detectLoop(sessionId, "repeatAnswer");
            assertThat(first).as("第 1 次不应触发循环").isFalse();

            // 第 2 次：不触发
            boolean second = detector.detectLoop(sessionId, "repeatAnswer");
            assertThat(second).as("第 2 次不应触发循环").isFalse();

            // 第 3 次：触发（maxSameAction=3，consecutiveCount >= 3）
            boolean third = detector.detectLoop(sessionId, "repeatAnswer");
            assertThat(third).as("第 3 次连续相同操作应触发循环").isTrue();

            // 违规原因应包含 "连续相同操作"
            assertThat(detector.getViolation(sessionId))
                    .contains("连续相同操作");
        }

        @Test
        @DisplayName("第 3 次触发后 actionCount 应为 3")
        void actionCountAfterThreeSameActions() {
            Long sessionId = 1L;

            detector.detectLoop(sessionId, "x");
            detector.detectLoop(sessionId, "x");
            detector.detectLoop(sessionId, "x");

            assertThat(detector.getActionCount(sessionId)).isEqualTo(3);
        }
    }

    // ==================== 不同操作不触发 ====================

    @Nested
    @DisplayName("不同操作不触发循环检测")
    class DifferentActionsTest {

        @Test
        @DisplayName("3 次不同的回答不触发循环检测")
        void threeDifferentActionsDoNotTriggerLoop() {
            Long sessionId = 1L;

            boolean r1 = detector.detectLoop(sessionId, "answerA");
            boolean r2 = detector.detectLoop(sessionId, "answerB");
            boolean r3 = detector.detectLoop(sessionId, "answerC");

            assertThat(r1).isFalse();
            assertThat(r2).isFalse();
            assertThat(r3).as("3 次不同回答不应触发循环").isFalse();
            assertThat(detector.getViolation(sessionId)).isNull();
        }

        @Test
        @DisplayName("交替不同回答（A B A B）不触发连续检测")
        void alternatingAnswersDoNotTriggerConsecutive() {
            Long sessionId = 1L;

            boolean r1 = detector.detectLoop(sessionId, "A");
            boolean r2 = detector.detectLoop(sessionId, "B");
            boolean r3 = detector.detectLoop(sessionId, "A");
            boolean r4 = detector.detectLoop(sessionId, "B");

            assertThat(r1).isFalse();
            assertThat(r2).isFalse();
            assertThat(r3).isFalse();
            assertThat(r4).as("交替回答不应触发连续检测").isFalse();
            assertThat(detector.getViolation(sessionId)).isNull();
        }
    }

    // ==================== clear(sessionId) ====================

    @Nested
    @DisplayName("clear(sessionId) 只清理指定 session")
    class ClearTest {

        @Test
        @DisplayName("clear 后该 session 的 actionCount 归零")
        void clearResetsActionCount() {
            Long sessionId = 1L;

            detector.detectLoop(sessionId, "answer1");
            detector.detectLoop(sessionId, "answer2");
            assertThat(detector.getActionCount(sessionId)).isEqualTo(2);

            detector.clear(sessionId);

            assertThat(detector.getActionCount(sessionId))
                    .as("clear 后 actionCount 应为 0")
                    .isZero();
        }

        @Test
        @DisplayName("clear(sessionA) 不影响 sessionB 的历史")
        void clearOnlyAffectsSpecifiedSession() {
            Long sessionA = 1L;
            Long sessionB = 2L;

            // 两个 session 各记录 2 次
            detector.detectLoop(sessionA, "a1");
            detector.detectLoop(sessionA, "a2");
            detector.detectLoop(sessionB, "b1");
            detector.detectLoop(sessionB, "b2");

            // 清理 sessionA
            detector.clear(sessionA);

            assertThat(detector.getActionCount(sessionA))
                    .as("sessionA 应被清空")
                    .isZero();
            assertThat(detector.getActionCount(sessionB))
                    .as("sessionB 不应受影响")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("clear 后该 session 的 violation 被清除")
        void clearResetsViolation() {
            Long sessionId = 1L;

            // 触发循环
            detector.detectLoop(sessionId, "same");
            detector.detectLoop(sessionId, "same");
            detector.detectLoop(sessionId, "same");
            assertThat(detector.getViolation(sessionId)).isNotNull();

            detector.clear(sessionId);

            assertThat(detector.getViolation(sessionId))
                    .as("clear 后 violation 应为 null")
                    .isNull();
        }

        @Test
        @DisplayName("clear 后重新记录不会立即触发循环（历史已重置）")
        void clearAllowsFreshStart() {
            Long sessionId = 1L;

            // 触发循环
            detector.detectLoop(sessionId, "same");
            detector.detectLoop(sessionId, "same");
            detector.detectLoop(sessionId, "same");

            // 清理后重新记录相同内容
            detector.clear(sessionId);
            boolean result = detector.detectLoop(sessionId, "same");

            assertThat(result)
                    .as("clear 后历史已重置，单次记录不应触发循环")
                    .isFalse();
            assertThat(detector.getActionCount(sessionId)).isEqualTo(1);
        }
    }

    // ==================== 最大轮次检测 ====================

    @Test
    @DisplayName("超过 maxRounds 触发循环检测")
    void maxRoundsTriggersLoop() {
        // maxRounds=10，第 11 次操作触发
        Long sessionId = 1L;
        detector = new LoopDetector(10, 100); // maxSameAction 设高，排除连续检测干扰

        for (int i = 0; i < 10; i++) {
            boolean looped = detector.detectLoop(sessionId, "answer" + i);
            assertThat(looped).as("第 %d 次不应触发", i + 1).isFalse();
        }

        // 第 11 次：history.size()=11 > 10
        boolean looped = detector.detectLoop(sessionId, "answer10");
        assertThat(looped).as("超过 maxRounds 应触发循环").isTrue();
        assertThat(detector.getViolation(sessionId)).contains("最大轮次");
    }
}
