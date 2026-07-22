package com.charles.interview.arena.agent.observability;

/**
 * 追踪上下文(ThreadLocal)
 * <p>
 * 管理 trace_id/span_id/parent_span_id,贯穿一次请求的所有层。
 * 对应 Python SDK 的 TraceContext(contextvars)。
 */
public class TraceContext {

    private static final ThreadLocal<TraceContext> CONTEXT = new ThreadLocal<>();

    private final String traceId;
    private String spanId;
    private String parentSpanId;
    private long startTimeMs;

    public TraceContext(String traceId) {
        this.traceId = traceId;
        this.spanId = generateId();
        this.parentSpanId = null;
        this.startTimeMs = System.currentTimeMillis();
    }

    /** 开始一个新的 trace */
    public static TraceContext startTrace(String traceName) {
        TraceContext ctx = new TraceContext(generateId());
        ctx.spanId = generateId();
        CONTEXT.set(ctx);
        return ctx;
    }

    /** 开始一个子 span */
    public static TraceContext startSpan(String spanName) {
        TraceContext parent = getCurrent();
        if (parent == null) {
            return startTrace(spanName);
        }
        TraceContext child = new TraceContext(parent.traceId);
        child.parentSpanId = parent.spanId;
        child.spanId = generateId();
        CONTEXT.set(child);
        return child;
    }

    /** 结束当前 span/trace */
    public static void end() {
        CONTEXT.remove();
    }

    /** 获取当前上下文 */
    public static TraceContext getCurrent() {
        return CONTEXT.get();
    }

    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public long getStartTimeMs() { return startTimeMs; }

    /** 生成唯一 ID(简化版 UUID) */
    private static String generateId() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
