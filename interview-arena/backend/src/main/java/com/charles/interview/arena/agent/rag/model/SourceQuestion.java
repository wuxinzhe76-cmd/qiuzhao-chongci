package com.charles.interview.arena.agent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG 引用标注：命中的面试题溯源信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceQuestion {
    /** 题目 ID */
    private Long questionId;
    /** 题目标题 */
    private String title;
}
