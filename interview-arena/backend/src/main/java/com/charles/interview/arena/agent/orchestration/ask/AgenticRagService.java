package com.charles.interview.arena.agent.orchestration.ask;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import com.charles.interview.arena.agent.rag.service.HybridRetriever;
import com.charles.interview.arena.agent.rag.service.RerankService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Agentic RAG 服务（蓝图 §5.7.3）
 * <p>
 * 八股映射：
 * - 迭代检索：不满足时改写 query 重新检索，最多 3 轮
 * - LLM 判断 isContextSufficient：基于检索结果是否足以回答用户问题
 * - Query Rewrite：原 query 检索结果不够时，LLM 改写 query 再检索
 * <p>
 * 与普通 RAG 区别：
 * - 普通 RAG：检索一次 -> 生成
 * - Agentic RAG：检索 -> 判断 -> 改写 -> 再检索 -> 再判断 -> ... -> 生成（直到够用或超 3 轮）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgenticRagService {

    private final ChatClient chatClient;
    private final HybridRetriever hybridRetriever;
    private final RerankService rerankService;

    /** 最大迭代轮次 */
    private static final int MAX_ITERATIONS = 3;
    /** 每轮检索 Top-K */
    private static final int TOP_K = 5;

    private static final String JUDGE_PROMPT = """
            你是 RAG 上下文评估器。请判断以下检索到的资料是否足以回答用户问题。

            【用户问题】
            %s

            【检索到的资料】
            %s

            【输出要求】
            严格输出 JSON：{"sufficient": true/false, "reason": "简短原因"}
            只输出 JSON，不要其他文字
            """;

    private static final String REWRITE_PROMPT = """
            你是 Query 改写器。当前 query 检索结果不够，请改写 query 提升检索效果。

            【原 query】
            %s

            【已有检索资料（不够）】
            %s

            【改写要求】
            - 同义词替换
            - 加上下文关键词
            - 只输出改写后的 query，不要其他文字
            """;

    /**
     * Agentic RAG 迭代检索
     * <p>
     * 流程：
     * 1. 用原 query 检索 -> rerank Top-K
     * 2. LLM 判断是否够用
     * 3. 不够用 -> LLM 改写 query -> 回到步骤 1
     * 4. 够用 或 达到 MAX_ITERATIONS -> 返回最终检索结果
     *
     * @param query 用户原始 query
     * @return 最终用于生成的文档列表
     */
    public List<Document> retrieveIteratively(String query) {
        String currentQuery = query;
        List<Document> accumulated = new ArrayList<>();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            log.info("Agentic RAG 第 {}/{} 轮：query='{}'", i + 1, MAX_ITERATIONS, currentQuery);

            // 1. 检索 + rerank
            List<Document> candidates = hybridRetriever.retrieve(currentQuery);
            List<Document> topDocs = rerankService.rerank(currentQuery, candidates, TOP_K);
            accumulated.addAll(topDocs);

            // 2. LLM 判断是否够用
            boolean sufficient = isContextSufficient(query, topDocs);
            if (sufficient) {
                log.info("Agentic RAG 第 {} 轮够用，停止迭代", i + 1);
                break;
            }

            // 3. 不够用 -> 改写 query 进入下一轮
            if (i < MAX_ITERATIONS - 1) {
                currentQuery = rewriteQuery(query, topDocs);
                log.info("Agentic RAG query 改写：'{}' -> '{}'", query, currentQuery);
            }
        }

        return accumulated;
    }

    /**
     * LLM 判断检索结果是否足以回答用户问题
     */
    private boolean isContextSufficient(String query, List<Document> docs) {
        if (docs == null || docs.isEmpty()) return false;
        try {
            String context = buildContext(docs);
            String prompt = String.format(JUDGE_PROMPT, query, context);
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            if (response == null) return false;
            String cleaned = response.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
            }
            // 简单解析：找 "sufficient": true/false
            return cleaned.contains("\"sufficient\": true") || cleaned.contains("\"sufficient\":true");
        } catch (Exception e) {
            log.warn("isContextSufficient 失败，默认 false：{}", e.getMessage());
            return false;
        }
    }

    /**
     * LLM 改写 query
     */
    private String rewriteQuery(String originalQuery, List<Document> docs) {
        try {
            String context = buildContext(docs);
            String prompt = String.format(REWRITE_PROMPT, originalQuery, context);
            String rewritten = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();
            return rewritten != null ? rewritten.trim() : originalQuery;
        } catch (Exception e) {
            log.warn("rewriteQuery 失败，沿用原 query：{}", e.getMessage());
            return originalQuery;
        }
    }

    private String buildContext(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            sb.append("--- 资料 ").append(i + 1).append(" ---\n");
            sb.append(docs.get(i).getText()).append("\n\n");
        }
        return sb.toString();
    }
}
