package com.charles.interview.arena.agent.reflection.model;

/**
 * 反思结果
 *
 * @param valid         校验是否通过
 * @param errorType     错误类型(null 表示通过)
 * @param errorMessage  错误描述
 * @param correction    纠错类型(null 表示通过)
 * @param repairPrompt  修复提示(给 LLM 的纠正指令)
 */
public record ReflectionResult(
        boolean valid,
        String errorType,
        String errorMessage,
        ErrorCorrection correction,
        String repairPrompt
) {
    /** 校验通过 */
    public static ReflectionResult pass() {
        return new ReflectionResult(true, null, null, null, null);
    }

    /** 校验失败,带修复提示 */
    public static ReflectionResult invalid(String errorType, String errorMessage,
                                           ErrorCorrection correction, String repairPrompt) {
        return new ReflectionResult(false, errorType, errorMessage, correction, repairPrompt);
    }

    /** 校验失败,无需修复(如权限错误) */
    public static ReflectionResult invalid(String errorType, String errorMessage) {
        return new ReflectionResult(false, errorType, errorMessage, null, null);
    }
}
