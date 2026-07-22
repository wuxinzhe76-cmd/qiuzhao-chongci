package com.charles.interview.arena.agent.planning.harness;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.planning.model.PlanningAction;
import com.charles.interview.arena.agent.runtime.state.InterviewAgentState;
import com.charles.interview.arena.agent.runtime.state.InterviewStage;

/**
 * 规划约束器(硬约束)
 * <p>
 * 职责:用代码强制约束面试流程,LLM 无法跳过。
 * <p>
 * 硬约束项:
 * - 当前阶段是否允许出题(已用题目数 < 最大题数)
 * - 是否超过最大追问次数(当前题追问数 < 上限)
 * - 是否允许结束面试(至少面试了 N 轮)
 * - 是否允许开始面试(状态为 CREATED)
 * <p>
 * 软约束:把可用动作渲染为 Prompt 文本,指导 LLM 在约束内决策。
 */
@Component
public class PlanningConstraints {

    /** 最大题目数(默认 10) */
    @Value("${interview.max-questions:10}")
    private int maxQuestions;

    /** 单题最大追问轮次(默认 3) */
    @Value("${interview.max-question-rounds:3}")
    private int maxFollowUpRounds;

    /** 最小面试轮次(允许结束的最小轮次,默认 1) */
    @Value("${interview.min-rounds:1}")
    private int minRounds;

    /**
     * 检查动作是否被硬约束允许
     *
     * @param action 待检查的动作
     * @param state  当前面试状态
     * @return true 表示允许
     */
    public boolean isActionAllowed(PlanningAction action, InterviewAgentState state) {
        if (action == null) return false;

        return switch (action) {
            case START_INTERVIEW -> state == null
                    || state.stage() == InterviewStage.CREATED
                    || state.stage() == InterviewStage.ENDED;

            case PROPOSE_NEXT_QUESTION -> canAskNewQuestion(state);

            case DEEP_DIVE -> canContinueDeepDive(state);

            case LOWER_DIFFICULTY, RAISE_DIFFICULTY -> true; // 总是允许调难度

            case SWITCH_TOPIC -> canAskNewQuestion(state); // 切知识点需要能出新题

            case END_INTERVIEW -> canEndInterview(state);
        };
    }

    /**
     * 获取当前可用动作列表(注入 Prompt 软约束)
     *
     * @param state 当前面试状态
     * @return 可用动作列表
     */
    public List<PlanningAction> getAvailableActions(InterviewAgentState state) {
        List<PlanningAction> actions = new ArrayList<>();

        if (canAskNewQuestion(state)) {
            actions.add(PlanningAction.PROPOSE_NEXT_QUESTION);
            actions.add(PlanningAction.SWITCH_TOPIC);
        }
        if (canContinueDeepDive(state)) {
            actions.add(PlanningAction.DEEP_DIVE);
        }
        // 调难度总是可用
        actions.add(PlanningAction.LOWER_DIFFICULTY);
        actions.add(PlanningAction.RAISE_DIFFICULTY);

        if (canEndInterview(state)) {
            actions.add(PlanningAction.END_INTERVIEW);
        }

        return actions;
    }

    /**
     * 把可用动作渲染为 Prompt 文本(软约束)
     *
     * @param state 当前面试状态
     * @return Prompt 文本,如:
     *   "你可以选择以下动作:
     *    - DEEP_DIVE: 继续追问
     *    - PROPOSE_NEXT_QUESTION: 提出下一题
     *    - END_INTERVIEW: 结束面试"
     */
    public String renderActionsForPrompt(InterviewAgentState state) {
        List<PlanningAction> actions = getAvailableActions(state);
        StringBuilder sb = new StringBuilder("你可以选择以下动作:\n");
        for (PlanningAction action : actions) {
            sb.append("- ").append(action.name())
              .append(": ").append(action.getDescription())
              .append("\n");
        }
        return sb.toString();
    }

    // ==================== 内部约束检查 ====================

    /**
     * 是否还能出新题(已用题目数 < 最大题数)
     */
    private boolean canAskNewQuestion(InterviewAgentState state) {
        if (state == null) return false;
        int usedCount = state.usedQuestionIds() != null ? state.usedQuestionIds().size() : 0;
        return usedCount < maxQuestions;
    }

    /**
     * 是否还能继续追问(当前题追问数 < 上限)
     */
    private boolean canContinueDeepDive(InterviewAgentState state) {
        if (state == null) return false;
        return state.followUpCount() < maxFollowUpRounds;
    }

    /**
     * 是否允许结束面试(至少面试了 minRounds 轮)
     */
    private boolean canEndInterview(InterviewAgentState state) {
        if (state == null) return true;
        // 至少面试了 1 题
        int usedCount = state.usedQuestionIds() != null ? state.usedQuestionIds().size() : 0;
        return usedCount >= minRounds;
    }

    // ==================== Getter ====================

    public int getMaxQuestions() { return maxQuestions; }
    public int getMaxFollowUpRounds() { return maxFollowUpRounds; }
    public int getMinRounds() { return minRounds; }
}
