package com.charles.interview.arena.agent.tool.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.guardrail.tool.ToolPermission;
import com.charles.interview.arena.agent.rag.service.HybridRetriever;
import com.charles.interview.arena.agent.rag.service.RerankService;
import com.charles.interview.arena.agent.tool.api.Tool;
import com.charles.interview.arena.agent.tool.api.ToolInput;
import com.charles.interview.arena.agent.tool.api.ToolResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 题库知识检索工具（RAG 封装为模型可调用工具）
 * <p>
 * 链路：HybridRetriever（向量 + BM25 + RRF 融合）→ RerankService 精排 Top-5。
 * 返回结构含 questionId/title，编排层可从轨迹中提取引用来源做溯源展示。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrieveKnowledgeTool implements Tool {

    private final HybridRetriever hybridRetriever;
    private final RerankService rerankService;

    private static final int TOP_K = 5;

    @Override
    public String getName() { return "retrieveKnowledge"; }

    @Override
    public String getDescription() { return "检索面试题库（权威知识源，混合检索+精排，返回最相关的题目与答案内容）"; }

    @Override
    public String getInputSchema() {
        return "{\"query\": \"string, 检索关键词或问题\"}";
    }

    @Override
    public ToolPermission.Level getPermissionLevel() { return ToolPermission.Level.READ; }

    @Override
    public ToolResult execute(ToolInput input) {
        String query = input.getString("query");
        if (query == null || query.isBlank()) {
            return ToolResult.failure("缺少必要参数: query");
        }

        try {
            List<Document> candidates = hybridRetriever.retrieve(query);
            List<Document> topDocs = rerankService.rerank(query, candidates, TOP_K);

            List<Map<String, Object>> docs = new ArrayList<>();
            for (Document doc : topDocs) {
                Map<String, Object> item = new HashMap<>();
                item.put("questionId", doc.getMetadata().get("questionId"));
                item.put("title", doc.getMetadata().getOrDefault("title", ""));
                item.put("content", doc.getText());
                docs.add(item);
            }

            log.info("题库检索: query='{}', 命中 {} 条", query, docs.size());
            return ToolResult.success(docs);
        } catch (Exception e) {
            log.warn("题库检索失败: query='{}', err={}", query, e.getMessage());
            return ToolResult.failure("题库检索失败: " + e.getMessage());
        }
    }
}
