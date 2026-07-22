package com.charles.interview.arena.agent.rag.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 重排序服务（基于 DashScope gte-rerank Cross-Encoder）
 * <p>
 * 八股映射：
 * - #7 Rerank：Cross-Encoder 拼接文本不是向量 + [CLS][SEP] + batch 推理
 * - 双塔 vs 交叉编码器：Bi-Encoder（召回阶段，快但粗）vs Cross-Encoder（精排阶段，慢但准）
 * - 为什么不能全用 Cross-Encoder：逐对打分 O(n×q)，100 万文档要打 100 万次分，性能扛不住
 * - 两阶段架构：召回 100→10（Bi-Encoder），精排 10→5（Cross-Encoder）
 * <p>
 * DashScope Rerank API：
 * - endpoint: https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank
 * - model: gte-rerank
 * - input: { query, documents: [] }
 * - parameters: { top_n, return_documents }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankService {

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    private static final String DASHSCOPE_RERANK_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    private static final String RERANK_MODEL = "gte-rerank";

    /**
     * 对检索结果重排序
     *
     * @param query    用户提问
     * @param documents 候选文档（HybridRetriever 输出的 Top-10）
     * @param topN     重排序后返回数量（如 5）
     * @return 重排序后的 Top-N 文档
     */
    public List<Document> rerank(String query, List<Document> documents, int topN) {
        if (documents.isEmpty()) {
            return documents;
        }

        // 构造请求体
        List<String> docTexts = documents.stream().map(Document::getText).toList();

        Map<String, Object> requestBody = Map.of(
                "model", RERANK_MODEL,
                "input", Map.of(
                        "query", query,
                        "documents", docTexts),
                "parameters", Map.of(
                        "top_n", topN,
                        "return_documents", false)
        );

        try {
            String response = webClientBuilder.build()
                    .post()
                    .uri(DASHSCOPE_RERANK_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            // 解析响应：{ output: { results: [{ index, relevance_score }] } }
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("output").path("results");

            // 按 relevance_score 降序，映射回原始 Document
            List<Document> reranked = new ArrayList<>();
            for (JsonNode result : results) {
                int index = result.path("index").asInt();
                if (index < documents.size()) {
                    reranked.add(documents.get(index));
                }
            }

            log.info("Rerank 完成：{} 条 → {} 条", documents.size(), reranked.size());
            return reranked;

        } catch (Exception e) {
            log.error("Rerank 调用失败，返回原始顺序前 {} 条: {}", topN, e.getMessage());
            // 降级：API 失败时返回前 topN 条（不中断主流程）
            return documents.stream().limit(topN).toList();
        }
    }
}
