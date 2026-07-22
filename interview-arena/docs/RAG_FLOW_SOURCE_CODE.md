# RAG 模块完整源码流程详解

> 本文档按数据流顺序，逐文件、逐方法展示 interview-arena RAG 模块的完整源码与解析。
> 适合面试前复习、代码走读、后续维护参考。

---

## 一、总体架构

```
管理员调 /rag/import → ETL 全量导入 MySQL → Milvus + ES

用户调 /rag/chat
  ↓
[1] 语义缓存（Redis）──── hit → 直接返回
  ↓ miss
[2] 查询改写（LLM）
  ↓
[3] 混合检索（Milvus 向量 Top-20 + ES BM25 Top-20 → RRF 融合 Top-10）
  ↓
[4] Rerank（DashScope Cross-Encoder Top-5）
  ↓
[5] 去重 + Lost-in-the-middle 重排
  ↓
[6] 引用标注 + 拼接中文 Prompt
  ↓
[7] 通义千问生成
  ↓
[8] 写缓存 + 返回

管理员增删改题目 → Spring Event → 增量同步 Milvus + ES

用户搜索栏输入 → GET /rag/suggest → ES match_phrase_prefix
```

---

## 二、源码文件索引

| # | 文件 | 作用 | 阶段 |
|---|------|------|------|
| 1 | `SemanticCache.java` | 语义缓存（cosine > 0.95） | 缓存 |
| 2 | `QueryRewriteTransformer.java` | 查询改写 | Pre-Retrieval |
| 3 | `BM25Retriever.java` | ES BM25 关键词检索 | Retrieval |
| 4 | `HybridRetriever.java` | 向量+BM25 混合检索 + RRF 融合 | Retrieval |
| 5 | `RerankService.java` | Cross-Encoder 精排 | Retrieval |
| 6 | `DocumentDeduplicator.java` | 文档去重 | Post-Retrieval |
| 7 | `LostInTheMiddleRearranger.java` | 文档重排 | Post-Retrieval |
| 8 | `QuestionChangedEvent.java` | 题目变更事件定义 | 增量入库 |
| 9 | `QuestionServiceImpl.java` | 事件发布端 | 增量入库 |
| 10 | `RagService.java` | 核心：ETL + ragChat DAG 编排 + 增量入库监听 | 核心 |
| 11 | `QuestionSearchService.java` | ES autocomplete | 搜索栏 |
| 12 | `RagController.java` | 三个 HTTP 接口 | 入口 |
| 13 | `RagChatResponse.java` | 结构化响应 DTO | 模型 |
| 14 | `SourceQuestion.java` | 引用标注 DTO | 模型 |
| 15 | `QuestionEsDoc.java` | ES 文档实体 | 模型 |

---

## 三、逐文件源码 + 解析

---

### 文件 1：SemanticCache.java — 语义缓存

**路径**：`rag/service/SemanticCache.java`
**作用**：用户提问时，先用向量相似度查 Redis 缓存。语义相同的问题直接返回，跳过整个 RAG 链路。

**依赖注入**：
```java
private final EmbeddingModel embeddingModel;       // DashScope text-embedding-v3，文本→1024维向量
private final StringRedisTemplate redisTemplate;   // Redis 操作客户端
```

**常量**：
```java
private static final String CACHE_PREFIX = "rag:sem_cache:";     // Redis key 前缀
private static final double SIMILARITY_THRESHOLD = 0.95;        // cosine 相似度阈值
private static final Duration CACHE_TTL = Duration.ofHours(1);  // 缓存 1 小时过期
```

**完整源码**：
```java
package com.charles.interview.arena.rag.service;

import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticCache {

    private final EmbeddingModel embeddingModel;
    private final StringRedisTemplate redisTemplate;

    private static final String CACHE_PREFIX = "rag:sem_cache:";
    private static final double SIMILARITY_THRESHOLD = 0.95;
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    /**
     * 查询语义缓存
     * 1. 把 query 转 1024 维向量
     * 2. 遍历 Redis 所有缓存 key，逐个算 cosine 相似度
     * 3. >= 0.95 命中，返回缓存的答案；未命中返回 null
     */
    public String get(String query) {
        float[] queryEmbedding = embeddingModel.embed(query);

        Set<String> keys = redisTemplate.keys(CACHE_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return null;
        }

        for (String key : keys) {
            String storedAnswer = redisTemplate.opsForValue().get(key);
            if (storedAnswer == null) continue;

            // key 格式：rag:sem_cache:{base64_embedding}，key 本身存了原始 query 的向量
            String embeddingBase64 = key.substring(CACHE_PREFIX.length());
            float[] cachedEmbedding = decodeBase64ToFloatArray(embeddingBase64);

            double similarity = cosineSimilarity(queryEmbedding, cachedEmbedding);
            if (similarity >= SIMILARITY_THRESHOLD) {
                log.info("语义缓存命中：query='{}', similarity={}", query, similarity);
                return storedAnswer;
            }
        }
        return null;
    }

    /**
     * 写入语义缓存
     * key = rag:sem_cache:{base64(query_embedding)}
     * value = answer，TTL = 1h
     */
    public void put(String query, String answer) {
        float[] embedding = embeddingModel.embed(query);
        String embeddingBase64 = encodeFloatArrayToBase64(embedding);
        String key = CACHE_PREFIX + embeddingBase64;
        redisTemplate.opsForValue().set(key, answer, CACHE_TTL);  // TTL=1h
        log.info("语义缓存写入：query='{}'", query);
    }

    // ---- 工具方法：cosine 相似度 + Base64 编解码 ----

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private String encodeFloatArrayToBase64(float[] arr) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(arr.length * 4);
        for (float f : arr) buffer.putFloat(f);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private float[] decodeBase64ToFloatArray(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
        float[] arr = new float[bytes.length / 4];
        for (int i = 0; i < arr.length; i++) arr[i] = buffer.getFloat();
        return arr;
    }
}
```

