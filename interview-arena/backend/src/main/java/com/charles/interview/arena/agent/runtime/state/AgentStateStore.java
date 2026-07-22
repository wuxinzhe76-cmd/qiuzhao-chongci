package com.charles.interview.arena.agent.runtime.state;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Agent 状态存储(Redis)
 * <p>
 * 职责:读写面试 Agent 的确定性状态(题目/轮次/已用集)。
 * 独立于 Memory,是流程控制的权威依据。
 * <p>
 * 被多层共享:
 * - planning 层:GoalTracker 读轮次判断漂移
 * - memory 层:MemoryFacade 读当前题目记录归属
 * - orchestration 层:Orchestrator 读写所有状态
 * <p>
 * 存储:Redis(会话级,TTL 2h),面试结束清理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentStateStore {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${interview.redis-ttl:2h}")
    private Duration redisTtl;

    // ==================== 状态读取(聚合) ====================

    /**
     * 加载完整状态(聚合多个 Redis key)
     */
    public InterviewAgentState load(Long sessionId) {
        if (sessionId == null) return null;

        Long questionId = getCurrentQuestion(sessionId);
        long round = getRound(sessionId);
        long questionRound = getQuestionRound(sessionId);
        Set<Long> usedIds = getUsedQuestionIds(sessionId);

        return new InterviewAgentState(
                sessionId,
                InterviewStage.QUESTIONING, // 简化:有状态说明在面试中
                questionId != null ? questionId.intValue() : 0,
                (int) questionRound,
                usedIds,
                round // version 复用 round 作为乐观锁
        );
    }

    // ==================== 当前题目 ====================

    public void setCurrentQuestion(Long sessionId, Long questionId) {
        stringRedisTemplate.opsForValue().set(
                InterviewRedisConstants.questionKey(sessionId),
                String.valueOf(questionId),
                redisTtl);
    }

    public Long getCurrentQuestion(Long sessionId) {
        String val = stringRedisTemplate.opsForValue()
                .get(InterviewRedisConstants.questionKey(sessionId));
        return val != null ? Long.parseLong(val) : null;
    }

    // ==================== 总轮次 ====================

    public long incrementRound(Long sessionId) {
        Long val = stringRedisTemplate.opsForValue()
                .increment(InterviewRedisConstants.roundKey(sessionId));
        stringRedisTemplate.expire(InterviewRedisConstants.roundKey(sessionId), redisTtl);
        return val != null ? val : 0L;
    }

    public long getRound(Long sessionId) {
        String val = stringRedisTemplate.opsForValue()
                .get(InterviewRedisConstants.roundKey(sessionId));
        return val != null ? Long.parseLong(val) : 0L;
    }

    // ==================== 当前题目追问轮次 ====================

    public long incrementQuestionRound(Long sessionId) {
        Long val = stringRedisTemplate.opsForValue()
                .increment(InterviewRedisConstants.questionRoundKey(sessionId));
        stringRedisTemplate.expire(InterviewRedisConstants.questionRoundKey(sessionId), redisTtl);
        return val != null ? val : 0L;
    }

    public long getQuestionRound(Long sessionId) {
        String val = stringRedisTemplate.opsForValue()
                .get(InterviewRedisConstants.questionRoundKey(sessionId));
        return val != null ? Long.parseLong(val) : 0L;
    }

    public void resetQuestionRound(Long sessionId) {
        stringRedisTemplate.opsForValue().set(
                InterviewRedisConstants.questionRoundKey(sessionId),
                "0",
                redisTtl);
    }

    // ==================== 已使用题目集 ====================

    public void addUsedQuestion(Long sessionId, Long questionId) {
        stringRedisTemplate.opsForSet().add(
                InterviewRedisConstants.usedKey(sessionId),
                String.valueOf(questionId));
        stringRedisTemplate.expire(InterviewRedisConstants.usedKey(sessionId), redisTtl);
    }

    public Set<String> getUsedQuestions(Long sessionId) {
        return stringRedisTemplate.opsForSet()
                .members(InterviewRedisConstants.usedKey(sessionId));
    }

    public Set<Long> getUsedQuestionIds(Long sessionId) {
        Set<String> strIds = getUsedQuestions(sessionId);
        if (strIds == null || strIds.isEmpty()) return new HashSet<>();
        return strIds.stream().map(Long::parseLong).collect(Collectors.toSet());
    }

    // ==================== 清理 ====================

    public void clear(Long sessionId) {
        stringRedisTemplate.delete(InterviewRedisConstants.questionKey(sessionId));
        stringRedisTemplate.delete(InterviewRedisConstants.roundKey(sessionId));
        stringRedisTemplate.delete(InterviewRedisConstants.questionRoundKey(sessionId));
        stringRedisTemplate.delete(InterviewRedisConstants.usedKey(sessionId));
        log.info("Agent 状态已清理: sessionId={}", sessionId);
    }
}
