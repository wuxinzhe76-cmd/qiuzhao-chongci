package com.charles.interview.arena.agent.memory.semantic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.charles.interview.arena.model.entity.InterviewRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 知识点画像分析器（蓝图 §5.4.5）
 * <p>
 * AI 分析面试记录 -> 提取知识点/薄弱点 -> 写入 user_knowledge_profile。
 * <p>
 * 输入：面试所有问答明细
 * 输出：Map<知识点, 掌握度 0-100>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileAnalyzer {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    private static final String ANALYZE_PROMPT = """
            你是一个面试分析助手。请分析下面的面试问答记录，提取候选人本次面试中被考察的知识点，
            并评估对每个知识点的掌握程度（0-100 分）。

            【面试问答记录】
            %s

            【输出要求】
            严格输出 JSON：{"topics": [{"topic": "知识点名称", "mastery": 75}]}
            - topic 必须是简洁的知识点名称（如 "Java volatile"、"HashMap 扩容机制"）
            - mastery 是 0-100 的整数
            - 只输出 JSON，不要其他文字
            """;

    /**
     * 分析面试记录，提取知识点掌握度
     *
     * @param records 面试问答明细
     * @return Map<知识点, 掌握度 0-100>
     */
    public Map<String, Integer> analyze(List<InterviewRecord> records) {
        if (records == null || records.isEmpty()) {
            return new HashMap<>();
        }

        // 拼接问答记录
        StringBuilder sb = new StringBuilder();
        for (InterviewRecord r : records) {
            sb.append(r.getRole()).append(": ")
              .append(r.getContent() != null ? r.getContent() : "")
              .append("\n");
        }

        String prompt = String.format(ANALYZE_PROMPT, sb.toString());

        try {
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            return parseAnalysisResult(response);
        } catch (Exception e) {
            log.error("AI 分析知识点失败，返回空 Map", e);
            return new HashMap<>();
        }
    }

    private Map<String, Integer> parseAnalysisResult(String json) {
        Map<String, Integer> result = new HashMap<>();
        if (json == null || json.isBlank()) {
            return result;
        }
        try {
            // 容错：去掉可能的 markdown 代码块包裹
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
            }
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode topics = root.path("topics");
            if (topics.isArray()) {
                for (JsonNode t : topics) {
                    String topic = t.path("topic").asText("");
                    int mastery = t.path("mastery").asInt(0);
                    if (!topic.isEmpty()) {
                        result.put(topic, mastery);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 AI 分析结果失败：{}", e.getMessage());
        }
        return result;
    }
}
