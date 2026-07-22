package com.charles.interview.arena.agent.memory.consolidation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.charles.interview.arena.agent.memory.episodic.InterviewHistoryService;
import com.charles.interview.arena.agent.memory.semantic.ProfileAnalyzer;
import com.charles.interview.arena.agent.memory.semantic.UserProfileService;
import com.charles.interview.arena.model.entity.InterviewRecord;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 记忆整合服务（蓝图 §5.4.5 Consolidation）
 * <p>
 * 面试结束触发：工作记忆->情景记忆->语义记忆 自动升级。
 * <p>
 * Step 1: 工作记忆 -> 情景记忆
 *   Redis 对话历史 -> 异步刷入 interview_record 表（已在每轮即时落库，无需重做）
 *   interview_session status -> 1（已结束，已在 endInterview 中完成）
 * <p>
 * Step 2: 情景记忆 -> 语义记忆（★ 新增核心）
 *   AI 分析本次面试所有问答 -> 提取知识图谱：
 *   - 哪些知识点被考察了
 *   - 每个知识点的掌握度
 *   - 标记薄弱点（mastery < 60）
 *   -> 写入 user_knowledge_profile 表
 *   -> 薄弱点描述向量化后写入 Milvus
 * <p>
 * Step 3: 更新用户画像 user_memory_summary 表
 *   - 总面试次数、平均分
 *   - Top-3 薄弱知识点
 *   - 推荐复习方向
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileUpdateService {

    private final InterviewHistoryService episodicMemoryService;
    private final UserProfileService semanticMemoryService;
    private final ProfileAnalyzer knowledgeProfileAnalyzer;

    /** 蓝图 §5.4.5：薄弱点阈值 */
    private static final int WEAK_THRESHOLD = 60;

    /**
     * 实际整合逻辑（传入 userId）
     *
     * @param sessionId 面试会话 ID
     * @param userId    用户 ID
     */
    public void consolidate(Long sessionId, Long userId) {
        log.info("记忆整合开始：sessionId={}, userId={}", sessionId, userId);

        try {
            // Step 1: 工作记忆 -> 情景记忆
            // 已在每轮即时落库（InterviewServiceImpl.answerInterview 中 INSERT interview_record）
            // 已在 endInterview 中 session.status -> 1
            log.info("Step 1: 工作记忆 -> 情景记忆（已即时落库，跳过）");

            // Step 2: 情景记忆 -> 语义记忆
            List<InterviewRecord> records = episodicMemoryService.getSessionRecords(sessionId);
            if (records.isEmpty()) {
                log.warn("面试无问答记录，跳过整合：sessionId={}", sessionId);
                return;
            }

            Map<String, Integer> topicMasteryMap = knowledgeProfileAnalyzer.analyze(records);
            log.info("Step 2: AI 分析出 {} 个知识点", topicMasteryMap.size());

            // 写入 user_knowledge_profile + Milvus
            for (Map.Entry<String, Integer> entry : topicMasteryMap.entrySet()) {
                String topic = entry.getKey();
                int mastery = entry.getValue();

                semanticMemoryService.saveOrUpdateProfile(userId, topic, mastery);

                // 薄弱点向量化写入 Milvus
                if (mastery < WEAK_THRESHOLD) {
                    semanticMemoryService.indexWeakPointToVectorStore(userId, topic,
                            "掌握度 " + mastery + "/100，需重点复习");
                }
            }

            // Step 3: 更新用户画像 user_memory_summary
            updateMemorySummary(userId);

            log.info("记忆整合完成：sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("记忆整合失败：sessionId={}, userId={}", sessionId, userId, e);
        }
    }

    /**
     * 更新用户记忆摘要
     */
    private void updateMemorySummary(Long userId) {
        int total = episodicMemoryService.totalInterviews(userId);
        double avg = episodicMemoryService.avgScore(userId);

        // Top-3 薄弱知识点
        List<String> weakTopics = semanticMemoryService.getWeakPoints(userId).stream()
                .limit(3)
                .map(com.charles.interview.arena.agent.memory.semantic.model.WeakPoint::getTopic)
                .collect(java.util.stream.Collectors.toList());

        // 推荐复习方向
        String recommended = weakTopics.isEmpty()
                ? "暂无明显薄弱点，建议保持练习节奏"
                : "建议重点复习：" + String.join("、", weakTopics);

        semanticMemoryService.saveSummary(userId, total, avg, weakTopics, new ArrayList<>(), recommended);
    }
}
