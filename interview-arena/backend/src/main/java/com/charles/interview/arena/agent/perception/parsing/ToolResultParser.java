package com.charles.interview.arena.agent.perception.parsing;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.perception.model.Observation;
import com.charles.interview.arena.agent.perception.normalization.ObservationNormalizer;
import com.charles.interview.arena.agent.tool.api.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具返回解析器(ToolResult -> Observation)
 * <p>
 * 职责:将工具执行结果转换为标准 Observation,保留结构化元数据。
 * 在 ReAct 循环中被 ReActExecutor 调用,不经过 PerceptionService。
 * <p>
 * 安全:所有工具返回标记为 UNTRUSTED(防间接注入)。
 * 截断:超长结果截断,防 Token 爆炸。
 */
@Component
public class ToolResultParser {

    private static final Logger log = LoggerFactory.getLogger(ToolResultParser.class);

    private final ObservationNormalizer observationNormalizer;
    private final ObjectMapper objectMapper;

    /** Observation 文本最大长度(防 Token 爆炸) */
    private static final int MAX_CONTENT_LENGTH = 2000;

    public ToolResultParser(ObservationNormalizer observationNormalizer, ObjectMapper objectMapper) {
        this.observationNormalizer = observationNormalizer;
        this.objectMapper = objectMapper;
    }

    /**
     * 将 ToolResult 转为 Observation
     *
     * @param result 工具执行结果
     * @return 标准 Observation(标记 UNTRUSTED)
     */
    public Observation parse(ToolResult result) {
        if (result == null) {
            return Observation.toolError("工具返回为 null");
        }

        if (!result.isSuccess()) {
            return observationNormalizer.normalizeToolError(result.getErrorMessage());
        }

        Object data = result.getData();
        if (data == null) {
            return Observation.toolResult("（无结果）", Map.of());
        }

        try {
            // 序列化为 JSON 文本
            String jsonContent = objectMapper.writeValueAsString(data);

            // 截断超长内容
            String content = abbreviate(jsonContent, MAX_CONTENT_LENGTH);

            // 提取元数据(如果是 Map 类型,保留关键字段)
            Map<String, Object> metadata = extractMetadata(data);

            return observationNormalizer.normalizeToolResult(content, metadata);

        } catch (Exception e) {
            log.warn("工具返回解析失败,降级为 toString: {}", e.getMessage());
            String content = abbreviate(String.valueOf(data), MAX_CONTENT_LENGTH);
            return observationNormalizer.normalizeToolResult(content, Map.of());
        }
    }

    /**
     * 从工具返回数据中提取元数据
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMetadata(Object data) {
        Map<String, Object> metadata = new HashMap<>();

        if (data instanceof Map<?, ?> map) {
            // 提取常见元数据字段
            copyIfPresent(map, "questionId", metadata);
            copyIfPresent(map, "title", metadata);
            copyIfPresent(map, "difficulty", metadata);
            copyIfPresent(map, "category", metadata);
        }

        return metadata;
    }

    private void copyIfPresent(Map<?, ?> source, String key, Map<String, Object> target) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...(截断)";
    }
}