**解析**：
- key 格式 `rag:sem_cache:{base64向量}`，key 本身存了 query 的向量，不需要额外存
- 阈值 0.95 偏保守：宁可漏命中也不要假阳性（返回不相关答案）
- 当前线性扫描所有 key，适合 < 1000 条；生产环境可用 Redis Stack VECTOR 类型

---

### 文件 2：QueryRewriteTransformer.java — 查询改写

**路径**：`rag/service/QueryRewriteTransformer.java`
**作用**：检索前用 LLM 改写用户查询，提升召回质量。Pre-Retrieval 阶段。

**完整源码**：
```java
package com.charles.interview.arena.rag.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRewriteTransformer {

    private final ChatClient chatClient;

    private static final String REWRITE_PROMPT = """
            你是一个面试题知识库的查询改写助手。请改写用户查询，使其更适合检索：
            1. 纠正术语错误（如"双亲委托"改为"双亲委派模型"）
            2. 扩展过短的查询，补充关键技术词
            3. 去除口语化表达，转为规范的检索语句
            只输出改写后的查询本身，不要加任何解释或引号。

            用户查询：%s
            """;

    /**
     * 改写用户查询。
     * 失败时降级返回原始 query，不阻塞 RAG 主流程。
     */
    public String rewrite(String query) {
        try {
            String rewritten = chatClient.prompt()
                    .user(REWRITE_PROMPT.formatted(query))  // 把原始 query 填入模板
                    .call()
                    .content();                            // 拿到 LLM 返回的改写文本

            if (rewritten != null && !rewritten.isBlank()) {
                // 去掉 LLM 可能加的首尾引号
                rewritten = rewritten.trim().replaceAll("^\"|\"$", "");
                log.info("查询改写：'{}' → '{}'", query, rewritten);
                return rewritten;
            }
        } catch (Exception e) {
            log.warn("查询改写失败，使用原始查询：{}", e.getMessage());
        }
        return query;  // 降级：任何异常都返回原始 query
    }
}
```

**解析**：
- `replaceAll("^\"|\"$", "")`：正则匹配开头或结尾的引号，替换成空
- 降级逻辑：LLM 调用失败时返回原始 query，不阻塞主流程
- 改写后的 query 只用于检索，Prompt 里放的是用户原始问题

---

### 文件 3：BM25Retriever.java — BM25 关键词检索

**路径**：`rag/service/BM25Retriever.java`
**作用**：用 Elasticsearch 倒排索引做关键词检索，返回 Top-N 文档。HybridRetriever 的两路之一。

**完整源码**：
```java
package com.charles.interview.arena.rag.service;

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

import com.charles.interview.arena.rag.model.QuestionEsDoc;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
     */
    public List<Document> retrieve(String query, int topK, String filterExpression) {
        // 第 1 步：从 filterExpression 解析 category 值
        String category = extractCategory(filterExpression);

        // 第 2 步：构造 ES bool 查询
        // must=multi_match（BM25 打分），filter=term（category 过滤不打分）
        Query searchQuery = NativeQuery.builder()
                .withQuery(q -> q.bool(b -> {
                    b.must(m -> m.multiMatch(mm -> mm
                            .query(query)
                            .fields("title^3", "content^2", "answer^1")  // title 权重×3
                            .type(TextQueryType.BestFields)));             // 取最佳匹配字段
                    if (category != null) {
                        b.filter(f -> f.term(t -> t
                                .field("category").value(FieldValue.of(category))));  // term 精确匹配
                    }
                    return b;
                }))
                .withMaxResults(topK)
                .build();

        // 第 3 步：执行查询 + 转成 Document（带 metadata）
        SearchHits<QuestionEsDoc> hits = elasticsearchOperations.search(searchQuery, QuestionEsDoc.class);

        List<Document> documents = new ArrayList<>();
        hits.forEach(hit -> {
            var esDoc = hit.getContent();
            String content = "题目：" + esDoc.getTitle()
                    + "\n内容：" + esDoc.getContent()
                    + "\n答案：" + (esDoc.getAnswer() != null ? esDoc.getAnswer() : "暂无答案");

            // Document 带 metadata，后续引用标注和去重能用
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("questionId", esDoc.getQuestionId());
            metadata.put("title", esDoc.getTitle());
            metadata.put("category", esDoc.getCategory());
            metadata.put("difficulty", esDoc.getDifficulty());
            Document doc = new Document(content, metadata);
            documents.add(doc);
        });

        log.info("BM25 检索完成：query='{}', filter='{}', 命中 {} 条", query, filterExpression, documents.size());
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
```

**解析**：
- `must`（multi_match）参与打分，`filter`（term）只过滤不打分
- `title^3`：title 字段分数乘 3，因为题目标题最能代表主题
- `BestFields`：取匹配分数最高的字段，而不是所有字段分数相加
- 底层翻译成 ES DSL JSON：
  ```json
  {
    "query": {
      "bool": {
        "must": [{
          "multi_match": {
            "query": "HashMap put 方法",
            "fields": ["title^3", "content^2", "answer^1"],
            "type": "best_fields"
          }
        }],
        "filter": [{"term": {"category": "Java基础"}}]
      }
    }
  }
  ```

---

### 文件 4：HybridRetriever.java — 混合检索 + RRF 融合

