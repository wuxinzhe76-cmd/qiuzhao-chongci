package com.charles.interview.arena.agent.rag.model;

import java.util.List;

import lombok.Data;

/**
 * RAG 问答结构化响应
 * <p>
 * 八股映射：
 * - blueprint 5.5：引用标注（溯源），返回命中的 questionId 列表 + 题目标题
 * - Modular RAG：ragChat 返回结构化响应（answer + sourceQuestions），不只是 String
 */
@Data
public class RagChatResponse {

    /** AI 生成的回答 */
    private String answer;

    /** 引用标注：命中的面试题列表（前端展示"参考题目"） */
    private List<SourceQuestion> sourceQuestions;

    /** 是否命中语义缓存 */
    private boolean cacheHit;
}
