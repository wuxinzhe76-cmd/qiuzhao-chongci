package com.charles.interview.arena.agent.memory.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.charles.interview.arena.agent.memory.episodic.InterviewHistoryService;
import com.charles.interview.arena.agent.memory.semantic.UserProfileService;
import com.charles.interview.arena.agent.memory.semantic.model.WeakPoint;
import com.charles.interview.arena.model.entity.InterviewRecord;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 多策略记忆检索 + RRF 融合（蓝图 §5.7.5）
 * <p>
 * 八股映射：
 * - 四路并行检索：语义 + 关键词 + 时间 + 薄弱点
 * - RRF（Reciprocal Rank Fusion）：融合四路结果，避免分数尺度不一致问题
 * <p>
 * 用于 Quick Ask / Agentic RAG：在 RAG 检索前先查记忆，避免重复问已学过的内容。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiStrategyMemoryRetriever {

    private final UserProfileService semanticMemoryService;
    private final InterviewHistoryService episodicMemoryService;
    /** 知识库检索（DIP：接口在 memory 侧，实现在 RAG 侧的 HybridRetriever） */
    private final KnowledgeRetriever knowledgeRetriever;

    /** RRF 超参数（标准值 60，平衡 Top-1 与 Top-10 权重） */
    private static final int RRF_K = 60;
    /** 每路检索 Top-K */
    private static final int PER_STRATEGY_TOP_K = 10;
    /** 融合后保留 Top-N */
    private static final int FINAL_TOP_N = 5;

    /**
     * 多策略记忆检索 + RRF 融合
     *
     * @param userId 用户 ID
     * @param query  当前查询
     * @return 融合后的记忆片段列表
     */
    public List<MemoryFragment> retrieveWithRRF(Long userId, String query) {
        log.info("多策略记忆检索：userId={}, query='{}'", userId, query);

        // 1. 四路并行检索
        List<MemoryFragment> semantic = retrieveSemantic(userId, query);
        List<MemoryFragment> keyword = retrieveKeyword(query);
        List<MemoryFragment> temporal = retrieveTemporal(userId);
        List<MemoryFragment> weakness = retrieveWeakness(userId);

        log.info("四路检索结果数：semantic={}, keyword={}, temporal={}, weakness={}",
                semantic.size(), keyword.size(), temporal.size(), weakness.size());

        // 2. RRF 融合
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, MemoryFragment> fragmentMap = new HashMap<>();

        addRRFScores(semantic, rrfScores, fragmentMap);
        addRRFScores(keyword, rrfScores, fragmentMap);
        addRRFScores(temporal, rrfScores, fragmentMap);
        addRRFScores(weakness, rrfScores, fragmentMap);

        // 3. 按 RRF 分数降序取 Top-N
        return rrfScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(FINAL_TOP_N)
                .map(e -> {
                    MemoryFragment f = fragmentMap.get(e.getKey());
                    f.setScore(e.getValue());
                    return f;
                })
                .collect(Collectors.toList());
    }

    /**
     * 路径 1：语义检索（基于 Milvus 向量相似）
     */
    private List<MemoryFragment> retrieveSemantic(Long userId, String query) {
        try {
            List<String> topics = semanticMemoryService.searchSimilarWeakTopics(userId, query, PER_STRATEGY_TOP_K);
            return topics.stream()
                    .map(t -> new MemoryFragment(t, "语义检索：薄弱点 '" + t + "'", "semantic"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("语义检索失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 路径 2：关键词检索（基于 ES BM25）
     */
    private List<MemoryFragment> retrieveKeyword(String query) {
        try {
            return knowledgeRetriever.retrieve(query).stream()
                    .limit(PER_STRATEGY_TOP_K)
                    .map(d -> new MemoryFragment(
                            d.getText(),
                            "关键词检索：" + d.getMetadata().getOrDefault("title", ""),
                            "keyword"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("关键词检索失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 路径 3：时间检索（最近 N 天的面试记录）
     */
    private List<MemoryFragment> retrieveTemporal(Long userId) {
        try {
            List<InterviewRecord> records = episodicMemoryService.getRecentRecords(userId, 7);
            return records.stream()
                    .limit(PER_STRATEGY_TOP_K)
                    .map(r -> new MemoryFragment(
                            r.getContent(),
                            "时间检索：session=" + r.getSessionId() + " round=" + r.getRoundNum(),
                            "temporal"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("时间检索失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 路径 4：薄弱点检索（用户知识画像中的薄弱点）
     */
    private List<MemoryFragment> retrieveWeakness(Long userId) {
        try {
            List<WeakPoint> weakPoints = semanticMemoryService.getWeakPoints(userId);
            return weakPoints.stream()
                    .limit(PER_STRATEGY_TOP_K)
                    .map(w -> new MemoryFragment(
                            w.getTopic(),
                            String.format("薄弱点检索：%s (掌握度 %d, 出现 %d 次)",
                                    w.getTopic(), w.getAvgMastery(), w.getExamCount()),
                            "weakness"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("薄弱点检索失败：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * RRF 算分：score = sum(1 / (k + rank))
     */
    private void addRRFScores(List<MemoryFragment> fragments,
                              Map<String, Double> rrfScores,
                              Map<String, MemoryFragment> fragmentMap) {
        for (int i = 0; i < fragments.size(); i++) {
            MemoryFragment f = fragments.get(i);
            String key = f.getContent();
            double score = 1.0 / (RRF_K + i + 1);
            rrfScores.merge(key, score, Double::sum);
            fragmentMap.putIfAbsent(key, f);
        }
    }

    /**
     * 记忆片段 BO
     */
    public static class MemoryFragment {
        private String content;
        private String source;
        private String strategy;
        private double score;

        public MemoryFragment(String content, String source, String strategy) {
            this.content = content;
            this.source = source;
            this.strategy = strategy;
        }

        public String getContent() { return content; }
        public String getSource() { return source; }
        public String getStrategy() { return strategy; }
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
    }
}
