package com.charles.interview.arena.agent.perception.parsing;

import java.util.Base64;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.perception.model.Observation;
import com.charles.interview.arena.agent.perception.normalization.ObservationNormalizer;

/**
 * 图片内容解析器
 * <p>
 * 职责:将图片转为 Observation,保留图片引用(不 OCR)。
 * 对多模态 LLM 友好:MiniMax-M3 可直接理解图片。
 * <p>
 * 策略:
 * - 小图片(< 1MB):转 base64 内嵌
 * - 大图片(>= 1MB):只记录引用,不内嵌(防 Token 爆炸)
 */
@Component
public class ImageContentParser {

    private static final Logger log = LoggerFactory.getLogger(ImageContentParser.class);

    private static final long BASE64_THRESHOLD = 1024 * 1024; // 1MB

    private final ObservationNormalizer observationNormalizer;

    public ImageContentParser(ObservationNormalizer observationNormalizer) {
        this.observationNormalizer = observationNormalizer;
    }

    /**
     * 解析图片
     *
     * @param imageData 图片字节数组
     * @param mimeType  图片 MIME 类型(image/png, image/jpeg 等)
     * @return 含图片引用的 Observation
     */
    public Observation parse(byte[] imageData, String mimeType) {
        if (imageData == null || imageData.length == 0) {
            return Observation.toolError("图片数据为空");
        }

        String imageRef;
        String description;

        if (imageData.length < BASE64_THRESHOLD) {
            // 小图片:转 base64 data URI
            String base64 = Base64.getEncoder().encodeToString(imageData);
            imageRef = "data:" + (mimeType != null ? mimeType : "image/png") + ";base64," + base64;
            description = "用户上传的图片(" + (mimeType != null ? mimeType : "image") + ", " + imageData.length + " bytes)";
        } else {
            // 大图片:不内嵌,只记录元数据(实际应用中可存临时文件)
            imageRef = null;
            description = "用户上传的图片(" + (mimeType != null ? mimeType : "image") + ", " + imageData.length + " bytes, 超过1MB不内嵌)";
        }

        log.info("图片解析: mimeType={}, size={}, base64={}",
                mimeType, imageData.length, imageRef != null);

        Map<String, Object> metadata = Map.of(
                "mimeType", mimeType != null ? mimeType : "unknown",
                "size", imageData.length,
                "base64Embedded", imageRef != null
        );

        Observation obs = observationNormalizer.normalizeUserImage(description, imageRef);
        return new Observation(obs.content(), obs.imageRef(), metadata, obs.source(), obs.trustLevel());
    }
}
