package com.charles.interview.arena.agent.rag.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import com.charles.interview.arena.model.entity.InterviewRecord;
import com.charles.interview.arena.mapper.InterviewRecordMapper;
import com.charles.interview.arena.mapper.InterviewSessionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RAG Gap Detection 服务（蓝图 §5.7.2）
 * <p>
 * 八股映射：
 * - 把用户回答向量化存入 Milvus，跨会话语义检索历史相似回答
 * - 发现「跨会话反复答错/答浅」的薄弱点，主动推荐复习
 * <p>
 * 工作流：
 * 1. 用户回答 → 向量化 → 写入 Milvus（type=user_answer, userId, sessionId, questionId）
 * 2. 检索 Milvus 中相似回答（cosine > 0.75）
 * 3. 如果历史相似回答也 mastery < 60，标记为「跨会话薄弱点」
 * 4. 触发复习推荐
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagGapDetectionService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final InterviewRecordMapper interviewRecordMapper;
    private final InterviewSessionMapper sessionMapper;

    /** 相似回答阈值 */
    private static final double SIMILARITY_THRESHOLD = 0.75;
    /** 薄弱掌握度阈值 */
    private static final int WEAK_MASTERY_THRESHOLD = 60;
    /** 跨会话薄弱点触发次数 */
    private static final int CROSS_SESSION_TRIGGER = 2;

    /**
     * 索引用户回答到 Milvus（蓝图 §5.7.2）
     *
     * @param userId     用户 ID
     * @param sessionId  面试会话 ID
     * @param questionId 题目 ID
     * @param questionTitle 题目标题
     * @param answer     用户回答
     * @param mastery    掌握度（0-100）
     */
    public void indexUserAnswer(Long userId, Long sessionId, Long questionId,
                                String questionTitle, String answer, int mastery) {
        try {
            String content = String.format("题目：%s\n用户回答：%s\n掌握度：%d",
                    questionTitle, answer, mastery);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", userId);
            metadata.put("sessionId", sessionId);
            metadata.put("questionId", questionId);
            metadata.put("questionTitle", questionTitle);
            metadata.put("mastery", mastery);
            metadata.put("type", "user_answer");
            metadata.put("timestamp", System.currentTimeMillis());

            Document doc = new Document(content, metadata);
            vectorStore.add(List.of(doc));
            log.info("用户回答已写入 Milvus：userId={}, questionId={}, mastery={}",
                    userId, questionId, mastery);
        } catch (Exception e) {
            log.warn("索引用户回答失败（不影响主流程）：userId={}, err={}", userId, e.getMessage());
        }
    }

    /**
     * 检测跨会话薄弱点（蓝图 §5.7.2）
     * <p>
     * 在用户回答新问题后调用：检索历史相似回答，若多次 mastery < 60，标记为跨会话薄弱点。
     *
     * @param userId      用户 ID
     * @param answer      当前回答
     * @return 跨会话薄弱点列表（题目 ID + 题目标题 + 历史平均掌握度）
     */
    public List<CrossSessionGap> detectGaps(Long userId, String answer) {
        List<CrossSessionGap> gaps = new ArrayList<>();
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(answer)
                    .topK(10)
                    .filterExpression("userId == " + userId + " and type == 'user_answer'")
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .build();
            List<Document> docs = vectorStore.similaritySearch(request);
            if (docs == null || docs.isEmpty()) {
                return gaps;
            }

            // 按 questionId 聚合
            Map<Long, List<Document>> byQuestion = new HashMap<>();
            for (Document d : docs) {
                Object qidObj = d.getMetadata().get("questionId");
                if (qidObj == null) continue;
                Long qid = toLong(qidObj);
                byQuestion.computeIfAbsent(qid, k -> new ArrayList<>()).add(d);
            }

            // 找出多次低 mastery 的题目
            for (Map.Entry<Long, List<Document>> entry : byQuestion.entrySet()) {
                List<Document> records = entry.getValue();
                if (records.size() < CROSS_SESSION_TRIGGER) continue;

                long weakCount = records.stream()
                        .filter(d -> toInt(d.getMetadata().get("mastery")) < WEAK_MASTERY_THRESHOLD)
                        .count();
                if (weakCount < CROSS_SESSION_TRIGGER) continue;

                CrossSessionGap gap = new CrossSessionGap();
                gap.setQuestionId(entry.getKey());
                gap.setQuestionTitle(String.valueOf(records.get(0).getMetadata().get("questionTitle")));
                gap.setOccurrenceCount(records.size());
                gap.setAvgMastery(records.stream()
                        .mapToInt(d -> toInt(d.getMetadata().get("mastery")))
                        .average().orElse(0));
                gaps.add(gap);
            }
            log.info("检测到跨会话薄弱点：userId={}, gaps={}", userId, gaps.size());
        } catch (Exception e) {
            log.warn("跨会话薄弱点检测失败：userId={}, err={}", userId, e.getMessage());
        }
        return gaps;
    }

    /**
     * 生成复习推荐（蓝图 §5.7.2）
     * <p>
     * 基于检测到的跨会话薄弱点，调用 LLM 生成针对性复习建议。
     */
    public String generateReviewRecommendation(Long userId, List<CrossSessionGap> gaps) {
        if (gaps == null || gaps.isEmpty()) {
            return "暂无跨会话薄弱点，继续保持！";
        }
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("检测到以下跨会话薄弱点：\n");
            for (CrossSessionGap g : gaps) {
                sb.append(String.format("- 题目 %d：%s（出现 %d 次，平均掌握度 %.1f）\n",
                        g.getQuestionId(), g.getQuestionTitle(),
                        g.getOccurrenceCount(), g.getAvgMastery()));
            }
            sb.append("\n请生成针对性复习建议（200 字以内）。");

            return chatClient.prompt()
                    .system("你是面试复习教练，根据用户薄弱点生成针对性复习建议。")
                    .user(sb.toString())
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("复习推荐生成失败：userId={}, err={}", userId, e.getMessage());
            return "复习推荐生成失败，请稍后重试";
        }
    }

    /**
     * 拉取用户最近 N 天的回答记录（用于批量检测）
     * <p>
     * 通过 InterviewSession 关联查询：先查用户所有 session，再查这些 session 的 user 回答。
     */
    public List<InterviewRecord> getRecentUserAnswers(Long userId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        // 先查用户近 N 天的 session IDs
        LambdaQueryWrapper<com.charles.interview.arena.model.entity.InterviewSession> sessionWrapper =
                new LambdaQueryWrapper<com.charles.interview.arena.model.entity.InterviewSession>()
                        .eq(com.charles.interview.arena.model.entity.InterviewSession::getUserId, userId)
                        .ge(com.charles.interview.arena.model.entity.InterviewSession::getCreateTime, since)
                        .select(com.charles.interview.arena.model.entity.InterviewSession::getId);
        List<com.charles.interview.arena.model.entity.InterviewSession> sessions =
                sessionMapper.selectList(sessionWrapper);
        if (sessions.isEmpty()) return new ArrayList<>();

        List<Long> sessionIds = sessions.stream()
                .map(com.charles.interview.arena.model.entity.InterviewSession::getId)
                .toList();

        LambdaQueryWrapper<InterviewRecord> wrapper = new LambdaQueryWrapper<InterviewRecord>()
                .in(InterviewRecord::getSessionId, sessionIds)
                .eq(InterviewRecord::getRole, "user")
                .orderByDesc(InterviewRecord::getCreateTime)
                .last("LIMIT 50");
        return interviewRecordMapper.selectList(wrapper);
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return null; }
    }

    private int toInt(Object val) {
        if (val == null) return 0;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (Exception e) { return 0; }
    }

    /**
     * 跨会话薄弱点 BO
     */
    public static class CrossSessionGap {
        private Long questionId;
        private String questionTitle;
        private int occurrenceCount;
        private double avgMastery;

        public Long getQuestionId() { return questionId; }
        public void setQuestionId(Long questionId) { this.questionId = questionId; }
        public String getQuestionTitle() { return questionTitle; }
        public void setQuestionTitle(String questionTitle) { this.questionTitle = questionTitle; }
        public int getOccurrenceCount() { return occurrenceCount; }
        public void setOccurrenceCount(int occurrenceCount) { this.occurrenceCount = occurrenceCount; }
        public double getAvgMastery() { return avgMastery; }
        public void setAvgMastery(double avgMastery) { this.avgMastery = avgMastery; }

        @Override
        public String toString() {
            return String.format("CrossSessionGap{qid=%d, title='%s', count=%d, avgMastery=%.1f}",
                    questionId, questionTitle, occurrenceCount, avgMastery);
        }
    }
}
