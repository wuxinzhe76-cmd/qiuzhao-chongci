package com.charles.interview.arena.agent.tool.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.charles.interview.arena.agent.guardrail.tool.ToolPermission;
import com.charles.interview.arena.agent.tool.api.Tool;
import com.charles.interview.arena.agent.tool.api.ToolInput;
import com.charles.interview.arena.agent.tool.api.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 联网搜索工具（模型可调用）
 * <p>
 * 从 QuickAskService 抽出：当前实现调用通义千问 qwen-plus 自带的联网搜索能力
 * （enable_search=true）。替代方案：Tavily API、Bing Search API。
 * <p>
 * 返回 {content: 搜索综合内容, sources: 来源 URL 列表}，
 * 编排层可从轨迹中提取 sources 做「联网参考」标注。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool implements Tool {

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    /** DashScope API Key（联网搜索走通义千问） */
    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    private static final String WEB_SEARCH_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/text-generation/generation";

    @Override
    public String getName() { return "webSearch"; }

    @Override
    public String getDescription() { return "联网搜索最新技术内容（低权重参考源，用于题库没有或有时效性的问题，返回内容与来源链接）"; }

    @Override
    public String getInputSchema() {
        return "{\"query\": \"string, 搜索关键词或问题\"}";
    }

    @Override
    public ToolPermission.Level getPermissionLevel() { return ToolPermission.Level.READ; }

    @Override
    public ToolResult execute(ToolInput input) {
        String query = input.getString("query");
        if (query == null || query.isBlank()) {
            return ToolResult.failure("缺少必要参数: query");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("content", "（联网搜索失败，请稍后重试）");
        List<String> sources = new ArrayList<>();
        result.put("sources", sources);

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", "qwen-plus",
                    "input", Map.of(
                            "messages", List.of(
                                    Map.of("role", "system",
                                            "content", "你是一个技术搜索引擎。基于联网搜索结果回答用户问题，并标注来源 URL。"),
                                    Map.of("role", "user", "content", query)
                            )
                    ),
                    "parameters", Map.of(
                            "enable_search", true,
                            "result_format", "message"
                    )
            );

            String response = webClientBuilder.build()
                    .post()
                    .uri(WEB_SEARCH_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);
            JsonNode output = root.path("output");
            JsonNode choices = output.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                result.put("content", choices.get(0).path("message").path("content").asText(""));
            }

            JsonNode searchResults = output.path("search_results");
            if (searchResults.isArray()) {
                for (JsonNode r : searchResults) {
                    String url = r.path("url").asText("");
                    if (!url.isEmpty()) {
                        sources.add(url);
                    }
                }
            }

            log.info("联网搜索: query='{}', sources={}", query, sources.size());
            return ToolResult.success(result);
        } catch (Exception e) {
            log.warn("联网搜索失败: query='{}', err={}", query, e.getMessage());
            return ToolResult.failure("联网搜索失败: " + e.getMessage());
        }
    }
}
