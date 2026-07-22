package com.charles.interview.arena.agent.orchestration.harness;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.model.enums.ActionDirectiveEnum;

import lombok.extern.slf4j.Slf4j;

/**
 * 三层控制器（AI 主导 + 代码兜底 + 用户主动）
 * <p>
 * 蓝图 §5.4 核心机制：AI 返回 action_directive，但代码做两道兜底，
 * 防止 AI 判断失误导致面试失控。
 * <p>
 * <pre>
 * AI 返回 action_directive
 *        │
 *  ┌─────▼─────┐
 *  │ 代码兜底 1 │  单题追问 > 3 轮？
 *  │ 强制换题   │  是 -> NEXT_QUESTION（覆盖 AI 指令）
 *  └─────┬─────┘  否 -> 保持 AI 指令
 *        │
 *  ┌─────▼─────┐
 *  │ 代码兜底 2 │  总轮次 >= 10？
 *  │ 强制结束   │  是 -> END_INTERVIEW（覆盖 AI 指令）
 *  └─────┬─────┘  否 -> 保持 AI 指令
 *        │
 *  ┌─────▼─────┐
 *  │ 最终路由   │  DEEP_DIVE / NEXT_QUESTION / END_INTERVIEW
 *  └───────────┘
 * </pre>
 */
@Slf4j
@Component
public class ThreeLayerController {

    @Value("${interview.max-rounds:10}")
    private int maxRounds;

    @Value("${interview.max-question-rounds:3}")
    private int maxQuestionRounds;

    /**
     * 三层控制路由
     *
     * @param aiDirective   AI 返回的动作指令（DEEP_DIVE / NEXT_QUESTION / END_INTERVIEW）
     * @param questionRound 当前题目已追问轮次
     * @param totalRound    总轮次
     * @return 最终执行的动作指令
     */
    public ActionDirectiveEnum applyControl(String aiDirective, long questionRound, long totalRound) {
        ActionDirectiveEnum directive = ActionDirectiveEnum.fromValue(aiDirective);

        // 代码兜底 1：单题超过 3 轮，强制换题（防止 AI 在一道题上无限追问）
        if (questionRound > maxQuestionRounds && directive == ActionDirectiveEnum.DEEP_DIVE) {
            log.info("代码兜底 1：单题 {} 轮 > {} 轮上限，强制 NEXT_QUESTION", questionRound, maxQuestionRounds);
            directive = ActionDirectiveEnum.NEXT_QUESTION;
        }

        // 代码兜底 2：总轮次达到上限，强制结束（防止 AI 永远不结束）
        if (totalRound >= maxRounds) {
            log.info("代码兜底 2：总轮次 {} >= {} 上限，强制 END_INTERVIEW", totalRound, maxRounds);
            directive = ActionDirectiveEnum.END_INTERVIEW;
        }

        return directive;
    }
}
