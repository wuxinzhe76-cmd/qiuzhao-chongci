package com.charles.interview.arena.agent.orchestration.harness;

import com.charles.interview.arena.model.enums.ActionDirectiveEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThreeLayerController 单元测试
 * <p>
 * 验证三层控制器的两道代码兜底机制：
 * - 兜底 1：单题追问超过 maxQuestionRounds 时，强制 NEXT_QUESTION（覆盖 AI 的 DEEP_DIVE）
 * - 兜底 2：总轮次 >= maxRounds 时，强制 END_INTERVIEW（覆盖一切 AI 指令）
 * - 正常范围内 AI 指令被尊重
 * <p>
 * ThreeLayerController 使用 @Value 注入字段，纯单元测试中通过 ReflectionTestUtils 设置。
 */
@DisplayName("ThreeLayerController 三层控制器")
class ThreeLayerControllerTest {

    private ThreeLayerController controller;

    @BeforeEach
    void setUp() {
        controller = new ThreeLayerController();
        // 模拟 @Value 注入（与 application.yml 默认值一致）
        ReflectionTestUtils.setField(controller, "maxRounds", 10);
        ReflectionTestUtils.setField(controller, "maxQuestionRounds", 3);
    }

    // ==================== 兜底 1：单题追问超限 ====================

    @Nested
    @DisplayName("代码兜底 1：单题追问 > maxQuestionRounds")
    class Fallback1Test {

        @Test
        @DisplayName("AI 想追问(DEEP_DIVE)但单题已 4 轮 > 3 上限 -> 强制 NEXT_QUESTION")
        void deepDiveExceedingQuestionRoundsForcedToNextQuestion() {
            // questionRound=4 > maxQuestionRounds=3，AI 说 DEEP_DIVE
            ActionDirectiveEnum result = controller.applyControl(
                    "DEEP_DIVE", 4L, 5L);

            assertThat(result)
                    .as("单题超限时 DEEP_DIVE 应被强制覆盖为 NEXT_QUESTION")
                    .isEqualTo(ActionDirectiveEnum.NEXT_QUESTION);
        }

        @Test
        @DisplayName("单题恰好 3 轮(不超限)时 AI 的 DEEP_DIVE 被尊重")
        void deepDiveAtExactLimitRespected() {
            // questionRound=3，不大于 maxQuestionRounds=3，不触发兜底 1
            ActionDirectiveEnum result = controller.applyControl(
                    "DEEP_DIVE", 3L, 5L);

            assertThat(result)
                    .as("单题 3 轮未超限，DEEP_DIVE 应被尊重")
                    .isEqualTo(ActionDirectiveEnum.DEEP_DIVE);
        }

        @Test
        @DisplayName("单题超限但 AI 已决定 NEXT_QUESTION -> 保持 NEXT_QUESTION")
        void nextQuestionNotAffectedByFallback1() {
            // questionRound=4 > 3，但 AI 已经说 NEXT_QUESTION，兜底 1 不改变结果
            ActionDirectiveEnum result = controller.applyControl(
                    "NEXT_QUESTION", 4L, 5L);

            assertThat(result)
                    .isEqualTo(ActionDirectiveEnum.NEXT_QUESTION);
        }
    }

    // ==================== 兜底 2：总轮次达上限 ====================

    @Nested
    @DisplayName("代码兜底 2：总轮次 >= maxRounds")
    class Fallback2Test {

        @Test
        @DisplayName("总轮次恰好 10 = maxRounds -> 强制 END_INTERVIEW（即使 AI 想 DEEP_DIVE）")
        void totalRoundAtMaxForcesEndInterview() {
            ActionDirectiveEnum result = controller.applyControl(
                    "DEEP_DIVE", 1L, 10L);

            assertThat(result)
                    .as("总轮次达到上限应强制 END_INTERVIEW")
                    .isEqualTo(ActionDirectiveEnum.END_INTERVIEW);
        }

        @Test
        @DisplayName("总轮次 15 > maxRounds -> 强制 END_INTERVIEW（即使 AI 想 NEXT_QUESTION）")
        void totalRoundExceedsMaxForcesEndInterview() {
            ActionDirectiveEnum result = controller.applyControl(
                    "NEXT_QUESTION", 2L, 15L);

            assertThat(result)
                    .as("总轮次超过上限应强制 END_INTERVIEW，覆盖 NEXT_QUESTION")
                    .isEqualTo(ActionDirectiveEnum.END_INTERVIEW);
        }

