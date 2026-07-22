package com.charles.interview.arena.agent.observability;

import java.util.HashMap;
import java.util.Map;

/**
 * Span 数据模型
 * <p>
 * 对应 Python SDK 的 SpanData,一次操作的可观测性数据。
 * 上报到 agent-observability 后端,存入 ClickHouse 供分析。
 *
 * @param traceId       追踪 ID(一次请求一个)
 * @param spanId        Span ID(一次操作一个)
 * @param parentSpanId  父 Span ID(构建调用链)
 * @param name          Span 名称(如 "llm.call"、"tool.execute")
 * @param kind          Span 类型(LLM/TOOL/MEMORY/ORCHESTRATE/PERCEPTION)
 * @param startTimeMs   开始时间
 * @param endTimeMs     结束时间
 * @param durationMs    耗时
 * @param status        状态(SUCCESS/FAILURE)
 * @param attributes    属性(如 model/tokenCount/toolName 等)
 */
public record SpanData(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        String kind,
        long startTimeMs,
        long endTimeMs,
        long durationMs,
        String status,
        Map<String, Object> attributes
) {
    public static SpanData of(String traceId, String spanId, String parentSpanId,
                              String name, String kind, long startTimeMs, long endTimeMs,
                              String status, Map<String, Object> attributes) {
        return new SpanData(traceId, spanId, parentSpanId, name, kind,
                startTimeMs, endTimeMs, endTimeMs - startTimeMs, status,
                attributes != null ? attributes : new HashMap<>());
    }

    public enum Kind {
        LLM,            // LLM 调用
        TOOL,           // 工具调用
        MEMORY,         // 记忆操作
        ORCHESTRATE,    // 编排操作
        PERCEPTION,     // 感知操作
        PLANNING,       // 规划操作
        REFLECTION      // 反思操作
    }
}
