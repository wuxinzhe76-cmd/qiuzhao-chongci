package com.charles.interview.arena.agent.perception.intent;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.perception.model.Intent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 实体提取器(LLM 辅助)
 * <p>
 * 从用户输入中提取业务实体:
 * - domain: 领域(JAVA/PYTHON/数据库等)
 * - knowledgePoint: 知识点(HashMap/线程池/volatile 等)
 * - useHistoricalWeakness: 是否使用历史薄弱点
 */
@Component
public class EntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(EntityExtractor.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String EXTRACT_PROMPT = """
            你是实体提取器。请从用户输入中提取以下实体,输出 JSON。
            
            实体定义:
            - domain: 技术领域(JAVA/PYTHON/GO/数据库/操作系统/网络/算法等)
            - knowledgePoint: 具体知识点(如 HashMap/线程池/volatile/Spring 等)
            - difficulty: 期望难度(EASY/MEDIUM/HARD,如果用户没提则为 null)
            - useHistoricalWeakness: 是否基于历史薄弱点出题(true/false)
            
            【用户输入】
            %s
            
            【输出要求】
            严格输出 JSON: {"domain":"JAVA","knowledgePoint":"HashMap","difficulty":null,"useHistoricalWeakness":false}
            只输出 JSON,不要其他文字。
            """;

    public EntityExtractor(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 提取实体
     *
     * @param text   用户输入文本
     * @param intent 已识别的意图(辅助提取)
     * @return 实体 Map
     */
    public Map<String, Object> extract(String text, Intent intent) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }

        // 简单意图不做 LLM 提取(省成本)
        if (intent == Intent.END_INTERVIEW || intent == Intent.SAVE_TO_KB) {
            return Map.of();
        }

        try {
            String prompt = String.format(EXTRACT_PROMPT, text);
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                return Map.of();
            }

            // 容错:去掉 markdown 代码块
            String cleaned = response.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
            }

            JsonNode root = objectMapper.readTree(cleaned);
            Map<String, Object> entities = new HashMap<>();

            putIfPresent(entities, "domain", root.path("domain"));
            putIfPresent(entities, "knowledgePoint", root.path("knowledgePoint"));
            putIfPresent(entities, "difficulty", root.path("difficulty"));
            entities.put("useHistoricalWeakness", root.path("useHistoricalWeakness").asBoolean(false));

            log.info("实体提取: {}", entities);
            return entities;

        } catch (Exception e) {
            log.warn("实体提取失败,返回空 Map: {}", e.getMessage());
            return Map.of();
        }
    }

    private void putIfPresent(Map<String, Object> map, String key, JsonNode node) {
        if (node != null && !node.isNull() && !node.asText("").isBlank()) {
            map.put(key, node.asText());
        }
    }
}
