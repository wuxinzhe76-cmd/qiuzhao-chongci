package com.charles.interview.arena.agent.harness.common;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Token 预算管理器（Token Budget）
 * <p>
 * 关联 Harness 层：L5 资源管理层
 * 关联八股题号：Day04 #8 上下文、Day05 Token 预算与成本控制
 * <p>
 * 核心职责：
 * 三级 Token 预算控制，防止单次会话或单轮对话消耗过多 Token 导致成本失控：
 * <pre>
 * ┌─────────────────┬───────────┬──────────────────────────────────┐
 * │ 预算级别         │ 默认值     │ 说明                              │
 * ├─────────────────┼───────────┼──────────────────────────────────┤
 * │ perRoundBudget   │ 5,000     │ 每轮对话预算，超过则停止本轮       │
 * │ perSessionBudget │ 20,000    │ 每个会话预算，超过则终止会话       │
 * │ globalBudget     │ 100,000   │ 全局预算，超过则停止所有会话       │
 * └─────────────────┴───────────┴──────────────────────────────────┘
 * </pre>
 *
 * <pre>
 * 使用示例：
 *   TokenBudget budget = new TokenBudget(100000, 20000, 5000);
 *   budget.consume("session-001", 1500);  // 消耗 1500 Token
 *   BudgetStatus status = budget.check("session-001");
 *   if (status == BudgetStatus.SESSION_STOP) { ... }
 *   budget.resetRound();  // 每轮结束重置
 * </pre>
 */
@Slf4j
public class TokenBudget {

    /**
     * 预算状态枚举
     */
    public enum BudgetStatus {
        /** 预算充足，可以继续 */
        OK,
        /** 会话预算耗尽，终止当前会话 */
        SESSION_STOP,
        /** 全局预算耗尽，终止所有会话 */
        GLOBAL_STOP
    }

    /** 全局 Token 预算 */
    private final int globalBudget;

    /** 每个会话的 Token 预算 */
    private final int perSessionBudget;

    /** 每轮对话的 Token 预算 */
    private final int perRoundBudget;

    /** 全局已消耗 Token 数 */
    private final AtomicInteger globalConsumed = new AtomicInteger(0);

    /** 每个会话已消耗 Token 数（sessionId -> consumed） */
    private final Map<String, AtomicInteger> sessionConsumed = new ConcurrentHashMap<>();

    /** 当前轮次已消耗 Token 数 */
    private final AtomicInteger roundConsumed = new AtomicInteger(0);

    /**
     * 构造 Token 预算管理器
     *
     * @param globalBudget     全局预算（默认 100000）
     * @param perSessionBudget 每会话预算（默认 20000）
     * @param perRoundBudget   每轮预算（默认 5000）
     */
    public TokenBudget(int globalBudget, int perSessionBudget, int perRoundBudget) {
        this.globalBudget = globalBudget;
        this.perSessionBudget = perSessionBudget;
        this.perRoundBudget = perRoundBudget;
        log.info("Token 预算初始化: global={}, perSession={}, perRound={}",
                globalBudget, perSessionBudget, perRoundBudget);
    }

    /**
     * 默认构造：globalBudget=100000, perSessionBudget=20000, perRoundBudget=5000
     */
    public TokenBudget() {
        this(100000, 20000, 5000);
    }

    /**
     * 消耗 Token
     * <p>
     * 在三个级别（全局、会话、轮次）同时累加消耗量。
     *
     * @param sessionId 会话 ID
     * @param tokens    消耗的 Token 数
     */
    public void consume(String sessionId, int tokens) {
        globalConsumed.addAndGet(tokens);
        roundConsumed.addAndGet(tokens);
        sessionConsumed.computeIfAbsent(sessionId, k -> new AtomicInteger(0)).addAndGet(tokens);
        log.debug("Token 消耗: session={}, tokens={}, roundTotal={}, sessionTotal={}, globalTotal={}",
                sessionId, tokens, roundConsumed.get(),
                sessionConsumed.get(sessionId).get(), globalConsumed.get());
    }