**路径**：`rag/service/HybridRetriever.java`
**作用**：同时调向量检索（Milvus）和 BM25 检索（ES），两路各取 Top-20，用 RRF 公式融合成 Top-10。

**完整源码**：
```java
package com.charles.interview.arena.rag.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever {

    private final VectorStore vectorStore;        // Milvus
    private final BM25Retriever bm25Retriever;    // ES

    private static final int VECTOR_TOP_K = 20;
    private static final int BM25_TOP_K = 20;
    private static final int FINAL_TOP_K = 10;
    private static final double SIMILARITY_THRESHOLD = 0.5;
    private static final int RRF_K = 60;  // RRF 平滑参数（Google 论文经验值）

    /**
     * 混合检索（无过滤）
     */
    public List<Document> retrieve(String query) {
        return retrieve(query, null);
    }

    /**
     * 混合检索 + 元数据过滤
     */
    public List<Document> retrieve(String query, String filterExpression) {
        // 1. 向量检索 Top-20（Milvus 语义相似，支持 metadata 过滤）
        SearchRequest.Builder searchBuilder = SearchRequest.builder()
                .query(query)
                .topK(VECTOR_TOP_K)
                .similarityThreshold(SIMILARITY_THRESHOLD);
        if (filterExpression != null && !filterExpression.isBlank()) {
            searchBuilder.filterExpression(filterExpression);  // Milvus metadata 过滤
        }
        List<Document> vectorResults = vectorStore.similaritySearch(searchBuilder.build());
        log.info("向量检索命中 {} 条（filter={}）", vectorResults.size(), filterExpression);

        // 2. BM25 检索 Top-20（ES 关键词匹配，支持 category 过滤）
        List<Document> bm25Results = bm25Retriever.retrieve(query, BM25_TOP_K, filterExpression);
        log.info("BM25 检索命中 {} 条（filter={}）", bm25Results.size(), filterExpression);

        // 3. RRF 融合 → Top-10
        List<Document> fused = rrfFuse(vectorResults, bm25Results, FINAL_TOP_K);
        log.info("RRF 融合后 Top-{} 条", fused.size());
        return fused;
    }

    /**
     * RRF（Reciprocal Rank Fusion）排名融合
     *
     * 公式：score(d) = Σ 1/(k + rank_i(d))
     * - k=60：平滑参数，防止排名第 1 的文档权重过大
     * - rank 从 1 开始
     * - 同一文档在两个检索结果中都出现 → 分数累加
     *
     * 例子：向量第 1 名 score=1/61=0.0164，BM25 第 1 名 score=1/61=0.0164
     *       两路都命中 → 累加 0.0328，分数最高
     */
    private List<Document> rrfFuse(List<Document> vectorResults, List<Document> bm25Results, int topK) {
        Map<String, Document> docMap = new HashMap<>();   // key=文档文本，value=文档对象
        Map<String, Double> scoreMap = new HashMap<>();    // key=文档文本，value=RRF 分数

        // 向量检索结果排名（rank 从 1 开始）
        for (int i = 0; i < vectorResults.size(); i++) {
            String key = vectorResults.get(i).getText();
            int rank = i + 1;
            double score = 1.0 / (RRF_K + rank);
            docMap.putIfAbsent(key, vectorResults.get(i));
            scoreMap.merge(key, score, Double::sum);  // 分数累加
        }

        // BM25 检索结果排名
        for (int i = 0; i < bm25Results.size(); i++) {
            String key = bm25Results.get(i).getText();
            int rank = i + 1;
            double score = 1.0 / (RRF_K + rank);
            docMap.putIfAbsent(key, bm25Results.get(i));
            scoreMap.merge(key, score, Double::sum);
        }

        // 按分数降序，取 Top-K
        List<Map.Entry<String, Double>> sorted = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .toList();

        List<Document> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : sorted) {
            result.add(docMap.get(entry.getKey()));
        }
        return result;
    }
}
```

**解析**：
- 向量和 BM25 分数量纲不同（向量 0~1 cosine，BM25 无上限 TF-IDF 衍生），不能直接加
- RRF 是排名融合非分数融合：只看每路的 rank 排名，不看具体分数
- k=60 来自 Google 论文，防止第 1 名权重过大（赢家通吃）
- 两路都命中的文档分数累加 → 最相关

---

### 文件 5：RerankService.java — Cross-Encoder 精排

**路径**：`rag/service/RerankService.java`
**作用**：调 DashScope gte-rerank 模型，对 Top-10 文档做 Cross-Encoder 精排，选 Top-5。

**完整源码**：
```java
package com.charles.interview.arena.rag.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class RerankService {

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    private static final String DASHSCOPE_RERANK_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
    private static final String RERANK_MODEL = "gte-rerank";

    /**
     * 对检索结果重排序
     *
     * @param query     用户提问
     * @param documents 候选文档（HybridRetriever 输出的 Top-10）
     * @param topN      重排序后返回数量（如 5）
     */
    public List<Document> rerank(String query, List<Document> documents, int topN) {
        if (documents.isEmpty()) {
            return documents;
        }

        // 第 1 步：构造请求体
        List<String> docTexts = documents.stream().map(Document::getText).toList();

        Map<String, Object> requestBody = Map.of(
                "model", RERANK_MODEL,
                "input", Map.of(
                        "query", query,
                        "documents", docTexts),           // 10 个文档的纯文本
                "parameters", Map.of(
                        "top_n", topN,                     // 返回 Top-5
                        "return_documents", false)         // 不返回文档原文，只返回索引+分数
        );

        try {
            // 第 2 步：调用 DashScope API
            String response = webClientBuilder.build()
                    .post()
                    .uri(DASHSCOPE_RERANK_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();                              // 同步等待响应

            // 第 3 步：解析响应，映射回原始 Document
            // API 返回格式：{ output: { results: [{ index, relevance_score }] } }
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("output").path("results");

            List<Document> reranked = new ArrayList<>();
            for (JsonNode result : results) {
                int index = result.path("index").asInt();  // 原始文档数组的索引
                if (index < documents.size()) {
                    reranked.add(documents.get(index));     // 用索引取回原始 Document（带 metadata）
                }
            }

            log.info("Rerank 完成：{} 条 → {} 条", documents.size(), reranked.size());
            return reranked;

        } catch (Exception e) {
            log.error("Rerank 调用失败，返回原始顺序前 {} 条: {}", topN, e.getMessage());
            // 降级：API 失败时取前 topN 条
            return documents.stream().limit(topN).toList();
        }
    }
}
```

