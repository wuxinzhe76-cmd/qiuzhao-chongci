package com.charles.interview.arena.agent.rag.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.rag.model.QuestionEsDoc;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * BM25 关键词检索器（基于 Elasticsearch 倒排索引）
 * <p>
 * 八股映射：
 * - #6 BM25 是打分函数不是索引结构，倒排索引才是
 * - BM25 公式：score = IDF(q) * (f(q,d) * (k1+1)) / (f(q,d) +
 * k1*(1-b+b*|d|/avgdl))
 * - 与向量检索正交互补：向量擅长语义相似，BM25 擅长精确关键词匹配
 * <p>
 * 场景：用户问"HashMap 的 put 方法"，向量检索可能返回"ConcurrentHashMap 的 put"（语义相近），
 * BM25 能精确命中"HashMap put"（关键词匹配）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BM25Retriever {

        private final ElasticsearchOperations elasticsearchOperations;

        /**
         * BM25 关键词检索（无元数据过滤）
         */
        public List<Document> retrieve(String query, int topK) {
                return retrieve(query, topK, null);
        }

        /**
         * BM25 关键词检索（支持 category 元数据过滤）
         *
         * @param query            用户提问
         * @param topK             返回数量
         * @param filterExpression Spring AI 过滤表达式（如 "category == 'Java基础'"），null 不过滤
         * @return 检索到的文档列表（按 BM25 打分降序）
         */
        public List<Document> retrieve(String query, int topK, String filterExpression) {
                String category = extractCategory(filterExpression);
                // bool 查询：must=multi_match（BM25 打分），filter=term（category 过滤不打分）
                Query searchQuery = NativeQuery.builder()
                                .withQuery(q -> q.bool(b -> {
                                        b.must(m -> m.multiMatch(mm -> mm
                                                        .query(query)
                                                        .fields("title^3", "content^2", "answer^1")
                                                        .type(TextQueryType.BestFields)));
                                        if (category != null) {
                                                b.filter(f -> f.term(t -> t
                                                                .field("category").value(FieldValue.of(category))));
                                        }
                                        return b;
                                }))
                                .withMaxResults(topK)
                                .build();

                SearchHits<QuestionEsDoc> hits = elasticsearchOperations.search(searchQuery,
                                QuestionEsDoc.class);

                List<Document> documents = new ArrayList<>();
                hits.forEach(hit -> {
                        var esDoc = hit.getContent();
                        String content = "题目：" + esDoc.getTitle()
                                        + "\n内容：" + esDoc.getContent()
                                        + "\n答案：" + (esDoc.getAnswer() != null ? esDoc.getAnswer() : "暂无答案");
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("questionId", esDoc.getQuestionId());
                        metadata.put("title", esDoc.getTitle());
                        metadata.put("category", esDoc.getCategory());
                        metadata.put("difficulty", esDoc.getDifficulty());
                        Document doc = new Document(content, metadata);
                        documents.add(doc);
                });

                log.info("BM25 检索完成：query='{}', filter='{}', 命中 {} 条", query, filterExpression,
                                documents.size());
                return documents;
        }

        /**
         * 从 Spring AI filterExpression（如 "category == 'Java基础'"）提取 category 值。
         * 仅支持 category 单条件，其他条件忽略（BM25 侧降级为不过滤）。
         */
        private String extractCategory(String filterExpression) {
                if (filterExpression == null || filterExpression.isBlank()) {
                        return null;
                }
                int idx = filterExpression.indexOf("category");
                if (idx < 0) return null;
                int eq = filterExpression.indexOf("==", idx);
                if (eq < 0) return null;
                int start = filterExpression.indexOf("'", eq);
                int end = filterExpression.indexOf("'", start + 1);
                if (start < 0 || end < 0) return null;
                return filterExpression.substring(start + 1, end);
        }
}
