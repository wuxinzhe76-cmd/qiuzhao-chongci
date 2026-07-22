package com.charles.interview.arena.agent.perception.validation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.perception.model.RawInput;

/**
 * 文件校验器
 * <p>
 * 职责:校验上传文件的 MIME 类型、大小、数量。
 * 白名单机制,只允许安全类型。
 */
@Component
public class FileValidator {

    /** 单文件最大大小(默认 10MB) */
    @Value("${perception.max-file-size:10485760}")
    private long maxFileSize;

    /** 允许的 MIME 类型白名单 */
    private static final java.util.Set<String> ALLOWED_MIME_TYPES = java.util.Set.of(
            "application/pdf",
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/gif",
            "image/webp"
    );

    /**
     * 校验文件
     *
     * @param input 原始输入(含文件信息)
     * @throws IllegalArgumentException 文件不合法
     */
    public void validate(RawInput input) {
        if (input == null || input.fileData() == null || input.fileData().length == 0) {
            return; // 无文件,跳过
        }

        // 1. 大小校验
        if (input.fileData().length > maxFileSize) {
            throw new IllegalArgumentException(
                    "文件大小超过限制: " + input.fileData().length + " > " + maxFileSize);
        }

        // 2. MIME 类型白名单校验
        String mimeType = input.mimeType();
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException(
                    "不支持的文件类型: " + mimeType + ", 允许: " + ALLOWED_MIME_TYPES);
        }
    }

    /**
     * 检查 MIME 类型是否为 PDF
     */
    public boolean isPdf(String mimeType) {
        return "application/pdf".equals(mimeType);
    }

    /**
     * 检查 MIME 类型是否为图片
     */
    public boolean isImage(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }
}