**解析**：
- Bi-Encoder（召回阶段）vs Cross-Encoder（精排阶段）：
  - Bi-Encoder 分别编码 query 和 doc 再算相似度，快但粗
  - Cross-Encoder 把 query 和 doc 拼在一起送入模型，逐对打分，准但慢
- 为什么不能全用 Cross-Encoder：100 万文档要打 100 万次分，性能扛不住
- 两阶段架构：召回 100→10（Bi-Encoder），精排 10→5（Cross-Encoder）
- API 返回 `index` 是输入文档数组的下标，用它取回原始 Document（保留 metadata）

---

### 文件 6：DocumentDeduplicator.java — 文档去重

**路径**：`rag/service/DocumentDeduplicator.java`
**作用**：按 questionId 去重，只保留首次出现的（Rerank 已排序，首次=相关性最高）。

**完整源码**：
```java
package com.charles.interview.arena.rag.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

@Component
public class DocumentDeduplicator {

    private static final int PREFIX_LENGTH = 50;  // 无 questionId 时按文本前 50 字符去重

    /**
     * 去重，保留首次出现的文档（相关性更高，因为 rerank 已排序）
     */
    public List<Document> deduplicate(List<Document> docs) {
        if (docs == null || docs.size() <= 1) {
            return docs;
        }
        Set<String> seenKeys = new HashSet<>();
        // Set.add() 返回 true=首次出现（保留），false=重复（filter 掉）
        return docs.stream()
                .filter(doc -> seenKeys.add(buildDedupKey(doc)))
                .toList();
    }

    /**
     * 去重 key：优先 questionId，无 qid 按文本前 50 字符
     */
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
```

**解析**：
- 利用 `Set.add()` 返回值实现 filter 去重——Java Stream 小技巧
- 两套去重策略：有 questionId 用 `"qid:123"`，无 qid 用文本前 50 字符

---

### 文件 7：LostInTheMiddleRearranger.java — 文档重排

**路径**：`rag/service/LostInTheMiddleRearranger.java`
**作用**：把最相关的文档放到 Prompt 的首尾位置，防止 LLM 忽略中间内容。

**完整源码**：
```java
package com.charles.interview.arena.rag.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class LostInTheMiddleRearranger {

    /**
     * 重排文档，使最相关的文档位于 Prompt 首尾。
     *
     * 算法：偶数索引（0,2,4...）正序放前半，奇数索引（1,3...）倒序放后半
     *
     * 例子：输入 [d1, d2, d3, d4, d5]（按相关性降序）
     *       head = [d1, d3, d5]（偶数索引）
     *       tail = [d2, d4]（奇数索引）
     *       tail 反转 = [d4, d2]
     *       输出 = [d1, d3, d5, d4, d2]
     *       → d1 在首位，d2 在末尾，d5 在中间
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
        Collections.reverse(tail);
        head.addAll(tail);
        log.info("Lost-in-the-middle 重排：{} 条文档 → 首尾分布", docs.size());
        return head;
    }
}
```

**解析**：
- 论文 "Lost in the Middle"（Liu et al. 2023）：LLM 对 Prompt 首尾关注度高于中间
- 最终效果：d1（最相关）在首位，d2（次相关）在末尾，d5（中等相关）在中间

---

### 文件 8：QuestionChangedEvent.java — 题目变更事件定义

**路径**：`rag/event/QuestionChangedEvent.java`
**作用**：Java 21 record，不可变事件对象，包含动作类型 + 题目完整对象。

**完整源码**：
```java
package com.charles.interview.arena.rag.event;

import com.charles.interview.arena.model.entity.Question;

/**
 * 题目变更事件（用于 RAG 增量入库）
 * QuestionServiceImpl 在 add/update/delete 后发布此事件，
 * RagService 监听做 Milvus 向量库 + ES 倒排索引的增量同步。
 */
public record QuestionChangedEvent(Action action, Question question) {

    public enum Action { ADD, UPDATE, DELETE }
}
```

**解析**：
- `record` 自动生成构造器/getter/equals/hashCode，不可变
- `Action` 枚举三种动作

---

### 文件 9：QuestionServiceImpl.java — 事件发布端

**路径**：`service/impl/QuestionServiceImpl.java`
**作用**：题目 CRUD 操作完成后发布 QuestionChangedEvent，触发 RAG 增量入库。

