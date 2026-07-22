package com.charles.interview.arena.agent.memory.working;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.charles.interview.arena.agent.runtime.state.InterviewRedisConstants;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 工作记忆服务（蓝图 §5.4.5 三层记忆模型）
 * <p>
 * 存储：Redis（会话级，TTL 2h）
 * 存什么：当前对话上下文、当前题目、轮次、已用题目集
 * <p>
 * 八股映射：
 * - 借鉴 Hello-Agents 第八章 WorkingMemory（纯内存 + TTL）
 * - 封装现有面试模块的 Redis 操作 + 容量管理（50 条 FIFO）
 * <p>
 * 遗忘策略（蓝图 §5.4.5）：
 * - TTL 过期：Redis 工作记忆 TTL 2h
 * - 容量限制：对话历史超过 50 条时，FIFO 淘汰最早消息
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkingMemoryService {

    private final StringRedisTemplate stringRedisTemplate;

    /** 滑动窗口大小（最近 N 条对话给 AI 看上下文） */
    @Value("${interview.history-window-size:10}")
    private int historyWindowSize;

    /** 容量上限（蓝图 §5.4.5：50 条 FIFO） */
    private static final int CAPACITY_LIMIT = 50;

    /** Redis TTL（蓝图 §5.4.5：2h） */
    @Value("${interview.redis-ttl:2h}")
    private Duration redisTtl;

    /** ===== 对话历史（List） ===== **/

    public void pushHistory(Long sessionId, String message) {
        String key = InterviewRedisConstants.historyKey(sessionId);
        stringRedisTemplate.opsForList().rightPush(key, message);
        stringRedisTemplate.expire(key, redisTtl);
        // 容量限制：超 50 条 FIFO 淘汰（砍最早，已经落库可安心丢）
        Long size = stringRedisTemplate.opsForList().size(key);
        if (size != null && size > CAPACITY_LIMIT) {
            stringRedisTemplate.opsForList().trim(key, size - CAPACITY_LIMIT, -1);
        }
    }

    public List<String> getHistory(Long sessionId) {
        String key = InterviewRedisConstants.historyKey(sessionId);
        List<String> range = stringRedisTemplate.opsForList().range(key, 0, -1);
        return range != null ? range : new ArrayList<>();
    }

    /**
     * 取最近 N 条对话（滑动窗口）
     */
    public List<String> getRecentHistory(Long sessionId) {
        List<String> all = getHistory(sessionId);
        if (all.size() <= historyWindowSize) {
            return all;
        }
        return all.subList(all.size() - historyWindowSize, all.size());
    }

    /** ===== 当前题目（String） ===== **/

    public void setCurrentQuestion(Long sessionId, Long questionId) {
        stringRedisTemplate.opsForValue().set(
                InterviewRedisConstants.questionKey(sessionId),
                String.valueOf(questionId),
                redisTtl);
    }

    public Long getCurrentQuestion(Long sessionId) {
        String val = stringRedisTemplate.opsForValue().get(InterviewRedisConstants.questionKey(sessionId));
        return val != null ? Long.parseLong(val) : null;
    }

    /** ===== 总轮次（String） ===== **/

    public long incrementRound(Long sessionId) {
        Long val = stringRedisTemplate.opsForValue().increment(InterviewRedisConstants.roundKey(sessionId));
        stringRedisTemplate.expire(InterviewRedisConstants.roundKey(sessionId), redisTtl);
        return val != null ? val : 0L;
    }

    public long getRound(Long sessionId) {
        String val = stringRedisTemplate.opsForValue().get(InterviewRedisConstants.roundKey(sessionId));
        return val != null ? Long.parseLong(val) : 0L;
    }

    /** ===== 当前题目已追问轮次（String） ===== **/

    public long incrementQuestionRound(Long sessionId) {
        Long val = stringRedisTemplate.opsForValue().increment(InterviewRedisConstants.questionRoundKey(sessionId));
        stringRedisTemplate.expire(InterviewRedisConstants.questionRoundKey(sessionId), redisTtl);
        return val != null ? val : 0L;
    }

    public long getQuestionRound(Long sessionId) {
        String val = stringRedisTemplate.opsForValue().get(InterviewRedisConstants.questionRoundKey(sessionId));
        return val != null ? Long.parseLong(val) : 0L;
    }

    public void resetQuestionRound(Long sessionId) {
        stringRedisTemplate.opsForValue().set(
                InterviewRedisConstants.questionRoundKey(sessionId),
                "0",
                redisTtl);
    }

    /** ===== 已使用题目集（Set） ===== **/

    public void addUsedQuestion(Long sessionId, Long questionId) {
        stringRedisTemplate.opsForSet().add(
                InterviewRedisConstants.usedKey(sessionId),
                String.valueOf(questionId));
        stringRedisTemplate.expire(InterviewRedisConstants.usedKey(sessionId), redisTtl);
    }

    public Set<String> getUsedQuestions(Long sessionId) {
        return stringRedisTemplate.opsForSet().members(InterviewRedisConstants.usedKey(sessionId));
    }

    /** ===== 遗忘策略：会话结束立即清理 ===== **/

    public void clearAll(Long sessionId) {
        stringRedisTemplate.delete(InterviewRedisConstants.historyKey(sessionId));
        stringRedisTemplate.delete(InterviewRedisConstants.questionKey(sessionId));
        stringRedisTemplate.delete(InterviewRedisConstants.roundKey(sessionId));
        stringRedisTemplate.delete(InterviewRedisConstants.questionRoundKey(sessionId));
        stringRedisTemplate.delete(InterviewRedisConstants.usedKey(sessionId));
        log.info("工作记忆已清理：sessionId={}", sessionId);
    }
}
