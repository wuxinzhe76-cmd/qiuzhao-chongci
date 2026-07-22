package com.charles.interview.arena;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import lombok.extern.slf4j.Slf4j;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM 连接测试（基于 Spring AI 自动配置）
 * <p>
 * 验证 Spring AI 的 ChatClient（MiniMax）和 EmbeddingModel（DashScope）能否正常连接。
 */
@Slf4j
@SpringBootTest
@TestPropertySource(properties = {
    "spring.ai.mcp.client.enabled=false"  // 测试时禁用 MCP Client（本地无 MCP Server）
})
class LlmConnectionTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Test
    void testMiniMaxChatConnection() {
        log.info("=== 测试 Spring AI ChatClient（MiniMax）连接 ===");

        String response = chatClientBuilder
                .build()
                .prompt()
                .user("说一个字：好")
                .call()
                .content();

        log.info("MiniMax 响应: {}", response);
        assertNotNull(response, "ChatClient 响应不能为空");
        assertFalse(response.isBlank(), "ChatClient 响应不能为空白");
        log.info("=== Spring AI ChatClient 连接成功 ===");
    }

    @Test
    void testDashScopeEmbeddingConnection() {
        log.info("=== 测试 Spring AI EmbeddingModel（DashScope）连接 ===");

        float[] vector = embeddingModel.embed("Java 面试题");

        log.info("Embedding 维度: {}", vector.length);
        assertNotNull(vector, "Embedding 结果不能为空");
        assertTrue(vector.length > 0, "Embedding 维度必须大于 0");
        log.info("=== Spring AI EmbeddingModel 连接成功，维度={} ===", vector.length);
    }
}