    /**
     * 检查是否超预算
     * <p>
     * 检查顺序：全局 -> 会话 -> 轮次。一旦某级别超预算，立即返回对应状态。
     *
     * @param sessionId 会话 ID
     * @return 预算状态（OK / SESSION_STOP / GLOBAL_STOP）
     */
    public BudgetStatus check(String sessionId) {
        // 全局预算检查（最高优先级）
        if (globalConsumed.get() >= globalBudget) {
            log.warn("全局 Token 预算耗尽: {}/{}", globalConsumed.get(), globalBudget);
            return BudgetStatus.GLOBAL_STOP;
        }

        // 会话预算检查
        AtomicInteger sessionUsed = sessionConsumed.get(sessionId);
        if (sessionUsed != null && sessionUsed.get() >= perSessionBudget) {
            log.warn("会话 Token 预算耗尽: session={}, {}/{}", sessionId, sessionUsed.get(), perSessionBudget);
            return BudgetStatus.SESSION_STOP;
        }

        return BudgetStatus.OK;
    }

    /**
     * 每轮结束重置轮次预算
     * <p>
     * 注意：只重置轮次消耗，不重置会话和全局消耗。
     * 应在每轮 Agent 对话结束后调用。
     */
    public void resetRound() {
        int consumed = roundConsumed.getAndSet(0);
        log.info("轮次重置，本轮消耗: {} Token", consumed);
    }

    /**
     * 返回指定会话的预算摘要
     *
     * @param sessionId 会话 ID
     * @return 预算摘要字符串
     */
    public String summary(String sessionId) {
        AtomicInteger sessionUsed = sessionConsumed.getOrDefault(sessionId, new AtomicInteger(0));
        return String.format(
                "Token 预算摘要 [session=%s]: 全局 %d/%d, 会话 %d/%d, 本轮 %d/%d",
                sessionId,
                globalConsumed.get(), globalBudget,
                sessionUsed.get(), perSessionBudget,
                roundConsumed.get(), perRoundBudget
        );
    }

    /**
     * 获取当前轮次已消耗的 Token 数
     *
     * @return 本轮已消耗 Token 数
     */
    public int getRoundConsumed() {
        return roundConsumed.get();
    }

    /**
     * 获取全局已消耗的 Token 数
     *
     * @return 全局已消耗 Token 数
     */
    public int getGlobalConsumed() {
        return globalConsumed.get();
    }

    // ===== 兼容方法（供已有调用方使用） =====

    /**
     * 估算文本的 Token 数（粗略估算：字符数 / 4）
     * <p>
     * 中文约 1 字 = 1~2 Token，英文约 4 字符 = 1 Token，
     * 取折中值字符数 / 4 作为粗略估算。
     *
     * @param text 输入文本
     * @return 估算的 Token 数
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / 4;
    }

    /**
     * 检查是否有足够的 Token 预算
     * <p>
     * 检查全局和当前轮次预算是否足够容纳指定数量的 Token。
     *
     * @param tokens 需要消耗的 Token 数
     * @return true 表示预算充足
     */
    public boolean checkBudget(int tokens) {
        if (globalConsumed.get() + tokens >= globalBudget) {
            log.warn("全局 Token 预算不足: {}/{} + {}", globalConsumed.get(), globalBudget, tokens);
            return false;
        }
        if (roundConsumed.get() + tokens >= perRoundBudget) {
            log.warn("轮次 Token 预算不足: {}/{} + {}", roundConsumed.get(), perRoundBudget, tokens);
            return false;
        }
        return true;
    }

    /**
     * 记录 Token 消耗（无需 sessionId 的便捷方法）
     *
     * @param tokens 消耗的 Token 数
     */
    public void recordUsage(int tokens) {
        globalConsumed.addAndGet(tokens);
        roundConsumed.addAndGet(tokens);
        log.debug("Token 记录: tokens={}, roundTotal={}, globalTotal={}",
                tokens, roundConsumed.get(), globalConsumed.get());
    }
}
