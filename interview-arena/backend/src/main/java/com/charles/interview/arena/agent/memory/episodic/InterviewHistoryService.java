package com.charles.interview.arena.agent.memory.episodic;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.charles.interview.arena.mapper.InterviewRecordMapper;
import com.charles.interview.arena.mapper.InterviewSessionMapper;
import com.charles.interview.arena.model.entity.InterviewRecord;
import com.charles.interview.arena.model.entity.InterviewSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 情景记忆服务（蓝图 §5.4.5 三层记忆模型）
 * <p>
 * 存储：MySQL（永久，interview_session + interview_record）
 * 存什么：历次面试的完整问答明细、评分、时间序列
 * <p>
 * 八股映射：
 * - 借鉴 Hello-Agents 第八章 EpisodicMemory（SQLite + Qdrant）
 * - 时间衰减评分：retrieval_score = relevance × time_decay × importance_weight
 *   - time_decay: max(0.3, 1 - days_elapsed / 30)  // 30天衰减到0.3底线
 *   - importance_weight: mastery < 40 ? 2.0 : (mastery < 60 ? 1.5 : 1.0)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewHistoryService {

    private final InterviewSessionMapper sessionMapper;
    private final InterviewRecordMapper recordMapper;

    /** 时间衰减天数底线（蓝图 §5.4.5：30 天） */
    private static final int DECAY_DAYS = 30;

    /** 时间衰减底线（蓝图 §5.4.5：0.3） */
    private static final double DECAY_FLOOR = 0.3;

    /**
     * 情景记忆写入：每轮对话即时落库（interview_record 的唯一写入口）
     * <p>
     * 原 SaveRecordTool 的职责收口到 Memory 层：写记忆是记忆子系统的事，不是模型可选的工具。
     */
    public void saveRecord(Long sessionId, Long questionId, String role, String content, int roundNum) {
        InterviewRecord record = new InterviewRecord();
        record.setSessionId(sessionId);
        record.setQuestionId(questionId);
        record.setRole(role);
        record.setContent(content);
        record.setRoundNum(roundNum);
        recordMapper.insert(record);
        log.info("情景记忆落库: sessionId={}, role={}, roundNum={}", sessionId, role, roundNum);
    }

    /**
     * 检索用户历次面试会话
     *
     * @param userId 用户 ID
     * @param limit  最多返回多少条
     */
    public List<InterviewSession> getUserSessions(Long userId, int limit) {
        LambdaQueryWrapper<InterviewSession> wrapper = new LambdaQueryWrapper<InterviewSession>()
                .eq(InterviewSession::getUserId, userId)
                .eq(InterviewSession::getStatus, 1)  // 只查已结束的面试
                .orderByDesc(InterviewSession::getCreateTime)
                .last("LIMIT " + limit);
        return sessionMapper.selectList(wrapper);
    }

    /**
     * 取某次面试的所有问答明细
     */
    public List<InterviewRecord> getSessionRecords(Long sessionId) {
        LambdaQueryWrapper<InterviewRecord> wrapper = new LambdaQueryWrapper<InterviewRecord>()
                .eq(InterviewRecord::getSessionId, sessionId)
                .orderByAsc(InterviewRecord::getRoundNum);
        return recordMapper.selectList(wrapper);
    }

    /**
     * 计算时间衰减权重（蓝图 §5.4.5）
     * <p>
     * max(0.3, 1 - days_elapsed / 30)
     */
    public double timeDecay(LocalDateTime recordTime) {
        if (recordTime == null) {
            return DECAY_FLOOR;
        }
        long days = ChronoUnit.DAYS.between(recordTime, LocalDateTime.now());
        double decay = 1.0 - (double) days / DECAY_DAYS;
        return Math.max(DECAY_FLOOR, decay);
    }

    /**
     * 计算重要性权重（蓝图 §5.4.5）
     * <p>
     * mastery < 40 ? 2.0 : (mastery < 60 ? 1.5 : 1.0)
     */
    public double importanceWeight(Integer mastery) {
        if (mastery == null) return 1.0;
        if (mastery < 40) return 2.0;
        if (mastery < 60) return 1.5;
        return 1.0;
    }

    /**
     * 综合评分：retrieval_score = relevance × time_decay × importance_weight
     * <p>
     * 此处简化：relevance 用 1.0 占位（实际检索时由调用方按语义相似度填充）
     */
    public double retrievalScore(LocalDateTime recordTime, Integer mastery) {
        return 1.0 * timeDecay(recordTime) * importanceWeight(mastery);
    }

    /**
     * 取用户最近 N 天内的面试记录（按时间衰减排序）
     */
    public List<InterviewSession> getRecentSessions(Long userId, int days, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        LambdaQueryWrapper<InterviewSession> wrapper = new LambdaQueryWrapper<InterviewSession>()
                .eq(InterviewSession::getUserId, userId)
                .eq(InterviewSession::getStatus, 1)
                .ge(InterviewSession::getCreateTime, since)
                .orderByDesc(InterviewSession::getCreateTime)
                .last("LIMIT " + limit);
        return sessionMapper.selectList(wrapper);
    }

    /**
     * 取用户所有面试中评分最低的 N 次（用于薄弱点分析）
     */
    public List<InterviewSession> getWorstSessions(Long userId, int limit) {
        LambdaQueryWrapper<InterviewSession> wrapper = new LambdaQueryWrapper<InterviewSession>()
                .eq(InterviewSession::getUserId, userId)
                .eq(InterviewSession::getStatus, 1)
                .isNotNull(InterviewSession::getScore)
                .orderByAsc(InterviewSession::getScore)
                .last("LIMIT " + limit);
        return sessionMapper.selectList(wrapper);
    }

    /**
     * 计算用户历史平均分
     */
    public double avgScore(Long userId) {
        List<InterviewSession> sessions = getUserSessions(userId, 100);
        return sessions.stream()
                .filter(s -> s.getScore() != null)
                .mapToInt(InterviewSession::getScore)
                .average()
                .orElse(0.0);
    }

    /**
     * 取用户历史面试总数
     */
    public int totalInterviews(Long userId) {
        LambdaQueryWrapper<InterviewSession> wrapper = new LambdaQueryWrapper<InterviewSession>()
                .eq(InterviewSession::getUserId, userId)
                .eq(InterviewSession::getStatus, 1);
        return Math.toIntExact(sessionMapper.selectCount(wrapper));
    }

    /**
     * 按时间衰减排序返回用户面试会话（带综合评分）
     */
    public List<InterviewSession> sessionsByDecay(Long userId, int limit) {
        List<InterviewSession> sessions = getUserSessions(userId, 100);
        return sessions.stream()
                .sorted(Comparator.comparingDouble(
                        (InterviewSession s) -> retrievalScore(s.getCreateTime(), s.getScore())).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * 取用户近 N 天的面试问答记录（用于多策略记忆检索 - 时间路）
     * <p>
     * 通过 session 关联查询：先查用户近 N 天的 session，再查这些 session 的所有记录。
     */
    public List<InterviewRecord> getRecentRecords(Long userId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        LambdaQueryWrapper<InterviewSession> sessionWrapper = new LambdaQueryWrapper<InterviewSession>()
                .eq(InterviewSession::getUserId, userId)
                .ge(InterviewSession::getCreateTime, since)
                .select(InterviewSession::getId);
        List<InterviewSession> sessions = sessionMapper.selectList(sessionWrapper);
        if (sessions.isEmpty()) return new java.util.ArrayList<>();

        List<Long> sessionIds = sessions.stream().map(InterviewSession::getId).collect(Collectors.toList());

        LambdaQueryWrapper<InterviewRecord> wrapper = new LambdaQueryWrapper<InterviewRecord>()
                .in(InterviewRecord::getSessionId, sessionIds)
                .orderByDesc(InterviewRecord::getCreateTime)
                .last("LIMIT 50");
        return recordMapper.selectList(wrapper);
    }
}
