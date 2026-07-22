package com.charles.interview.arena.agent.planning.harness;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.planning.model.PlanningAction;
import com.charles.interview.arena.agent.planning.model.PlanningDecision;
import com.charles.interview.arena.agent.runtime.state.InterviewAgentState;

/**
 * 规划恢复器(恢复/回退策略)
 * <p>
 * 职责:当规划失败或 LLM 决策被硬约束拒绝时,提供降级方案。
 * <p>
 * 恢复场景:
 * 1. 题库没有搜索到题目 -> 降低难度/改用默认题/结束面试
 * 2. LLM 返回非法动作 -> 降级为安全动作
 * 3. LLM 追问超限但仍选 DEEP_DIVE -> 强制换题
 * 4. 题库已抽完但仍选 NEXT_QUESTION -> 结束面试
 */
@Component
public class PlanningRecovery {

    private static final Logger log = LoggerFactory.getLogger(PlanningRecovery.class);

    /**
     * 题库没有搜索到题目 -> 恢复
     * <p>
     * 恢复策略:
     * 1. 降低难度重新搜索(第一版简化:直接结束)
     * 2. 改用默认题目
     * 3. 都没有 -> 结束面试
     *
     * @param state 当前状态
     * @return 恢复决策(结束面试)
     */
    public PlanningDecision recoverNoQuestion(InterviewAgentState state) {
        log.warn("题库已抽完,恢复策略:结束面试 | sessionId={}",
                state != null ? state.sessionId() : null);
        return PlanningDecision.fromRecovery(
                PlanningAction.END_INTERVIEW,
                "题库已抽完,无法继续出题");
    }

    /**
     * LLM 返回的动作被硬约束拒绝 -> 恢复
     * <p>
     * 降级策略:
     * - DEEP_DIVE 被拒(追问超限) -> 强制 PROPOSE_NEXT_QUESTION
     * - PROPOSE_NEXT_QUESTION 被拒(题库空) -> 强制 END_INTERVIEW
     * - SWITCH_TOPIC 被拒(题库空) -> 强制 END_INTERVIEW
     * - 其他 -> 强制 END_INTERVIEW(最安全)
     *
     * @param rejectedAction 被 LLM 选择但被约束拒绝的动作
     * @param state          当前状态
     * @return 恢复决策
     */
    public PlanningDecision recoverConstrainedAction(
            PlanningAction rejectedAction, InterviewAgentState state) {

        log.warn("动作被硬约束拒绝,执行恢复: rejectedAction={}, sessionId={}",
                rejectedAction,
                state != null ? state.sessionId() : null);

        return switch (rejectedAction) {
            case DEEP_DIVE -> PlanningDecision.fromRecovery(
                    PlanningAction.PROPOSE_NEXT_QUESTION,
                    "追问超限,强制换题");

            case PROPOSE_NEXT_QUESTION, SWITCH_TOPIC -> PlanningDecision.fromRecovery(
                    PlanningAction.END_INTERVIEW,
                    "题库已抽完,结束面试");

            default -> PlanningDecision.fromRecovery(
                    PlanningAction.END_INTERVIEW,
                    "动作被拒,安全降级为结束面试");
        };
    }

    /**
     * LLM 返回未知/非法动作 -> 恢复
     *
     * @param invalidActionStr LLM 返回的原始动作字符串
     * @param state            当前状态
     * @return 恢复决策
     */
    public PlanningDecision recoverInvalidAction(
            String invalidActionStr, InterviewAgentState state) {

        log.warn("LLM 返回非法动作,执行恢复: invalidAction={}, sessionId={}",
                invalidActionStr,
                state != null ? state.sessionId() : null);

        // 降级为最安全的动作:如果还能追问就继续,否则结束
        if (state != null && state.followUpCount() < 3) {
            return PlanningDecision.fromRecovery(
                    PlanningAction.DEEP_DIVE,
                    "非法动作降级为继续追问");
        }
        return PlanningDecision.fromRecovery(
                PlanningAction.END_INTERVIEW,
                "非法动作降级为结束面试");
    }
}
