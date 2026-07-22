package com.charles.interview.arena.agent.rag.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.rag.model.QuestionEsDoc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ES 搜索栏 autocomplete 服务
 * <p>
 * 八股映射：
 * - RAG_UPGRADE_TODO §二：搜索栏前缀/后缀匹配，不是 RAG 场景，是题目搜索 autocomplete
 * - 用 ES match_phrase_prefix 查询：对 title 字段做前缀匹配，返回下拉建议
 * <p>
 * 场景：用户输入 "Java" → 下拉显示 "Java 的特性"、"Java 面向对象"、"Java 集合" 等
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionSearchService {

    private final ElasticsearchOperations elasticsearchOperations;

    /**
     * 题目标题前缀匹配（autocomplete）
     *
     * @param prefix 用户输入的前缀
     * @param limit  返回数量上限
     * @return 匹配的题目标题列表
     */
    public List<String> suggest(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) {
            return List.of();
        }
        // match_phrase_prefix：对 title 做短语前缀匹配（IK 分词后最后一个 token 做前缀）
        Query searchQuery = NativeQuery.builder()
                .withQuery(q -> q.matchPhrasePrefix(m -> m
                        .field("title")
                        .query(prefix)))
                .withMaxResults(limit)
                .build();

        SearchHits<QuestionEsDoc> hits = elasticsearchOperations.search(searchQuery, QuestionEsDoc.class);

        List<String> suggestions = new ArrayList<>();
        hits.forEach(hit -> suggestions.add(hit.getContent().getTitle()));
        log.info("Autocomplete 建议：prefix='{}', 命中 {} 条", prefix, suggestions.size());
        return suggestions;
    }
}
