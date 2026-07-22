package com.charles.interview.arena.agent.planning;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.charles.interview.arena.agent.planning.harness.PlanningConstraints;
import com.charles.interview.arena.agent.planning.harness.PlanningRecovery;
import com.charles.interview.arena.agent.planning.model.PlanningAction;
import com.charles.interview.arena.agent.planning.model.PlanningDecision;
import com.charles.interview.arena.agent.runtime.state.InterviewAgentState;

/**
 * 规划服务(规划层主线)
 * <p>
 * 职责:根据当前状态 + 用户输入 + 记忆,决定下一步做什么。
 * <p>
 * 核心原则:
 * - 不使用小 LLM 做决策(成本高)
 * - 使用 Prompt 软约束(把可用动作注入 Prompt,指导主 LLM 决策)
 * - 使用代码硬约束(验证 LLM 返回的动作是否合法)
 * - 恢复策略(动作被拒绝时降级)
 * <p>
 * 调用关系:
 * - 编排层调 PlanningService.getAvailableActions() -> 注入 Prompt
 * - 编排层调 PlanningService.validateAction() -> 验证 LLM 决策
 * - 规划失败调 PlanningService.recoverXxx() -> 恢复
 * <p>
 * 不负责:实际执行(调工具/调LLM/写记忆),那是编排层的职责。
 */
@Service
public class PlanningService {

    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);

    private final PlanningConstraints constraints;
    private final PlanningRecovery recovery;

    public PlanningService(PlanningConstraints constraints, PlanningRecovery recovery) {
        this.constraints = constraints;
        this.recovery = recovery;
    }

    /**
     * 获取当前可用动作列表(给编排层注入 Prompt 软约束)
     *
     * @param state 当前面试状态
     * @return 可用动作列表
     */
    public List<PlanningAction> getAvailableActions(InterviewAgentState state) {
        List<PlanningAction> actions = constraints.getAvailableActions(state);
        log.info("可用动作: {} | sessionId={}",
                actions,
                state != null ? state.sessionId() : null);
        return actions;
    }

    /**
     * 渲染可用动作为 Prompt 文本(软约束)
     * <p>
     * 编排层把此文本拼入 System Prompt,指导 LLM 在约束内决策。
     *
     * @param state 当前面试状态
     * @return Prompt 文本
     */
    public String renderActionsForPrompt(InterviewAgentState state) {
        return constraints.renderActionsForPrompt(state);
    }

    /**
     * 验证 LLM 返回的动作是否合法(硬约束)
     * <p>
     * 编排层在 LLM 返回决策后调用此方法:
     * - 合法 -> 返回 LLM 决策
     * - 非法 -> 返回恢复决策(降级)
     *
     * @param action LLM 返回的动作
     * @param state  当前状态
     * @return 最终决策(合法则用 LLM 的,非法则降级)
     */
    public PlanningDecision validateAction(PlanningAction action, InterviewAgentState state) {
        if (constraints.isActionAllowed(action, state)) {
            log.info("动作合法: {} | sessionId={}", action,
                    state != null ? state.sessionId() : null);
            return PlanningDecision.fromLlm(action, "LLM 决策合法");
        }

        // 硬约束拒绝 -> 恢复
        log.warn("动作被硬约束拒绝: {} | sessionId={}", action,
                state != null ? state.sessionId() : null);
        return recovery.recoverConstrainedAction(action, state);
    }

    /**
     * 验证 LLM 返回的动作字符串(从 ReAct final_answer 解析)
     *
     * @param actionStr LLM 返回的动作字符串(如 "DEEP_DIVE")
     * @param state     当前状态
     * @return 最终决策
     */
    public PlanningDecision validateActionString(String actionStr, InterviewAgentState state) {
        PlanningAction action = PlanningAction.fromString(actionStr);
        return validateAction(action, state);
    }

    /**
     * 题库搜索空 -> 恢复
     */
    public PlanningDecision recoverNoQuestion(InterviewAgentState state) {
        return recovery.recoverNoQuestion(state);
    }

    /**
     * LLM 返回非法动作 -> 恢复
     */
    public PlanningDecision recoverInvalidAction(String invalidActionStr, InterviewAgentState state) {
        return recovery.recoverInvalidAction(invalidActionStr, state);
    }

    /**
     * 获取约束配置信息(用于日志/调试)
     */
    public String getConstraintsInfo() {
        return String.format("maxQuestions=%d, maxFollowUpRounds=%d, minRounds=%d",
                constraints.getMaxQuestions(),
                constraints.getMaxFollowUpRounds(),
                constraints.getMinRounds());
    }
}
