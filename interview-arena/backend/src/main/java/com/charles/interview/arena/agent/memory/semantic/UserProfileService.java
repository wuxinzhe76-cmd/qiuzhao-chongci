package com.charles.interview.arena.agent.memory.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.charles.interview.arena.agent.memory.semantic.model.WeakPoint;
import com.charles.interview.arena.mapper.UserKnowledgeProfileMapper;
import com.charles.interview.arena.mapper.UserMemorySummaryMapper;
import com.charles.interview.arena.model.entity.UserKnowledgeProfile;
import com.charles.interview.arena.model.entity.UserMemorySummary;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 语义记忆服务（蓝图 §5.4.5 三层记忆模型）
 * <p>
 * 存储：MySQL（user_knowledge_profile + user_memory_summary） + Milvus（薄弱点向量）
 * 存什么：用户知识画像（薄弱点、掌握度、偏好标签）
 * <p>
 * 八股映射：
 * - 借鉴 Hello-Agents 第八章 SemanticMemory（Qdrant + Neo4j）
 * - 简化：用 MySQL 标签 + Milvus 向量检索替代 Neo4j 知识图谱
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserKnowledgeProfileMapper profileMapper;
    private final UserMemorySummaryMapper summaryMapper;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    /** 薄弱点阈值（蓝图 §5.4.5：mastery < 60） */
    private static final int WEAK_THRESHOLD = 60;

    /** 顽固薄弱点阈值（蓝图 §5.4.5：mastery < 40，永不遗忘） */
    private static final int PERSISTENT_THRESHOLD = 40;

    /**
     * 加载用户知识画像（用于面试开始时驱动出题）
     */
    public com.charles.interview.arena.agent.memory.semantic.model.UserProfile loadProfile(Long userId) {
        com.charles.interview.arena.agent.memory.semantic.model.UserProfile bo = new com.charles.interview.arena.agent.memory.semantic.model.UserProfile();
        bo.setUserId(userId);

        // 1. 取摘要
        UserMemorySummary summary = getSummary(userId);
        if (summary != null) {
            bo.setTotalInterviews(summary.getTotalInterviews());
            bo.setAvgScore(summary.getAvgScore() != null ? summary.getAvgScore().doubleValue() : 0.0);
            bo.setPreferredTags(parseStringList(summary.getPreferredTags()));
            bo.setRecommendedReview(summary.getRecommendedReview());
        } else {
            bo.setTotalInterviews(0);
            bo.setAvgScore(0.0);
            bo.setPreferredTags(new ArrayList<>());
        }

        // 2. 取所有知识点
        List<UserKnowledgeProfile> profiles = listProfiles(userId);
        List<WeakPoint> weakPoints = profiles.stream()
                .filter(p -> p.getAvgMastery() != null && p.getAvgMastery() < WEAK_THRESHOLD)
                .map(this::toWeakness)
                .collect(Collectors.toList());
        bo.setWeakPoints(weakPoints);

        return bo;
    }

    /**
     * 取用户的薄弱点列表（mastery < 60）
     */
    public List<WeakPoint> getWeakPoints(Long userId) {
        LambdaQueryWrapper<UserKnowledgeProfile> wrapper = new LambdaQueryWrapper<UserKnowledgeProfile>()
                .eq(UserKnowledgeProfile::getUserId, userId)
                .lt(UserKnowledgeProfile::getAvgMastery, WEAK_THRESHOLD)
                .orderByAsc(UserKnowledgeProfile::getAvgMastery);
        List<UserKnowledgeProfile> profiles = profileMapper.selectList(wrapper);
        return profiles.stream().map(this::toWeakness).collect(Collectors.toList());
    }

    /**
     * 取顽固薄弱点（连续 2 次 mastery < 60）
     */
    public List<WeakPoint> getPersistentWeakPoints(Long userId) {
        LambdaQueryWrapper<UserKnowledgeProfile> wrapper = new LambdaQueryWrapper<UserKnowledgeProfile>()
                .eq(UserKnowledgeProfile::getUserId, userId)
                .eq(UserKnowledgeProfile::getIsPersistent, 1)
                .orderByAsc(UserKnowledgeProfile::getAvgMastery);
        List<UserKnowledgeProfile> profiles = profileMapper.selectList(wrapper);
        return profiles.stream().map(this::toWeakness).collect(Collectors.toList());
    }

    /**
     * 保存或更新知识点画像
     */
    public void saveOrUpdateProfile(Long userId, String topic, int mastery) {
        LambdaQueryWrapper<UserKnowledgeProfile> wrapper = new LambdaQueryWrapper<UserKnowledgeProfile>()
                .eq(UserKnowledgeProfile::getUserId, userId)
                .eq(UserKnowledgeProfile::getTopic, topic);
        UserKnowledgeProfile existing = profileMapper.selectOne(wrapper);

        if (existing == null) {
            // 新增
            UserKnowledgeProfile p = new UserKnowledgeProfile();
            p.setUserId(userId);
            p.setTopic(topic);
            p.setAvgMastery(mastery);
            p.setExamCount(1);
            p.setWeakCount(mastery < WEAK_THRESHOLD ? 1 : 0);
            p.setIsPersistent(0);
            p.setLastExamTime(java.time.LocalDateTime.now());
            profileMapper.insert(p);
        } else {
            // 更新：重算 avgMastery、examCount、weakCount、isPersistent
            int newExamCount = existing.getExamCount() + 1;
            int totalScore = existing.getAvgMastery() * existing.getExamCount() + mastery;
            int newAvg = totalScore / newExamCount;
            int newWeakCount = existing.getWeakCount() + (mastery < WEAK_THRESHOLD ? 1 : 0);

            // 顽固薄弱点：连续 2 次 mastery < 60
            int isPersistent = newWeakCount >= 2 ? 1 : 0;

            existing.setAvgMastery(newAvg);
            existing.setExamCount(newExamCount);
            existing.setWeakCount(newWeakCount);
            existing.setIsPersistent(isPersistent);
            existing.setLastExamTime(java.time.LocalDateTime.now());
            profileMapper.updateById(existing);
        }
    }

    /**
     * 标记顽固薄弱点
     */
    public void markPersistent(Long userId, String topic) {
        LambdaQueryWrapper<UserKnowledgeProfile> wrapper = new LambdaQueryWrapper<UserKnowledgeProfile>()
                .eq(UserKnowledgeProfile::getUserId, userId)
                .eq(UserKnowledgeProfile::getTopic, topic);
        UserKnowledgeProfile p = profileMapper.selectOne(wrapper);
        if (p != null) {
            p.setIsPersistent(1);
            profileMapper.updateById(p);
        }
    }

    /**
     * 向量化薄弱点描述并写入 Milvus（用于跨会话语义检索）
     */
    public void indexWeakPointToVectorStore(Long userId, String topic, String description) {
        try {
            String content = "用户 " + userId + " 的薄弱点：" + topic + " - " + description;
            java.util.Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("userId", userId);
            metadata.put("topic", topic);
            metadata.put("type", "weak_point");
            org.springframework.ai.document.Document doc =
                    new org.springframework.ai.document.Document(content, metadata);
            vectorStore.add(java.util.List.of(doc));
            log.info("薄弱点已写入 Milvus：userId={}, topic={}", userId, topic);
        } catch (Exception e) {
            log.warn("薄弱点写入 Milvus 失败（不影响主流程）：userId={}, topic={}, err={}",
                    userId, topic, e.getMessage());
        }
    }

    /**
     * 通过 Milvus 向量检索找用户语义相似的薄弱点
     */
    public List<String> searchSimilarWeakTopics(Long userId, String query, int topK) {
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression("userId == " + userId + " and type == 'weak_point'")
                    .similarityThreshold(0.5)
                    .build();
            List<org.springframework.ai.document.Document> docs = vectorStore.similaritySearch(request);
            if (docs == null) return new ArrayList<>();
            return docs.stream()
                    .map(d -> String.valueOf(d.getMetadata().get("topic")))
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Milvus 薄弱点检索失败：userId={}, err={}", userId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 保存用户记忆摘要
     */
    public void saveSummary(Long userId, int totalInterviews, double avgScore,
                            List<String> weakTopics, List<String> preferredTags,
                            String recommendedReview) {
        try {
            LambdaQueryWrapper<UserMemorySummary> wrapper = new LambdaQueryWrapper<UserMemorySummary>()
                    .eq(UserMemorySummary::getUserId, userId);
            UserMemorySummary existing = summaryMapper.selectOne(wrapper);

            UserMemorySummary s = existing != null ? existing : new UserMemorySummary();
            s.setUserId(userId);
            s.setTotalInterviews(totalInterviews);
            s.setAvgScore(java.math.BigDecimal.valueOf(avgScore));
            s.setWeakTopics(objectMapper.writeValueAsString(weakTopics));
            s.setPreferredTags(objectMapper.writeValueAsString(preferredTags));
            s.setRecommendedReview(recommendedReview);
            s.setLastInterviewTime(java.time.LocalDateTime.now());

            if (existing == null) {
                summaryMapper.insert(s);
            } else {
                summaryMapper.updateById(s);
            }
        } catch (Exception e) {
            log.error("保存用户记忆摘要失败：userId={}", userId, e);
        }
    }

    public UserMemorySummary getSummary(Long userId) {
        LambdaQueryWrapper<UserMemorySummary> wrapper = new LambdaQueryWrapper<UserMemorySummary>()
                .eq(UserMemorySummary::getUserId, userId);
        return summaryMapper.selectOne(wrapper);
    }

    public List<UserKnowledgeProfile> listProfiles(Long userId) {
        LambdaQueryWrapper<UserKnowledgeProfile> wrapper = new LambdaQueryWrapper<UserKnowledgeProfile>()
                .eq(UserKnowledgeProfile::getUserId, userId);
        return profileMapper.selectList(wrapper);
    }

    // ===== 内部工具 =====

    private WeakPoint toWeakness(UserKnowledgeProfile p) {
        WeakPoint w = new WeakPoint();
        w.setTopic(p.getTopic());
        w.setAvgMastery(p.getAvgMastery());
        w.setExamCount(p.getExamCount());
        w.setWeakCount(p.getWeakCount());
        w.setIsPersistent(p.getIsPersistent() != null && p.getIsPersistent() == 1);
        w.setLastExamTimestamp(p.getLastExamTime() != null
                ? java.sql.Timestamp.valueOf(p.getLastExamTime()).getTime()
                : null);
        return w;
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
