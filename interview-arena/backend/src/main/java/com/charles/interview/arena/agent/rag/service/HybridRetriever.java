package com.charles.interview.arena.agent.rag.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.memory.retrieval.KnowledgeRetriever;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 混合检索器（Hybrid Retrieval = 向量检索 + BM25 + RRF 融合）
 * <p>
 * 八股映射：
 * - #6 混合检索：向量+BM25 正交互补，不是二选一
 * - #8 RRF 公式：score(d) = Σ 1/(k + rank_i(d))，k=60（Google 论文）
 *   - 排名融合非分数融合（向量和 BM25 分数量纲不同，不能直接加）
 *   - 赢家通吃：排名第 1 的文档贡献 1/61，排名第 2 的贡献 1/62
 *   - k=60 是经验值，来自 Google 论文 "Scaling Up Soft Vector Operations"
 * <p>
 * 流程：
 * 1. 向量检索 Top-20（Milvus 语义相似）
 * 2. BM25 检索 Top-20（ES 关键词匹配）
 * 3. RRF 融合 → 去重排序 → Top-10
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever implements KnowledgeRetriever {

    private final VectorStore vectorStore;
    private final BM25Retriever bm25Retriever;

    private static final int VECTOR_TOP_K = 20;
    private static final int BM25_TOP_K = 20;
    private static final int FINAL_TOP_K = 10;
    private static final double SIMILARITY_THRESHOLD = 0.5;
    private static final int RRF_K = 60;

    /**
     * 混合检索：向量 + BM25 + RRF 融合
     *
     * @param query 用户提问
     * @return RRF 融合后的 Top-10 文档
     */
    @Override
    public List<Document> retrieve(String query) {
        return retrieve(query, null);
    }

    /**
     * 混合检索 + 元数据过滤：向量 + BM25 + RRF 融合
     *
     * @param query            用户提问
     * @param filterExpression Spring AI 过滤表达式（如 "category == 'Java基础'"），null 不过滤
     * @return RRF 融合后的 Top-10 文档
     */
    public List<Document> retrieve(String query, String filterExpression) {
        // 1. 向量检索 Top-20（语义相似，支持元数据过滤）
        SearchRequest.Builder searchBuilder = SearchRequest.builder()
                .query(query)
                .topK(VECTOR_TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD);
        if (filterExpression != null && !filterExpression.isBlank()) {
            searchBuilder.filterExpression(filterExpression);
        }
        List<Document> vectorResults = vectorStore.similaritySearch(searchBuilder.build());
        log.info("向量检索命中 {} 条（filter={}）", vectorResults.size(), filterExpression);

        // 2. BM25 检索 Top-20（关键词匹配，支持 category 过滤）
        List<Document> bm25Results = bm25Retriever.retrieve(query, BM25_TOP_K, filterExpression);
        log.info("BM25 检索命中 {} 条（filter={}）", bm25Results.size(), filterExpression);

        // 3. RRF 融合
        List<Document> fused = rrfFuse(vectorResults, bm25Results, FINAL_TOP_K);
        log.info("RRF 融合后 Top-{} 条", fused.size());

        return fused;
    }

    /**
     * RRF（Reciprocal Rank Fusion）排名融合
     * <p>
     * 公式：score(d) = Σ 1/(k + rank_i(d))
     * - k=60：平滑参数，防止排名第 1 的文档权重过大（赢家通吃）
     * - rank 从 1 开始（不是 0）
     * - 同一文档在两个检索结果中都出现 → 分数累加
     */
    private List<Document> rrfFuse(List<Document> vectorResults, List<Document> bm25Results, int topK) {
        // 用 content 作为去重 key（同一题目可能在两个检索中都出现）
        Map<String, Document> docMap = new HashMap<>();
        Map<String, Double> scoreMap = new HashMap<>();

        // 向量检索结果排名（rank 从 1 开始）
        for (int i = 0; i < vectorResults.size(); i++) {
            String key = vectorResults.get(i).getText();
            int rank = i + 1;
            double score = 1.0 / (RRF_K + rank);
            docMap.putIfAbsent(key, vectorResults.get(i));
            scoreMap.merge(key, score, Double::sum);
        }

        // BM25 检索结果排名
        for (int i = 0; i < bm25Results.size(); i++) {
            String key = bm25Results.get(i).getText();
            int rank = i + 1;
            double score = 1.0 / (RRF_K + rank);
            docMap.putIfAbsent(key, bm25Results.get(i));
            scoreMap.merge(key, score, Double::sum);
        }

        // 按 RRF 分数降序排序，取 Top-K
        List<Map.Entry<String, Double>> sorted = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .toList();

        List<Document> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : sorted) {
            result.add(docMap.get(entry.getKey()));
        }

        return result;
    }
}
