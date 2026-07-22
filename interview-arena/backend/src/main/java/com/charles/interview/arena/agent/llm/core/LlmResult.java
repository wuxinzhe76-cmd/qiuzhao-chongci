package com.charles.interview.arena.agent.llm.core;

/**
 * LLM 调用结果
 * <p>
 * 封装 LLM 调用的成功/失败状态、数据和错误信息。
 * 工具层根据 LlmResult 决定降级策略（如返回兜底响应或抛出异常）。
 *
 * @param <T> 响应类型
 */
public class LlmResult<T> {

    /** 是否成功 */
    private final boolean success;

    /** 响应数据（成功时） */
    private final T data;

    /** 错误信息（失败时） */
    private final String errorMessage;

    /** 错误类型（失败时）：TRANSIENT / SEMANTIC / STRUCTURAL / CIRCUIT_OPEN */
    private final String errorType;

    private LlmResult(boolean success, T data, String errorMessage, String errorType) {
        this.success = success;
        this.data = data;
        this.errorMessage = errorMessage;
        this.errorType = errorType;
    }

    public static <T> LlmResult<T> success(T data) {
        return new LlmResult<>(true, data, null, null);
    }

    public static <T> LlmResult<T> failure(String errorMessage, String errorType) {
        return new LlmResult<>(false, null, errorMessage, errorType);
    }

    public boolean isSuccess() { return success; }
    public T getData() { return data; }
    public String getErrorMessage() { return errorMessage; }
    public String getErrorType() { return errorType; }
}
