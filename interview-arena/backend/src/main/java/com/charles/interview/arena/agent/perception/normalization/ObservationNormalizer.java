package com.charles.interview.arena.agent.perception.normalization;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.perception.model.Observation;
import com.charles.interview.arena.agent.perception.model.TrustLevel;

/**
 * 观察标准化器(多模态 -> 统一 Observation)
 * <p>
 * 职责:将不同输入类型(文本/PDF/图片/工具返回)统一为 Observation 结构。
 * 对多模态 LLM 友好:图片保留引用,PDF 保留结构,不强制转纯文本。
 */
@Component
public class ObservationNormalizer {

    private final TextNormalizer textNormalizer;

    public ObservationNormalizer(TextNormalizer textNormalizer) {
        this.textNormalizer = textNormalizer;
    }

    /**
     * 标准化用户文本输入
     */
    public Observation normalizeUserText(String text) {
        String normalized = textNormalizer.normalize(text);
        return Observation.userInput(normalized);
    }

    /**
     * 标准化用户图片输入(保留图片引用,不 OCR)
     *
     * @param description 图片简述(用户可能附带文字说明)
     * @param imageRef    图片引用(base64/URL/path)
     */
    public Observation normalizeUserImage(String description, String imageRef) {
        String normalizedDesc = description != null ? textNormalizer.normalize(description) : "用户上传的图片";
        return Observation.userImage(normalizedDesc, imageRef);
    }

    /**
     * 标准化 PDF 解析结果(保留页码/表格结构)
     *
     * @param text     PDF 提取的文本内容
     * @param metadata 结构化元数据(页数/表格数等)
     */
    public Observation normalizePdfContent(String text, Map<String, Object> metadata) {
        String normalized = textNormalizer.normalize(text);
        return Observation.pdfContent(normalized, metadata);
    }

    /**
     * 标准化工具返回结果(保留元数据)
     *
     * @param content  工具返回的文本内容
     * @param metadata 工具返回的元数据(questionId/title 等)
     */
    public Observation normalizeToolResult(String content, Map<String, Object> metadata) {
        String normalized = textNormalizer.normalize(content);
        return Observation.toolResult(normalized, metadata);
    }

    /**
     * 标准化工具返回失败
     */
    public Observation normalizeToolError(String errorMessage) {
        return Observation.toolError(errorMessage);
    }

    /**
     * 为 Observation 设置信任级别
     */
    public Observation withTrustLevel(Observation observation, TrustLevel trustLevel) {
        return new Observation(
                observation.content(),
                observation.imageRef(),
                observation.metadata(),
                observation.source(),
                trustLevel
        );
    }
}
