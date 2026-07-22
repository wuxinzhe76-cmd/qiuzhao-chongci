package com.charles.interview.arena.agent.rag.service;

import java.time.Duration;
import java.util.Base64;
import java.util.Set;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 语义缓存（Semantic Cache）
 * <p>
 * 八股映射：
 * - 场景：用户问"HashMap 底层原理"和"HashMap 的实现原理"，语义相同但字面不同
 * - 语义缓存 vs 精确缓存：精确缓存用 key=value 字面匹配，语义缓存用向量相似度匹配
 * - 相似度阈值：cosine > 0.95 才命中（precision/recall trade-off，0.95 偏保守避免假阳性）
 * <p>
 * 实现：
 * 1. 用户提问 → Embedding → 遍历 Redis 中的缓存向量 → cosine 相似度 > 0.95 → 命中
 * 2. 未命中 → 调 RAG 链路 → 结果存入 Redis（key=embedding_base64, value=answer）
 * <p>
 * 注：当前实现为线性扫描，适合缓存条目 < 1000 的场景。
 * 生产环境可用 Redis Stack 的 VECTOR 类型或 Milvus 子 collection 做向量检索。
 */
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
     *
     * @param query 用户提问
     * @return 命中的缓存回答，未命中返回 null
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

            // key 格式：rag:sem_cache:{base64_embedding}
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
     *
     * @param query  用户提问
     * @param answer RAG 生成的回答
     */
    public void put(String query, String answer) {
        float[] embedding = embeddingModel.embed(query);
        String embeddingBase64 = encodeFloatArrayToBase64(embedding);
        String key = CACHE_PREFIX + embeddingBase64;
        redisTemplate.opsForValue().set(key, answer, CACHE_TTL);
        log.info("语义缓存写入：query='{}'", query);
    }

    // ---- 工具方法 ----

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
