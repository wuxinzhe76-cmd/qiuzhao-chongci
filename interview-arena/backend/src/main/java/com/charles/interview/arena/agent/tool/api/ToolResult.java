package com.charles.interview.arena.agent.tool.api;

import com.charles.interview.arena.agent.perception.model.TrustLevel;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 工具执行结果
 * <p>
 * 统一封装工具执行的输出,包含成功/失败状态、数据、错误信息和信任级别。
 * <p>
 * 安全设计:所有工具返回默认标记为 UNTRUSTED(防间接注入)。
 * ToolResultSanitizer 在 ToolExecutor 中对返回结果做沙箱化处理。
 */
@Data
@AllArgsConstructor
public class ToolResult {

    /** 是否成功 */
    private boolean success;

    /** 返回数据（成功时） */
    private Object data;

    /** 错误信息（失败时） */
    private String errorMessage;

    /** 错误类型（失败时,来自 ToolErrorClassifier 六分类） */
    private String errorType;

    /** 信任级别（默认 UNTRUSTED,工具返回都标记为不可信） */
    private TrustLevel trustLevel;

    // ===== 工厂方法 =====

    public static ToolResult success(Object data) {
        return new ToolResult(true, data, null, null, TrustLevel.UNTRUSTED);
    }

    public static ToolResult failure(String errorMessage) {
        return new ToolResult(false, null, errorMessage, null, TrustLevel.UNTRUSTED);
    }

    public static ToolResult failure(String errorMessage, String errorType) {
        return new ToolResult(false, null, errorMessage, errorType, TrustLevel.UNTRUSTED);
    }

    /**
     * 从数据中获取指定类型的返回值
     */
    @SuppressWarnings("unchecked")
    public <T> T getDataAs(Class<T> type) {
        if (data == null) return null;
        return (T) data;
    }
}
