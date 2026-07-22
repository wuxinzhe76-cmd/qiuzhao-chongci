package com.charles.interview.arena.agent.rag.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Lost-in-the-middle 文档重排器（Post-Retrieval 阶段）
 * <p>
 * 八股映射：
 * - RAG_UPGRADE_TODO #8：最相关文档放 Prompt 首尾，防止 LLM 忽略中间内容
 * - 论文 "Lost in the Middle"（Liu et al. 2023）：LLM 对 Prompt 开头和结尾的信息关注度高于中间
 * <p>
 * 重排策略：
 * 输入（按相关性降序）：[d1, d2, d3, d4, d5]
 * 输出：[d1, d3, d5, d4, d2]
 * - d1（最相关）放首位
 * - d2（次相关）放末位
 * - d3 放第二位，d4 放倒数第二位，d5 放中间
 * <p>
 * 算法：偶数索引（0,2,4...）正序放前半，奇数索引（1,3...）倒序放后半
 */
@Slf4j
@Component
public class LostInTheMiddleRearranger {

    /**
     * 重排文档，使最相关的文档位于 Prompt 首尾。
     *
     * @param docs 已按相关性降序排序的文档列表
     * @return 重排后的文档列表
     */
    public List<Document> rearrange(List<Document> docs) {
        if (docs == null || docs.size() <= 2) {
            return docs;
        }
        List<Document> head = new ArrayList<>();
        List<Document> tail = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            if (i % 2 == 0) {
                head.add(docs.get(i));
            } else {
                tail.add(docs.get(i));
            }
        }
        // tail 倒序，使次相关文档靠近末尾
        Collections.reverse(tail);
        head.addAll(tail);
        log.info("Lost-in-the-middle 重排：{} 条文档 → 首尾分布", docs.size());
        return head;
    }
}