**关键代码（事件发布部分）**：
```java
@Service
@RequiredArgsConstructor
public class QuestionServiceImpl extends ServiceImpl<QuestionMapper, Question> implements QuestionService {

    private final ApplicationEventPublisher eventPublisher;

    // ==================== 新增：save 后发布 ADD 事件 ====================
    @Override
    public Long addQuestion(QuestionAddDTO dto, Long userId) {
        Question question = new Question();
        BeanUtils.copyProperties(dto, question);
        question.setUserId(userId);
        if (question.getType() == null) question.setType("PROGRAMMING");
        if (question.getDifficulty() == null) question.setDifficulty("MEDIUM");
        if (question.getTimeLimit() == null) question.setTimeLimit(1000);
        if (question.getMemoryLimit() == null) question.setMemoryLimit(256);
        boolean saved = this.save(question);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "题目创建失败");
        // 发布事件 → RAG 增量入库
        eventPublisher.publishEvent(new QuestionChangedEvent(Action.ADD, question));
        return question.getId();
    }

    // ==================== 更新：重新查完整对象再发布 UPDATE 事件 ====================
    @Override
    public Boolean updateQuestion(QuestionAddDTO dto) {
        Question question = new Question();
        BeanUtils.copyProperties(dto, question);
        boolean updated = this.updateById(question);
        ThrowUtils.throwIf(!updated, ErrorCode.OPERATION_ERROR, "题目更新失败");
        // updateById 后 question 可能缺字段，重新查完整对象
        Question full = this.getById(question.getId());
        if (full != null) {
            eventPublisher.publishEvent(new QuestionChangedEvent(Action.UPDATE, full));
        }
        return true;
    }

    // ==================== 删除：删除前先查，删除后发布 DELETE 事件 ====================
    @Override
    public Boolean deleteQuestion(Long id) {
        // 删除前先查出完整对象（逻辑删除后 getById 查不到）
        Question question = this.getById(id);
        boolean removed = this.removeById(id);
        ThrowUtils.throwIf(!removed, ErrorCode.OPERATION_ERROR, "题目删除失败");
        if (question != null) {
            eventPublisher.publishEvent(new QuestionChangedEvent(Action.DELETE, question));
        }
        return true;
    }

    // ... 其他方法省略
}
```

**解析**：
- ADD：save 成功后发布，事件在 DB 写入之后
- UPDATE：updateById 后重新 getById 查完整对象（DTO 拷贝的可能缺字段）
- DELETE：removeById 前先 getById（逻辑删除后查不到）
- Spring 事件默认同步：publishEvent 阻塞直到监听器执行完

---

### 文件 10：RagService.java — 核心服务

**路径**：`rag/service/RagService.java`
**作用**：ETL 全量导入 + ragChat 手动 DAG 编排 + 增量入库事件监听。

