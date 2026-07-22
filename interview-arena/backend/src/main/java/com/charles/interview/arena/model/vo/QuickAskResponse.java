package com.charles.interview.arena.model.vo;

import java.util.List;

import com.charles.interview.arena.agent.rag.model.SourceQuestion;

import lombok.Data;

/**
 * Quick Ask 响应 VO（蓝图 §5.5.6）
 * <p>
 * RAG + MCP 联网混合流程的结构化响应。
 */
@Data
public class QuickAskResponse {

    /** 综合回答（题库答案 + 联网参考） */
    private String answer;

    /** 命中的题库题目（引用溯源） */
    private List<SourceQuestion> sourceQuestions;

    /** 联网搜索的 URL 列表（标注"参考"） */
    private List<String> webSources;

    /** 是否命中语义缓存 */
    private Boolean cacheHit;

    /** 是否可入库（true=用户可选择将此答案存入个人知识库） */
    private Boolean canSaveToKb;
}
