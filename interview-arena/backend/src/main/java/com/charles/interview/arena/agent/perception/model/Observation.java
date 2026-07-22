package com.charles.interview.arena.agent.perception.model;

import java.util.Map;

/**
 * 标准化观察(支持多模态)
 * <p>
 * 文本输入: content 有值, imageRef 为 null
 * PDF 输入: content 为提取文本, metadata 含页码/表格结构
 * 图片输入: content 为简述, imageRef 为 base64/URL
 * 工具返回: content 为结果文本, metadata 含 questionId 等元数据
 */
public record Observation(
        String content,
        String imageRef,
        Map<String, Object> metadata,
        String source,
        TrustLevel trustLevel
) {
    private static final Map<String, Object> EMPTY_META = Map.of();

    /** 用户文本输入 */
    public static Observation userInput(String text) {
        return new Observation(text, null, EMPTY_META, "USER", TrustLevel.UNTRUSTED);
    }

    /** 用户图片输入 */
    public static Observation userImage(String description, String imageRef) {
        return new Observation(description, imageRef, EMPTY_META, "IMAGE", TrustLevel.UNTRUSTED);
    }

    /** PDF 解析结果 */
    public static Observation pdfContent(String text, Map<String, Object> metadata) {
        return new Observation(text, null, metadata, "PDF", TrustLevel.UNTRUSTED);
    }

    /** 工具返回结果 */
    public static Observation toolResult(String text, Map<String, Object> metadata) {
        return new Observation(text, null, metadata != null ? metadata : EMPTY_META,
                "TOOL", TrustLevel.UNTRUSTED);
    }

    /** 工具返回失败 */
    public static Observation toolError(String errorMessage) {
        return new Observation("工具执行失败: " + errorMessage, null, EMPTY_META,
                "TOOL_ERROR", TrustLevel.UNTRUSTED);
    }

    /** 是否含图片 */
    public boolean hasImage() {
        return imageRef != null && !imageRef.isBlank();
    }

    /** 是否有结构化元数据 */
    public boolean hasMetadata() {
        return metadata != null && !metadata.isEmpty();
    }
}