        @Test
        @DisplayName("总轮次 9 < maxRounds -> 不触发兜底 2")
        void totalRoundBelowMaxNoFallback2() {
            ActionDirectiveEnum result = controller.applyControl(
                    "DEEP_DIVE", 1L, 9L);

            assertThat(result)
                    .as("总轮次未达上限，AI 指令应被尊重")
                    .isEqualTo(ActionDirectiveEnum.DEEP_DIVE);
        }

        @Test
        @DisplayName("兜底 2 优先级高于兜底 1：单题超限 + 总轮次达限 -> END_INTERVIEW")
        void fallback2OverridesFallback1() {
            // questionRound=5 > 3 (触发兜底 1 -> NEXT_QUESTION)
            // totalRound=10 >= 10 (触发兜底 2 -> END_INTERVIEW)
            // 兜底 2 在兜底 1 之后执行，最终结果应为 END_INTERVIEW
            ActionDirectiveEnum result = controller.applyControl(
                    "DEEP_DIVE", 5L, 10L);

            assertThat(result)
                    .as("兜底 2 应覆盖兜底 1，最终强制 END_INTERVIEW")
                    .isEqualTo(ActionDirectiveEnum.END_INTERVIEW);
        }
    }

    // ==================== 正常范围：AI 指令被尊重 ====================

    @Nested
    @DisplayName("正常范围内 AI 指令被尊重")
    class NormalRangeTest {

        @Test
        @DisplayName("正常范围 AI 指令 DEEP_DIVE 被尊重")
        void deepDiveRespectedInNormalRange() {
            ActionDirectiveEnum result = controller.applyControl(
                    "DEEP_DIVE", 1L, 1L);

            assertThat(result).isEqualTo(ActionDirectiveEnum.DEEP_DIVE);
        }

        @Test
        @DisplayName("正常范围 AI 指令 NEXT_QUESTION 被尊重")
        void nextQuestionRespectedInNormalRange() {
            ActionDirectiveEnum result = controller.applyControl(
                    "NEXT_QUESTION", 2L, 3L);

            assertThat(result).isEqualTo(ActionDirectiveEnum.NEXT_QUESTION);
        }

        @Test
        @DisplayName("正常范围 AI 指令 END_INTERVIEW 被尊重")
        void endInterviewRespectedInNormalRange() {
            ActionDirectiveEnum result = controller.applyControl(
                    "END_INTERVIEW", 1L, 5L);

            assertThat(result).isEqualTo(ActionDirectiveEnum.END_INTERVIEW);
        }

        @Test
        @DisplayName("AI 返回 null 指令时默认为 DEEP_DIVE（fromValue 兜底）")
        void nullDirectiveDefaultsToDeepDive() {
            ActionDirectiveEnum result = controller.applyControl(
                    null, 1L, 1L);

            assertThat(result).isEqualTo(ActionDirectiveEnum.DEEP_DIVE);
        }

        @Test
        @DisplayName("AI 返回无效指令时默认为 DEEP_DIVE（fromValue 兜底）")
        void invalidDirectiveDefaultsToDeepDive() {
            ActionDirectiveEnum result = controller.applyControl(
                    "INVALID_DIRECTIVE", 1L, 1L);

            assertThat(result).isEqualTo(ActionDirectiveEnum.DEEP_DIVE);
        }
    }

    // ==================== 自定义配置值 ====================

    @Nested
    @DisplayName("自定义配置值下的兜底行为")
    class CustomConfigTest {

        @Test
        @DisplayName("自定义 maxQuestionRounds=5 时，第 6 轮才触发兜底 1")
        void customMaxQuestionRounds() {
            ReflectionTestUtils.setField(controller, "maxQuestionRounds", 5);

            // questionRound=5，不大于 5，不触发
            ActionDirectiveEnum ok = controller.applyControl(
                    "DEEP_DIVE", 5L, 3L);
            assertThat(ok).isEqualTo(ActionDirectiveEnum.DEEP_DIVE);

            // questionRound=6 > 5，触发兜底 1
            ActionDirectiveEnum forced = controller.applyControl(
                    "DEEP_DIVE", 6L, 3L);
            assertThat(forced).isEqualTo(ActionDirectiveEnum.NEXT_QUESTION);
        }

        @Test
        @DisplayName("自定义 maxRounds=20 时，总轮次 10 不触发兜底 2")
        void customMaxRounds() {
            ReflectionTestUtils.setField(controller, "maxRounds", 20);

            ActionDirectiveEnum result = controller.applyControl(
                    "DEEP_DIVE", 1L, 10L);

            assertThat(result)
                    .as("maxRounds=20 时总轮次 10 不应触发兜底 2")
                    .isEqualTo(ActionDirectiveEnum.DEEP_DIVE);
        }
    }
}
