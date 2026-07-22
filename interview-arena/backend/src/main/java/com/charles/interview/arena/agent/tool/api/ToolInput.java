package com.charles.interview.arena.agent.tool.api;

import java.util.HashMap;
import java.util.Map;

import lombok.Data;
import lombok.Builder;

/**
 * 工具输入
 * <p>
 * 封装工具执行所需的上下文和参数。
 * - sessionId / userId：会话级上下文，大多数工具都需要
 * - params：业务参数（如 questionId、answer、mode 等）
 * <p>
 * 使用 builder 模式构造，支持链式调用。
 */
@Data
@Builder
public class ToolInput {

    /** 会话 ID（面试会话标识） */
    private Long sessionId;

    /** 用户 ID */
    private Long userId;

    /** 业务参数 */
    @Builder.Default
    private Map<String, Object> params = new HashMap<>();

    /**
     * 获取参数（带类型转换）
     *
     * @param key  参数名
     * @param type 期望类型
     * @return 参数值，不存在则返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T getParam(String key, Class<T> type) {
        Object value = params.get(key);
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return (T) value;
        }
        // 数值类型转换
        if (value instanceof Number number) {
            if (type == Integer.class) return (T) Integer.valueOf(number.intValue());
            if (type == Long.class) return (T) Long.valueOf(number.longValue());
            if (type == Double.class) return (T) Double.valueOf(number.doubleValue());
        }
        throw new IllegalArgumentException(
                "参数类型不匹配: key=" + key + ", expected=" + type + ", actual=" + value.getClass());
    }

    /**
     * 获取字符串参数
     */
    public String getString(String key) {
        Object value = params.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 添加参数（链式调用）
     */
    public ToolInput with(String key, Object value) {
        this.params.put(key, value);
        return this;
    }
}
