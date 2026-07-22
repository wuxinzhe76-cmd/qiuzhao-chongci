package com.charles.interview.arena.agent.perception.model;

/**
 * 意图枚举(感知层识别,编排层路由)
 */
public enum Intent {
    START_INTERVIEW,      // 开始面试
    ANSWER_QUESTION,      // 提交面试回答
    END_INTERVIEW,        // 结束面试
    KNOWLEDGE_QUERY,      // 知识题查询(原 RAG_ONLY)
    MEMORY_QUERY,         // 历史追问(原 MEMORY_ONLY)
    HYBRID_QUERY,         // 混合查询(原 RAG_AND_MEMORY)
    SAVE_TO_KB,           // 保存到知识库
    UNKNOWN               // 未知意图,交编排层兜底
}
