package com.charles.interview.arena.agent.observability;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 可观测性服务(统一入口)
 * <p>
 * 对应 Python SDK 的 TraceAPI + SessionSDK + ToolSDK。
 * <p>
 * 职责:提供链路追踪的显式 API,采集各层的 Span 数据。
 * <p>
 * 使用方式:
 * <pre>
 * // 方式1:显式 API
 * TraceContext ctx = observabilityService.startSpan("llm.call", SpanData.Kind.LLM);
 * try {
 *     // 业务逻辑
 *     observabilityService.addAttribute("model", "MiniMax-M3");
 *     observabilityService.addAttribute("tokenCount", 1500);
 * } finally {
 *     observabilityService.endSpan(SpanData.Kind.LLM, "SUCCESS");
 * }
 *
 * // 方式2:便捷方法(自动计时)
 * observabilityService.recordSpan("tool.execute", SpanData.Kind.TOOL, () -> {
 *     return toolExecutor.execute("pickQuestion", input);
 * });
 * </pre>
 */
@Service
public class ObservabilityService {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityService.class);

    private final AsyncBatchUploader uploader;

    public ObservabilityService(AsyncBatchUploader uploader) {
        this.uploader = uploader;
    }

    /**
     * 开始一个新 trace(请求入口)
     */
    public TraceContext startTrace(String traceName) {
        TraceContext ctx = TraceContext.startTrace(traceName);
        log.debug("Trace 开始: traceId={}, name={}", ctx.getTraceId(), traceName);
        return ctx;
    }

    /**
     * 开始一个子 span
     */
    public TraceContext startSpan(String spanName, SpanData.Kind kind) {
        TraceContext ctx = TraceContext.startSpan(spanName);
        log.debug("Span 开始: traceId={}, spanId={}, name={}, kind={}",
                ctx.getTraceId(), ctx.getSpanId(), spanName, kind);
        return ctx;
    }

    /**
     * 结束当前 span(自动计算耗时并上报)
     */
    public void endSpan(SpanData.Kind kind, String status) {
        endSpan(kind, status, null);
    }

    /**
     * 结束当前 span(带属性)
     */
    public void endSpan(SpanData.Kind kind, String status, Map<String, Object> attributes) {
        TraceContext ctx = TraceContext.getCurrent();
        if (ctx == null) {
            log.warn("结束 span 时无上下文,跳过");
            return;
        }

        long endTimeMs = System.currentTimeMillis();
        SpanData span = SpanData.of(
                ctx.getTraceId(),
                ctx.getSpanId(),
                ctx.getParentSpanId(),
                ctx.getClass().getName(), // 简化:用类名作为 span name
                kind.name(),
                ctx.getStartTimeMs(),
                endTimeMs,
                status,
                attributes
        );

        uploader.addSpan(span);
        log.debug("Span 结束: spanId={}, duration={}ms, status={}",
                ctx.getSpanId(), span.durationMs(), status);

        TraceContext.end();
    }

    /**
     * 便捷方法:记录一个 span(自动计时)
     *
     * @param name       Span 名称
     * @param kind       Span 类型
     * @param supplier   业务逻辑
     * @return 业务逻辑的返回值
     */
    public <T> T recordSpan(String name, SpanData.Kind kind, java.util.function.Supplier<T> supplier) {
        TraceContext ctx = startSpan(name, kind);
        try {
            T result = supplier.get();
            endSpan(kind, "SUCCESS");
            return result;
        } catch (Exception e) {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("error", e.getMessage());
            endSpan(kind, "FAILURE", attrs);
            throw e;
        }
    }

    /**
     * 添加属性到当前 span(在 endSpan 前调用)
     */
    public Map<String, Object> attributes() {
        return new HashMap<>(); // 调用方填充后传给 endSpan
    }
}
