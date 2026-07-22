package com.charles.interview.arena.agent.rag.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

/**
 * 文档去重器（Post-Retrieval 阶段）
 * <p>
 * 八股映射：
 * - RAG_UPGRADE_TODO #6：RRF 融合后可能有内容重复的文档，按文本相似度去重
 * - Post-Retrieval：检索后、Prompt 拼接前去重，避免上下文冗余
 * <p>
 * 去重策略：
 * 1. 优先按 metadata.questionId 去重（同一题目只保留一份）
 * 2. 无 questionId 时按文本前缀去重（近似重复检测）
 */
@Component
public class DocumentDeduplicator {

    private static final int PREFIX_LENGTH = 50;

    /**
     * 去重，保留首次出现的文档（相关性更高，因为 rerank 已排序）
     *
     * @param docs 已排序的文档列表（相关性降序）
     * @return 去重后的文档列表
     */
    public List<Document> deduplicate(List<Document> docs) {
        if (docs == null || docs.size() <= 1) {
            return docs;
        }
        Set<String> seenKeys = new HashSet<>();
        return docs.stream()
                .filter(doc -> seenKeys.add(buildDedupKey(doc)))
                .toList();
    }

    private String buildDedupKey(Document doc) {
        Object qid = doc.getMetadata().get("questionId");
        if (qid != null) {
            return "qid:" + qid;
        }
        String text = doc.getText();
        int len = Math.min(PREFIX_LENGTH, text.length());
        return "txt:" + text.substring(0, len);
    }
}
