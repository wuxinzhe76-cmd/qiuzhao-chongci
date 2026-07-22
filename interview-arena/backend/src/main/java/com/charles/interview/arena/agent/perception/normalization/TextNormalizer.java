package com.charles.interview.arena.agent.perception.normalization;

import java.text.Normalizer;

import org.springframework.stereotype.Component;

/**
 * 文本规范化器
 * <p>
 * 职责:将输入文本统一为标准格式,检测 Unicode 混淆攻击。
 * <p>
 * 处理项:
 * 1. Unicode NFC 规范化(检测 Unicode 混淆攻击)
 * 2. 不可见字符清理(零宽字符、控制字符)
 * 3. 换行符统一(\r\n -> \n)
 * 4. 连续空白压缩(多个空格变一个)
 * 5. 首尾空白裁剪
 */
@Component
public class TextNormalizer {

    /** 零宽字符正则(零宽空格/零宽连字/零宽非连字/字节顺序标记) */
    private static final String ZERO_WIDTH_CHARS = "[\\u200B\\u200C\\u200D\\uFEFF]";

    /** 控制字符正则(除换行和制表符外的控制字符) */
    private static final String CONTROL_CHARS = "[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]";

    /** 连续空白(非换行) */
    private static final String MULTI_SPACE = "[ \\t]+";

    /**
     * 规范化文本
     *
     * @param text 原始文本
     * @return 规范化后的文本
     */
    public String normalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        // 1. Unicode NFC 规范化(统一组合字符,检测混淆攻击)
        String result = Normalizer.normalize(text, Normalizer.Form.NFC);

        // 2. 移除零宽字符(可能用于隐藏注入指令)
        result = result.replaceAll(ZERO_WIDTH_CHARS, "");

        // 3. 移除控制字符(保留换行和制表符)
        result = result.replaceAll(CONTROL_CHARS, "");

        // 4. 换行符统一
        result = result.replace("\r\n", "\n").replace("\r", "\n");

        // 5. 连续空白压缩(非换行的连续空白压缩为单个空格)
        result = result.replaceAll(MULTI_SPACE, " ");

        // 6. 首尾裁剪
        return result.trim();
    }

    /**
     * 检测文本中是否含有异常编码
     *
     * @param text 原始文本
     * @return true 表示检测到异常编码
     */
    public boolean hasAbnormalEncoding(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        // 检测零宽字符
        if (text.codePoints().anyMatch(c ->
                c == 0x200B || c == 0x200C || c == 0x200D || c == 0xFEFF)) {
            return true;
        }

        // 检测控制字符
        if (text.codePoints().anyMatch(c ->
                (c >= 0x00 && c <= 0x08) || c == 0x0B || c == 0x0C ||
                (c >= 0x0E && c <= 0x1F) || c == 0x7F)) {
            return true;
        }

        // 检测 Base64 编码的长字符串(可能是隐藏的注入指令)
        if (text.length() > 100 && text.matches("^[A-Za-z0-9+/=]{100,}$")) {
            return true;
        }

        return false;
    }
}
