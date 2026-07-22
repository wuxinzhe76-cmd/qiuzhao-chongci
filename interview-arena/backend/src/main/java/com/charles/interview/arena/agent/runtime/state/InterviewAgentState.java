package com.charles.interview.arena.agent.runtime.state;

import java.util.Set;

/**
 * 面试 Agent 状态(含乐观锁)
 * 独立于 Memory,是流程控制的权威依据。
 */
public record InterviewAgentState(
        Long sessionId,
        InterviewStage stage,
        int questionIndex,
        int followUpCount,
        Set<Long> usedQuestionIds,
        long version  // 乐观锁
) {}