**完整源码**：
```java
package com.charles.interview.arena.rag.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.event.EventListener;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;

import com.charles.interview.arena.model.entity.Question;
import com.charles.interview.arena.model.entity.QuestionBank;
import com.charles.interview.arena.model.entity.QuestionBankQuestion;
import com.charles.interview.arena.rag.event.QuestionChangedEvent;
import com.charles.interview.arena.rag.model.QuestionEsDoc;
import com.charles.interview.arena.rag.model.RagChatResponse;
import com.charles.interview.arena.rag.model.SourceQuestion;
import com.charles.interview.arena.service.QuestionBankQuestionService;
import com.charles.interview.arena.service.QuestionBankService;
import com.charles.interview.arena.service.QuestionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    // ==================== 依赖注入 ====================
    private final QuestionService questionService;
    private final QuestionBankQuestionService questionBankQuestionService;
    private final QuestionBankService questionBankService;
    private final VectorStore vectorStore;                        // Milvus
    private final ElasticsearchOperations elasticsearchOperations; // ES
    private final ChatClient chatClient;                           // 通义千问
    private final RetrievalAugmentationAdvisor ragAdvisor;         // 未使用（保留兼容）
    private final HybridRetriever hybridRetriever;
    private final RerankService rerankService;
    private final QueryRewriteTransformer queryRewriteTransformer;
    private final DocumentDeduplicator documentDeduplicator;
    private final LostInTheMiddleRearranger lostInTheMiddleRearranger;
    private final SemanticCache semanticCache;

    // ==================== 离线索引（ETL） ====================

    /**
     * ETL 全量导入：MySQL 所有面试题 → Milvus 向量库 + ES 倒排索引
     * 管理员调 /rag/import 触发，用于首次建索引或索引重建
     */
    public int importQuestionsToVectorStore() {
        List<Question> questions = questionService.list();
        Map<Long, String> categoryMap = buildCategoryMap();

        // 构造 Document（带 metadata + ID=questionId）
        List<Document> documents = new ArrayList<>();
        for (Question question : questions) {
            String answer = question.getAnswer() != null ? question.getAnswer() : "暂无答案";
            String contentString = "题目：" + question.getTitle()
                + "\n内容：" + question.getContent()
                + "\n答案：" + answer;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("questionId", question.getId());
            metadata.put("title", question.getTitle());
            metadata.put("difficulty", question.getDifficulty());
            metadata.put("category", categoryMap.getOrDefault(question.getId(), "未分类"));

            // 3 参数构造器：ID=questionId，支持增量删除
            Document doc = new Document(String.valueOf(question.getId()), contentString, metadata);
            documents.add(doc);
        }

        // 1. 分批写入 Milvus（DashScope embedding API 一次最多 10 条）
        int batchSize = 10;
        for (int i = 0; i < documents.size(); i += batchSize) {
            int end = Math.min(i + batchSize, documents.size());
            List<Document> batch = documents.subList(i, end);
            vectorStore.add(batch);  // Spring AI 调 DashScope embedding → 写入 Milvus
            log.info("已导入 {}/{} 条面试题到 Milvus 向量库", end, documents.size());
        }

        // 2. 写入 ES 倒排索引（save 为 upsert 语义）
        for (Question question : questions) {
            QuestionEsDoc esDoc = toEsDoc(question, categoryMap);
            elasticsearchOperations.save(esDoc);
        }
        log.info("已导入 {} 条面试题到 Elasticsearch 倒排索引", questions.size());
        log.info("全部导入完成，共 {} 条", documents.size());
        return documents.size();
    }

    /**
     * 构建 questionId → category（题库标题）映射。
     * 一个题目可能属于多个题库，取第一个关联题库的标题作为分类。
     */
    private Map<Long, String> buildCategoryMap() {
        List<QuestionBankQuestion> relations = questionBankQuestionService.list();
        Map<Long, String> bankTitleMap = questionBankService.list().stream()
                .collect(Collectors.toMap(QuestionBank::getId, QuestionBank::getTitle, (a, b) -> a));
        Map<Long, String> result = new HashMap<>();
        for (QuestionBankQuestion rel : relations) {
            result.putIfAbsent(rel.getQuestionId(),
                    bankTitleMap.getOrDefault(rel.getQuestionBankId(), "未分类"));
        }
        return result;
    }

    /**
     * Question 实体 → ES 文档（含 category）
     */
    private QuestionEsDoc toEsDoc(Question question, Map<Long, String> categoryMap) {
        QuestionEsDoc esDoc = new QuestionEsDoc();
        esDoc.setQuestionId(question.getId());
        esDoc.setTitle(question.getTitle());
        esDoc.setContent(question.getContent());
        esDoc.setAnswer(question.getAnswer() != null ? question.getAnswer() : "暂无答案");
        esDoc.setDifficulty(question.getDifficulty());
        esDoc.setType(question.getType());
        esDoc.setCategory(categoryMap.getOrDefault(question.getId(), "未分类"));
        esDoc.setCreateTime(question.getCreateTime());
        return esDoc;
    }

    // ==================== 在线检索 + 生成（Advanced RAG） ====================

    private static final String SYSTEM_PROMPT = "你是一个专业的面试题知识库助手。"
            + "只能基于检索到的面试题内容回答用户问题，不要编造未在上下文中出现的信息。"
            + "回答要准确、有条理，使用中文。";

    private static final String RAG_PROMPT_TEMPLATE = """
            请基于以下检索到的面试题资料回答用户问题。

            【检索到的面试题资料】
            {context}

            【回答要求】
            1. 只能基于上述资料回答，不要编造。
            2. 如果资料中没有相关内容，请如实说明。
            3. 回答要条理清晰，分点阐述。

            【用户问题】
            {question}
            """;

    /**
     * Advanced RAG 问答（Modular RAG 手动 DAG 编排）
     *
     * 9 步流程：
     * 1. 语义缓存查询
     * 2. 查询改写
     * 3. 混合检索（向量+BM25 → RRF）
     * 4. Cross-Encoder 精排
     * 5. 去重 + Lost-in-the-middle 重排
     * 6. 引用标注
     * 7. 拼接上下文 + Prompt
     * 8. 调通义千问生成
     * 9. 写缓存 + 返回
     */
    public RagChatResponse ragChat(String userMessage) {
        // 1. 语义缓存查询
        String cached = semanticCache.get(userMessage);
        if (cached != null) {
            log.info("语义缓存命中，直接返回");
            RagChatResponse resp = new RagChatResponse();
            resp.setAnswer(cached);
            resp.setSourceQuestions(List.of());
            resp.setCacheHit(true);
            return resp;
        }

        // 2. 查询改写（Pre-Retrieval）
        String rewrittenQuery = queryRewriteTransformer.rewrite(userMessage);

        // 3. 混合检索（向量 Top-20 + BM25 Top-20 → RRF 融合 Top-10）
        List<Document> candidates = hybridRetriever.retrieve(rewrittenQuery);

        // 4. Cross-Encoder 精排 Top-5
        List<Document> topDocs = rerankService.rerank(rewrittenQuery, candidates, 5);

        // 5. 去重 + Lost-in-the-middle 重排
        topDocs = documentDeduplicator.deduplicate(topDocs);
        topDocs = lostInTheMiddleRearranger.rearrange(topDocs);

        // 6. 引用标注（从 metadata 提取 questionId + title）
        List<SourceQuestion> sources = extractSources(topDocs);

        // 7. 拼接上下文 + 自定义中文 Prompt
        String context = buildContext(topDocs);
        String userPrompt = RAG_PROMPT_TEMPLATE
                .replace("{context}", context)
                .replace("{question}", userMessage);  // 注意：Prompt 用原始问题，不是改写后的

        // 8. 调用通义千问生成
        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        // 9. 语义缓存写入 + 返回
        semanticCache.put(userMessage, answer);

        RagChatResponse response = new RagChatResponse();
        response.setAnswer(answer);
        response.setSourceQuestions(sources);
        response.setCacheHit(false);
        return response;
    }

    /**
     * 引用标注：从检索文档 metadata 提取 questionId + title，按 questionId 去重
     */
    private List<SourceQuestion> extractSources(List<Document> docs) {
        return docs.stream()
                .map(doc -> {
                    Map<String, Object> meta = doc.getMetadata();
                    Long questionId = toLong(meta.get("questionId"));
                    String title = String.valueOf(meta.getOrDefault("title", ""));
                    return new SourceQuestion(questionId, title);
                })
                .filter(s -> s.getQuestionId() != null)
                .collect(Collectors.toMap(
                        SourceQuestion::getQuestionId,
                        s -> s,
                        (a, b) -> a))  // 按 questionId 去重
                .values().stream().toList();
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 拼接检索文档为 Prompt 上下文
     */
    private String buildContext(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            sb.append("--- 资料 ").append(i + 1).append(" ---\n");
            sb.append(docs.get(i).getText()).append("\n\n");
        }
        return sb.toString();
    }

    // ==================== 增量入库（事件驱动） ====================

    /**
     * 监听题目变更事件，增量同步 Milvus + ES。
     * Spring @EventListener：参数类型决定监听哪种事件
     */
    @EventListener
    public void onQuestionChanged(QuestionChangedEvent event) {
        Question question = event.question();
        try {
            switch (event.action()) {
                case ADD -> indexQuestion(question, false);
                case UPDATE -> indexQuestion(question, true);
                case DELETE -> deleteQuestionIndex(question.getId());
            }
        } catch (Exception e) {
            // 增量入库失败不影响主业务（题目 CRUD 已成功）
            log.error("RAG 增量入库失败：questionId={}, action={}, err={}",
                    question.getId(), event.action(), e.getMessage(), e);
        }
    }

    /**
     * 增量索引单条题目到 Milvus + ES
     * @param isUpdate true=先删旧索引再建（UPDATE），false=直接新建（ADD）
     */
    private void indexQuestion(Question question, boolean isUpdate) {
        if (isUpdate) {
            deleteQuestionIndex(question.getId());  // Milvus 不支持原地更新向量，先删再建
        }
        Map<Long, String> categoryMap = buildCategoryMap();
        String answer = question.getAnswer() != null ? question.getAnswer() : "暂无答案";
        String content = "题目：" + question.getTitle()
                + "\n内容：" + question.getContent()
                + "\n答案：" + answer;
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("questionId", question.getId());
        metadata.put("title", question.getTitle());
        metadata.put("difficulty", question.getDifficulty());
        metadata.put("category", categoryMap.getOrDefault(question.getId(), "未分类"));

        // Milvus（文档 ID = questionId）
        Document doc = new Document(String.valueOf(question.getId()), content, metadata);
        vectorStore.add(List.of(doc));
        // ES（_id = questionId，save 为 upsert）
        QuestionEsDoc esDoc = toEsDoc(question, categoryMap);
        elasticsearchOperations.save(esDoc);
        log.info("增量入库完成：questionId={}, isUpdate={}", question.getId(), isUpdate);
    }

    /**
     * 从 Milvus + ES 删除单条题目索引
     */
    private void deleteQuestionIndex(Long questionId) {
        vectorStore.delete(List.of(String.valueOf(questionId)));
        elasticsearchOperations.delete(String.valueOf(questionId), QuestionEsDoc.class);
        log.info("增量删除完成：questionId={}", questionId);
    }
}
```

