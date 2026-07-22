package com.charles.interview.arena.agent.rag.event;

import com.charles.interview.arena.model.entity.Question;

/**
 * 题目变更事件（用于 RAG 增量入库）
 * <p>
 * QuestionServiceImpl 在 add/update/delete 后发布此事件，
 * RagService 监听做 Milvus 向量库 + Elasticsearch 倒排索引的增量同步。
 * <p>
 * 八股映射：
 * - blueprint 5.5：增量入库，题目增删改时同步更新 Milvus + ES
 * - 解耦：事件机制避免 QuestionService ↔ RagService 循环依赖
 */
public record QuestionChangedEvent(Action action, Question question) {

    public enum Action { ADD, UPDATE, DELETE }
}
