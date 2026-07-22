package com.charles.interview.arena.agent.harness.common;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 熔断器（Circuit Breaker）
 * <p>
 * 关联 Harness 层：L2 容错层 + L4 可靠性层
 * 关联八股题号：Day04 #7 死循环、Day05 熔断与降级
 * <p>
 * 核心职责：
 * 在 Agent 调用 LLM 或外部工具时，当失败次数达到阈值后自动「熔断」（断路），
 * 阻止后续请求直接打到下游，给下游恢复时间。冷却后进入半开状态试探性放行。
 * <p>
 * 三状态状态机：
 * <pre>
 *    失败达 failureThreshold          冷却 resetTimeout 到期
 *    ──────────────────────►   OPEN  ──────────────────────►  HALF_OPEN
 *   CLOSED                      │                              │
 *    ▲                          │                              │
 *    │ 成功达 successThreshold   │ 直接拒绝请求                  │ 成功达 successThreshold -> CLOSED
 *    └──────────────────────────┘                              │ 失败 -> OPEN
 *                                                              ▼
 * </pre>
 * 状态说明：
 * - CLOSED（关闭）：正常放行请求，统计失败次数
 * - OPEN（打开）：直接拒绝请求，抛出 CircuitBreakerOpenException
 * - HALF_OPEN（半开）：放行少量请求试探，成功达阈值则关闭，失败则重新打开
 *
 * <pre>
 * 使用示例：
 *   CircuitBreaker breaker = new CircuitBreaker(5, 60, 2);
 *   String result = breaker.call(() -> llmClient.chat(prompt));
 * </pre>
 */
@Slf4j
public class CircuitBreaker {

    /**
     * 熔断器状态枚举
     */
    public enum State {
        /** 关闭：正常放行请求 */
        CLOSED,
        /** 打开：直接拒绝请求 */
        OPEN,
        /** 半开：试探性放行少量请求 */
        HALF_OPEN
    }

    /** 失败次数阈值，达到后从 CLOSED -> OPEN */
    private final int failureThreshold;

    /** 冷却时间（秒），OPEN 状态持续时间，到期后 -> HALF_OPEN */
    private final long resetTimeoutSeconds;

    /** 半开状态下连续成功次数阈值，达到后从 HALF_OPEN -> CLOSED */
    private final int successThreshold;

    /** 当前状态 */
    private volatile State state = State.CLOSED;

    /** 当前失败次数（CLOSED 状态下累计） */
    private final AtomicInteger failureCount = new AtomicInteger(0);

    /** 半开状态下成功次数（HALF_OPEN 状态下累计） */
    private final AtomicInteger successCount = new AtomicInteger(0);

    /** 进入 OPEN 状态的时间戳 */
    private volatile Instant openedAt = null;

    /**
     * 构造熔断器
     *
     * @param failureThreshold   失败次数阈值（默认 5）
     * @param resetTimeoutSeconds 冷却时间秒数（默认 60）
     * @param successThreshold   半开状态成功次数阈值（默认 2）
     */
    public CircuitBreaker(int failureThreshold, long resetTimeoutSeconds, int successThreshold) {
        this.failureThreshold = failureThreshold;
        this.resetTimeoutSeconds = resetTimeoutSeconds;
        this.successThreshold = successThreshold;
        log.info("熔断器初始化: failureThreshold={}, resetTimeout={}s, successThreshold={}",
                failureThreshold, resetTimeoutSeconds, successThreshold);
    }

    /**
     * 默认构造：failureThreshold=5, resetTimeout=60s, successThreshold=2
     */
    public CircuitBreaker() {
        this(5, 60, 2);
    }

    /**
     * 通过熔断器调用函数
     * <p>
     * 根据当前状态决定是否放行：
     * - CLOSED：放行，成功重置失败计数，失败累加失败计数
     * - OPEN：检查是否冷却到期，到期转 HALF_OPEN 并放行，否则直接拒绝
     * - HALF_OPEN：放行，成功累加成功计数（达阈值转 CLOSED），失败转 OPEN
     *
     * @param supplier 要执行的函数
     * @param <T>      返回类型
     * @return 函数执行结果
     * @throws CircuitBreakerOpenException 当熔断器处于 OPEN 状态时抛出
     */
    public <T> T call(Supplier<T> supplier) {
        // 检查是否可以从 OPEN -> HALF_OPEN
        if (state == State.OPEN) {
            if (isCooldownExpired()) {
                transitionTo(State.HALF_OPEN);
            } else {
                log.warn("熔断器处于 OPEN 状态，拒绝请求");
                throw new CircuitBreakerOpenException("熔断器已打开，请稍后重试");
            }
        }

        try {
            T result = supplier.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }

    /**
     * 通过熔断器调用函数（别名，等价于 call）
     * <p>
     * 兼容已有调用方使用 executeProtected 命名。
     *
     * @param supplier 要执行的函数
     * @param <T>      返回类型
     * @return 函数执行结果
     * @throws CircuitBreakerOpenException 当熔断器处于 OPEN 状态时抛出
     */
    public <T> T executeProtected(Supplier<T> supplier) {
        return call(supplier);
    }

    /**
     * 获取当前熔断器状态
     *
     * @return 当前状态
     */
    public State getState() {
        return state;
    }

    /**
     * 调用成功时的处理
     */
    private void onSuccess() {
        if (state == State.HALF_OPEN) {
            int successes = successCount.incrementAndGet();
            log.info("熔断器半开状态成功 {}/{}", successes, successThreshold);
            if (successes >= successThreshold) {
                transitionTo(State.CLOSED);
            }
        } else if (state == State.CLOSED) {
            failureCount.set(0);
        }
    }

    /**
     * 调用失败时的处理
     */
    private void onFailure() {
        if (state == State.HALF_OPEN) {
            log.warn("熔断器半开状态失败，重新打开");
            transitionTo(State.OPEN);
        } else if (state == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            log.warn("熔断器失败计数: {}/{}", failures, failureThreshold);
            if (failures >= failureThreshold) {
                transitionTo(State.OPEN);
            }
        }
    }

    /**
     * 检查冷却时间是否已到期
     *
     * @return true 表示冷却已到期
     */
    private boolean isCooldownExpired() {
        return openedAt != null
                && Instant.now().isAfter(openedAt.plusSeconds(resetTimeoutSeconds));
    }

    /**
     * 状态转换
     *
     * @param newState 新状态
     */
    private void transitionTo(State newState) {
        State oldState = this.state;
        this.state = newState;
        log.info("熔断器状态转换: {} -> {}", oldState, newState);

        switch (newState) {
            case CLOSED -> {
                failureCount.set(0);
                successCount.set(0);
                openedAt = null;
            }
            case OPEN -> {
                openedAt = Instant.now();
                successCount.set(0);
            }
            case HALF_OPEN -> {
                successCount.set(0);
                failureCount.set(0);
            }
        }
    }

    /**
     * 熔断器打开异常
     * <p>
     * 当熔断器处于 OPEN 状态时，拒绝请求并抛出此异常。
     * 调用方可捕获此异常执行降级逻辑。
     */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }
}
