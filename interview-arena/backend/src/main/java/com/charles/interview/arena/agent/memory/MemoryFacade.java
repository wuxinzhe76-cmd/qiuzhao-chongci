package com.charles.interview.arena.agent.memory;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.memory.consolidation.ProfileUpdateService;
import com.charles.interview.arena.agent.memory.episodic.InterviewHistoryService;
import com.charles.interview.arena.agent.memory.semantic.model.WeakPoint;
import com.charles.interview.arena.agent.memory.semantic.model.UserProfile;
import com.charles.interview.arena.agent.memory.semantic.UserProfileService;
import com.charles.interview.arena.agent.memory.working.WorkingMemoryService;
import com.charles.interview.arena.model.entity.InterviewRecord;
import com.charles.interview.arena.model.entity.InterviewSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Memory Facade（门面，协调各记忆服务，不实现记忆接口）
 * <p>
 * 统一调度短期记忆和长期记忆，对外提供四类操作：
 * <pre>
 * ┌─────────────────────────────────────────────────────────────┐
 * │                    记忆系统架构                              │
 * ├─────────────────────────────────────────────────────────────┤
 * │                                                              │
 * │  短期记忆 = Redis，TTL 2h                                       │
 * │  ├── 对话历史：interview:history:{sessionId}（滑动窗口10条） │
 * │  └── 工作聚焦：题目ID / 总轮次 / 追问轮次 / 已用题目集       │
 * │                                                              │
 * │  长期记忆 = MySQL + Milvus，永久                               │
 * │  ├── 情景记忆：interview_record 表（完整问答明细）           │
 * │  └── 语义记忆：user_knowledge_profile 表（知识点画像+薄弱点）│
 * │                                                              │
 * │  四类操作：                                                   │
 * │  ├── add：写入短期记忆（对话历史 + 工作聚焦）                │
 * │  ├── retrieve：检索记忆（短期取对话，长期取画像/薄弱点）      │
 * │  ├── consolidate：面试结束整合（短期 -> 长期升级）           │
 * │  └── forget：清理短期记忆（释放 Redis 空间）                 │
 * └─────────────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * 委托关系：
 * - 短期记忆操作 -> WorkingMemoryService（已有实现）
 * - 情景记忆操作 -> InterviewHistoryService（已有实现）
 * - 语义记忆操作 -> UserProfileService（已有实现）
 * - 记忆整合     -> ProfileUpdateService（已有实现）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryFacade {

    /** 短期记忆实现：Redis 工作记忆 */
    private final WorkingMemoryService workingMemoryService;

    /** 长期记忆-情景：MySQL 面试记录 */
    private final InterviewHistoryService episodicMemoryService;

    /** 长期记忆-语义：MySQL + Milvus 用户画像 */
    private final UserProfileService semanticMemoryService;

    /** 记忆整合服务：工作记忆 -> 情景记忆 -> 语义记忆 */
    private final ProfileUpdateService consolidationService;

    // ==================== 记忆写入（唯一入口） ====================

    /**
     * 记住一轮对话：情景记忆（MySQL 即时落库）+ 工作记忆（Redis 对话历史）
     * <p>
     * 面试主流程每轮调用一次（user 一次 / assistant 一次），
     * 替代原「SaveRecordTool + pushHistory」的双入口写法。
     */
    public void rememberTurn(Long sessionId, Long questionId, String role, String content, int roundNum) {
        episodicMemoryService.saveRecord(sessionId, questionId, role, content, roundNum);
        workingMemoryService.pushHistory(sessionId, role + ":" + content);
    }

    /**
     * 只落库、不进对话历史
     * <p>
     * 用于目标漂移拦截等场景：保证 MySQL 面试记录完整，
     * 但不把漂移对话喂给 LLM（防上下文污染）。
     */
    public void saveRecordOnly(Long sessionId, Long questionId, String role, String content, int roundNum) {
        episodicMemoryService.saveRecord(sessionId, questionId, role, content, roundNum);
    }

    // ==================== 短期记忆：对话历史 ====================

    public void pushHistory(Long sessionId, String message) {
        workingMemoryService.pushHistory(sessionId, message);
    }

    public List<String> getHistory(Long sessionId) {
        return workingMemoryService.getHistory(sessionId);
    }

    /**
     * 取最近 N 条对话（滑动窗口，N 由 interview.history-window-size 配置，默认 10）
     * <p>
     * 给 LLM 上下文时应优先使用本方法而非 getHistory（全量最多 50 条），节省 Token。
     */
    public List<String> getRecentHistory(Long sessionId) {
        return workingMemoryService.getRecentHistory(sessionId);
    }

    // ==================== 短期记忆：工作聚焦 ====================

    public void setCurrentQuestion(Long sessionId, Long questionId) {
        workingMemoryService.setCurrentQuestion(sessionId, questionId);
    }

    public Long getCurrentQuestion(Long sessionId) {
        return workingMemoryService.getCurrentQuestion(sessionId);
    }

    public long incrementRound(Long sessionId) {
        return workingMemoryService.incrementRound(sessionId);
    }

    public long getRound(Long sessionId) {
        return workingMemoryService.getRound(sessionId);
    }

    public long incrementQuestionRound(Long sessionId) {
        return workingMemoryService.incrementQuestionRound(sessionId);
    }

    public long getQuestionRound(Long sessionId) {
        return workingMemoryService.getQuestionRound(sessionId);
    }

    public void resetQuestionRound(Long sessionId) {
        workingMemoryService.resetQuestionRound(sessionId);
    }

    public void addUsedQuestion(Long sessionId, Long questionId) {
        workingMemoryService.addUsedQuestion(sessionId, questionId);
    }

    public Set<String> getUsedQuestions(Long sessionId) {
        return workingMemoryService.getUsedQuestions(sessionId);
    }

    // ==================== 短期记忆：生命周期 ====================

    public void clearAll(Long sessionId) {
        log.info("清理短期记忆: sessionId={}", sessionId);
        workingMemoryService.clearAll(sessionId);
    }

    // ==================== 长期记忆：情景记忆 ====================

    public List<InterviewSession> getUserSessions(Long userId, int limit) {
        return episodicMemoryService.getUserSessions(userId, limit);
    }

    public List<InterviewRecord> getSessionRecords(Long sessionId) {
        return episodicMemoryService.getSessionRecords(sessionId);
    }

    public List<InterviewRecord> getRecentRecords(Long userId, int days) {
        return episodicMemoryService.getRecentRecords(userId, days);
    }

    public List<InterviewSession> sessionsByDecay(Long userId, int limit) {
        return episodicMemoryService.sessionsByDecay(userId, limit);
    }

    public double avgScore(Long userId) {
        return episodicMemoryService.avgScore(userId);
    }

    public int totalInterviews(Long userId) {
        return episodicMemoryService.totalInterviews(userId);
    }

    // ==================== 长期记忆：语义记忆 ====================

    public UserProfile loadProfile(Long userId) {
        return semanticMemoryService.loadProfile(userId);
    }

    public List<WeakPoint> getWeakPoints(Long userId) {
        return semanticMemoryService.getWeakPoints(userId);
    }

    public List<WeakPoint> getPersistentWeakPoints(Long userId) {
        return semanticMemoryService.getPersistentWeakPoints(userId);
    }

    public void saveOrUpdateProfile(Long userId, String topic, int mastery) {
        semanticMemoryService.saveOrUpdateProfile(userId, topic, mastery);
    }

    public void markPersistent(Long userId, String topic) {
        semanticMemoryService.markPersistent(userId, topic);
    }

    // ==================== 记忆整合 ====================

    public void consolidate(Long sessionId, Long userId) {
        log.info("触发记忆整合: sessionId={}, userId={}", sessionId, userId);
        consolidationService.consolidate(sessionId, userId);
    }

    // ==================== 便捷方法 ====================

    /**
     * 面试开始时加载用户画像（记忆驱动出题用）
     * <p>
     * 新用户返回 null -> 随机抽题
     * 老用户返回画像 -> 优先考薄弱点
     */
    public UserProfile retrieveProfile(Long userId) {
        try {
            return semanticMemoryService.loadProfile(userId);
        } catch (Exception e) {
            log.warn("加载用户画像失败，降级为随机出题: userId={}, err={}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * 取顽固薄弱点列表（记忆驱动出题用）
     */
    public List<WeakPoint> retrievePersistentWeakPoints(Long userId) {
        try {
            return semanticMemoryService.getPersistentWeakPoints(userId);
        } catch (Exception e) {
            log.warn("取薄弱点失败: userId={}, err={}", userId, e.getMessage());
            return List.of();
        }
    }

}
