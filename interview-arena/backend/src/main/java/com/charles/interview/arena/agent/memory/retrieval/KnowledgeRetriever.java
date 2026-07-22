package com.charles.interview.arena.agent.memory.retrieval;

import java.util.List;

import org.springframework.ai.document.Document;

/**
 * 外部知识检索接口（依赖倒置，DIP）
 * <p>
 * Memory 层的多策略检索需要一路「关键词/知识库检索」，但知识库检索能力由 RAG 层提供。
 * 为避免 memory 包直接依赖 rag 包（形成 memory ↔ rag 双向耦合），
 * 在 memory 侧定义此接口，由 RAG 层的 HybridRetriever 实现。
 * <p>
 * 依赖方向：memory.retrieval → KnowledgeRetriever（接口） ← rag.service.HybridRetriever（实现）
 */
public interface KnowledgeRetriever {

    /**
     * 按查询检索知识库文档
     *
     * @param query 用户查询
     * @return 相关文档列表（按相关度降序）
     */
    List<Document> retrieve(String query);
}
