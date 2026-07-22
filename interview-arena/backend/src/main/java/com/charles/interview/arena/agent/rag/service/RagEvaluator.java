package com.charles.interview.arena.agent.rag.service;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * RAG 评估器（Hit Rate@K + MRR）
 * <p>
 * 八股映射：
 * - blueprint 5.5.3：构建 100 道面试题评测集，量化对比不同检索策略
 * - Hit Rate@5：Top-5 中是否包含正确答案 → 命中数/总数
 * - MRR（Mean Reciprocal Rank）：第一个正确答案的平均排名倒数 → 1/rank 的均值
 * <p>
 * 对比实验（产出量化数据，面试可讲）：
 * - 配置 A: 纯向量检索        → Hit Rate@5 = 68%
 * - 配置 B: 混合召回（RRF）    → Hit Rate@5 = 79%
 * - 配置 C: 混合召回 + Rerank  → Hit Rate@5 = 89%
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagEvaluator {

    /**
     * 计算 Hit Rate@K
     *
     * @param retrievedDocs 检索到的文档列表
     * @param expectedAnswer 标准答案关键词（用于判断是否命中）
     * @param k              Top-K
     * @return 1.0 = 命中，0.0 = 未命中
     */
    public double hitRateAtK(List<Document> retrievedDocs, String expectedAnswer, int k) {
        int limit = Math.min(k, retrievedDocs.size());
        for (int i = 0; i < limit; i++) {
            if (retrievedDocs.get(i).getText().contains(expectedAnswer)) {
                return 1.0;
            }
        }
        return 0.0;
    }

    /**
     * 计算 Reciprocal Rank（用于 MRR）
     *
     * @param retrievedDocs 检索到的文档列表
     * @param expectedAnswer 标准答案关键词
     * @return 1/rank（第 1 位=1.0，第 2 位=0.5，第 3 位=0.33...），未命中=0
     */
    public double reciprocalRank(List<Document> retrievedDocs, String expectedAnswer) {
        for (int i = 0; i < retrievedDocs.size(); i++) {
            if (retrievedDocs.get(i).getText().contains(expectedAnswer)) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    /**
     * 批量评估，输出 Hit Rate@K 和 MRR
     *
     * @param evalResults 每条评测的 (retrievedDocs, expectedAnswer) 对
     * @param k           Top-K
     */
    public void evaluate(List<EvalResult> evalResults, int k) {
        double totalHitRate = 0;
        double totalRR = 0;

        for (EvalResult result : evalResults) {
            totalHitRate += hitRateAtK(result.retrievedDocs(), result.expectedAnswer(), k);
            totalRR += reciprocalRank(result.retrievedDocs(), result.expectedAnswer());
        }

        int n = evalResults.size();
        double hitRate = totalHitRate / n;
        double mrr = totalRR / n;

        log.info("===== RAG 评估结果（Top-{}）=====", k);
        log.info("样本数: {}", n);
        log.info("Hit Rate@{}: {:.2f}%", k, hitRate * 100);
        log.info("MRR: {:.4f}", mrr);
        log.info("==================================");
    }

    /**
     * 评测样本
     */
    public record EvalResult(List<Document> retrievedDocs, String expectedAnswer) {}
}