**解析**：
- `ragChat` 是手动 DAG 编排（不依赖 Advisor 黑盒），9 步可插拔
- 查询改写用改写后的 query 检索，但 Prompt 里放原始 userMessage
- 缓存命中时 sourceQuestions 为空，前端可据此判断是否展示引用标注
- 增量入库的 `@EventListener` 默认同步，失败不回滚 MySQL（try-catch 兜底）
- `vectorStore.delete(List.of("123"))` 参数是文档 ID 列表，对应 ETL 写入时的 `Document ID`

---

### 文件 11：QuestionSearchService.java — ES autocomplete

**路径**：`rag/service/QuestionSearchService.java`
**作用**：搜索栏前缀匹配，返回题目标题下拉建议。

**完整源码**：
```java
package com.charles.interview.arena.rag.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.rag.model.QuestionEsDoc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
```

**解析**：
- `match_phrase_prefix` 走倒排索引（快），不是 `wildcard`（暴力扫描）
- 输入 "Java 集合" → `java` 精确匹配 + `集` 前缀匹配 → 命中"Java 集合框架"

---

### 文件 12：RagController.java — 三个 HTTP 接口

**路径**：`rag/controller/RagController.java`
**作用**：对外暴露 3 个接口，前端与 RAG 模块交互的唯一入口。

**完整源码**：
```java
package com.charles.interview.arena.rag.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.charles.interview.arena.common.BaseResponse;
import com.charles.interview.arena.common.ErrorCode;
import com.charles.interview.arena.common.ResultUtils;
import com.charles.interview.arena.exception.ThrowUtils;
import com.charles.interview.arena.model.dto.RagChatDTO;
import com.charles.interview.arena.model.entity.User;
import com.charles.interview.arena.rag.model.RagChatResponse;
import com.charles.interview.arena.rag.service.QuestionSearchService;
import com.charles.interview.arena.rag.service.RagService;
import com.charles.interview.arena.service.UserService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagService ragService;
    private final QuestionSearchService questionSearchService;
    private final UserService userService;

    /**
     * 接口 1：POST /rag/import — ETL 离线全量导入（仅管理员）
     */
    @PostMapping("/import")
    public BaseResponse<Integer> importQuestionsToVectorStore(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        User user = userService.getById(userId);
        ThrowUtils.throwIf(!"admin".equals(user.getRole()),
                ErrorCode.NO_AUTH_ERROR, "无权限，仅管理员可操作");
        int count = ragService.importQuestionsToVectorStore();
        return ResultUtils.success(count);
    }

    /**
     * 接口 2：POST /rag/chat — 在线 RAG 问答（登录即可用）
     */
    @PostMapping("/chat")
    public BaseResponse<RagChatResponse> chat(@Valid @RequestBody RagChatDTO ragChatDTO, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "未登录");
        RagChatResponse response = ragService.ragChat(ragChatDTO.getMessage());
        return ResultUtils.success(response);
    }

    /**
     * 接口 3：GET /rag/suggest — 搜索栏 autocomplete（无需登录）
     */
    @GetMapping("/suggest")
    public BaseResponse<List<String>> suggest(
            @RequestParam String prefix,
            @RequestParam(defaultValue = "10") int limit) {
        List<String> suggestions = questionSearchService.suggest(prefix, limit);
        return ResultUtils.success(suggestions);
    }
}
```

