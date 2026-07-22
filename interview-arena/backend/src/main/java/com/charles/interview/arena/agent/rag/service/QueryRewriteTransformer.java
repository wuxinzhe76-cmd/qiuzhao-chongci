package com.charles.interview.arena.agent.rag.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 查询改写器（Pre-Retrieval 阶段）
 * <p>
 * 八股映射：
 * - blueprint 5.5 / RAG_UPGRADE_TODO #1：查询改写，纠正术语 + 扩展短 query
 * - Pre-Retrieval：在检索前优化 query，提升召回质量
 * <p>
 * 场景：
 * 1. 术语纠正："双亲委托" → "双亲委派模型"
 * 2. 短 query 扩展："HashMap" → "HashMap 底层原理与扩容机制"
 * 3. 口语化去除："那个 Java 的集合咋回事" → "Java 集合框架原理"
 * <p>
 * 实现：用 ChatClient 调 LLM 改写，失败时降级返回原始 query（不阻塞主流程）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRewriteTransformer {

    private final ChatClient chatClient;

    private static final String REWRITE_PROMPT = """
            你是一个面试题知识库的查询改写助手。请改写用户查询，使其更适合检索：
            1. 纠正术语错误（如"双亲委托"改为"双亲委派模型"）
            2. 扩展过短的查询，补充关键技术词
            3. 去除口语化表达，转为规范的检索语句
            只输出改写后的查询本身，不要加任何解释或引号。

            用户查询：%s
            """;

    /**
     * 改写用户查询。
     * 失败时降级返回原始 query，不阻塞 RAG 主流程。
     *
     * @param query 原始用户查询
     * @return 改写后的查询（失败返回原 query）
     */
    public String rewrite(String query) {
        try {
            String rewritten = chatClient.prompt()
                    .user(REWRITE_PROMPT.formatted(query))
                    .call()
                    .content();
            if (rewritten != null && !rewritten.isBlank()) {
                rewritten = rewritten.trim().replaceAll("^\"|\"$", "");
                log.info("查询改写：'{}' → '{}'", query, rewritten);
                return rewritten;
            }
        } catch (Exception e) {
            log.warn("查询改写失败，使用原始查询：{}", e.getMessage());
        }
        return query;
    }
}