**接口对比**：

| 接口 | 方法 | 权限 | 用途 |
|------|------|------|------|
| /rag/import | POST | admin | 离线全量建索引 |
| /rag/chat | POST | 登录用户 | 在线 RAG 问答 |
| /rag/suggest | GET | 无 | 搜索栏下拉提示 |

---

### 文件 13：RagChatResponse.java — 结构化响应 DTO

**路径**：`rag/model/RagChatResponse.java`

```java
package com.charles.interview.arena.rag.model;

import java.util.List;

import lombok.Data;

@Data
public class RagChatResponse {
    /** AI 生成的回答 */
    private String answer;
    /** 引用标注：命中的面试题列表 */
    private List<SourceQuestion> sourceQuestions;
    /** 是否命中语义缓存 */
    private boolean cacheHit;
}
```

---

### 文件 14：SourceQuestion.java — 引用标注 DTO

**路径**：`rag/model/SourceQuestion.java`

```java
package com.charles.interview.arena.rag.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SourceQuestion {
    private Long questionId;
    private String title;
}
```

---

### 文件 15：QuestionEsDoc.java — ES 文档实体

**路径**：`rag/model/QuestionEsDoc.java`

```java
package com.charles.interview.arena.rag.model;

import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.DateFormat;

import lombok.Data;

@Data
@Document(indexName = "question")
public class QuestionEsDoc {

    @Id
    @Field(type = FieldType.Long)
    private Long questionId;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String answer;

    @Field(type = FieldType.Keyword)
    private String difficulty;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private String type;

    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime createTime;
}
```

**解析**：
- `ik_max_word`：索引时最大切分（"Java集合框架" → Java/集合/框架）
- `ik_smart`：查询时智能切分（分词更少，避免过度拆分）
- `FieldType.Keyword`：精确匹配不分词（category/difficulty/type）

---

## 四、完整数据流

```
管理员调 /rag/import
  → questionService.list()                        查 MySQL 所有题目
  → buildCategoryMap()                           构建 题目ID→题库标题 映射
  → 构造 Document(ID=questionId, metadata)       每条题转 Document
  → vectorStore.add(batch)  ×N                  分批写入 Milvus（10条/批）
  → elasticsearchOperations.save(esDoc) ×N       逐条写入 ES
  → 返回导入条数

用户调 /rag/chat
  → [1] semanticCache.get(query)                  Redis 语义缓存
       ↓ hit → 直接返回（cacheHit=true）
       ↓ miss
  → [2] queryRewriteTransformer.rewrite(query)   LLM 查询改写
  → [3] hybridRetriever.retrieve(rewrittenQuery)
         → Milvus 向量检索 Top-20
         → ES BM25 检索 Top-20
         → RRF 融合 Top-10
  → [4] rerankService.rerank(query, top10, 5)     Cross-Encoder Top-5
  → [5] documentDeduplicator.deduplicate(top5)    按 questionId 去重
       lostInTheMiddleRearranger.rearrange()      首尾重排
  → [6] extractSources(topDocs)                   引用标注
  → [7] buildContext(topDocs) + Prompt 拼接       上下文 + 原始问题
  → [8] chatClient.prompt().system().user().call() 通义千问生成
  → [9] semanticCache.put(query, answer)          写缓存 TTL=1h
  → 返回 {answer, sourceQuestions, cacheHit:false}

管理员增删改题目
  → QuestionServiceImpl.save/updateById/removeById  MySQL 操作
  → eventPublisher.publishEvent(QuestionChangedEvent)
  → RagService.onQuestionChanged(@EventListener)
       → ADD: indexQuestion(question, false)        Milvus + ES 建索引
       → UPDATE: indexQuestion(question, true)      先删再建
       → DELETE: deleteQuestionIndex(questionId)    删 Milvus + ES

用户搜索栏输入
  → GET /rag/suggest?prefix=Java
  → QuestionSearchService.suggest("Java", 10)
  → ES match_phrase_prefix(title)
  → 返回 ["Java 的特性", "Java 面向对象", ...]
```

---

## 五、面试讲点速查

| 八股编号 | 知识点 | 本项目落地点 |
|---------|--------|------------|
| #6 | BM25 是打分函数不是索引结构 | BM25Retriever 用 ES 倒排索引 |
| #7 | Cross-Encoder 精排 | RerankService 调 gte-rerank |
| #8 | RRF 排名融合 | HybridRetriever.rrfFuse() |
| #172 | RAG 在线检索链路 | RagService.ragChat() 9 步 DAG |
| #176 | Modular RAG（DAG 编排） | 手动编排，不依赖 Advisor 黑盒 |
| blueprint 5.5 | 语义缓存 | SemanticCache cosine > 0.95 |
| blueprint 5.5 | 引用标注 | extractSources() 返回 questionId + title |
| blueprint 5.5 | 自定义中文 Prompt | SYSTEM_PROMPT + RAG_PROMPT_TEMPLATE |
| blueprint 5.5 | 增量入库 | @EventListener + QuestionChangedEvent |
| blueprint 5.5 | Lost-in-the-middle | LostInTheMiddleRearranger |
| blueprint 5.5 | 元数据过滤 | BM25Retriever bool query + Milvus filterExpression |
