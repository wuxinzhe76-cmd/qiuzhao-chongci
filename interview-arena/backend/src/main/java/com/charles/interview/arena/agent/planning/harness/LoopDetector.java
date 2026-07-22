package com.charles.interview.arena.agent.planning.harness;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 循环检测器（Loop Detector）
 * <p>
 * 关联 Harness 层：L5 可观测性层
 * 关联八股题号：Day04 #7 死循环、Day05 循环检测与终止
 * <p>
 * 核心职责：
 * 检测 Agent 是否进入了循环行为，三种检测策略：
 * <pre>
 * 1. 连续相同操作：Agent 连续执行相同的 action+params 超过 maxSameAction 次
 * 2. 最大轮次限制：Agent 执行轮次超过 maxRounds 次
 * 3. Ping-Pong 模式：Agent 在两个操作之间来回切换（A->B->A->B->...）
 * </pre>
 *
 * <pre>
 * 使用示例：
 *   LoopDetector detector = new LoopDetector(10, 3);
 *   Long sessionId = 1L;
 *   detector.detectLoop(sessionId, "Java基础");
 *   detector.detectLoop(sessionId, "Java基础");
 *   if (detector.detectLoop(sessionId, "Java基础")) {
 *       log.warn("Agent 进入循环: {}", detector.getViolation(sessionId));
 *       // 终止 Agent
 *   }
 * </pre>
 */
@Slf4j
public class LoopDetector {

    /** 默认 session ID，供 deprecated 的 record 方法使用 */
    private static final Long DEFAULT_SESSION_ID = 0L;

    /** 最大执行轮次，超过视为循环 */
    private final int maxRounds;

    /** 连续相同操作的最大容忍次数，超过视为循环 */
    private final int maxSameAction;

    /** 各会话的操作历史记录，key 为 sessionId，value 为该会话的 "action:params" 列表 */
    private final ConcurrentHashMap<Long, List<String>> sessionHistories = new ConcurrentHashMap<>();

    /** 各会话的当前违规原因，key 为 sessionId，value 为违规描述 */
    private final ConcurrentHashMap<Long, String> sessionViolations = new ConcurrentHashMap<>();

    /**
     * 构造循环检测器
     *
     * @param maxRounds     最大轮次（默认 10）
     * @param maxSameAction 连续相同操作最大次数（默认 3）
     */
    public LoopDetector(int maxRounds, int maxSameAction) {
        this.maxRounds = maxRounds;
        this.maxSameAction = maxSameAction;
        log.info("循环检测器初始化: maxRounds={}, maxSameAction={}", maxRounds, maxSameAction);
    }

    /**
     * 默认构造：maxRounds=10, maxSameAction=3
     */
    public LoopDetector() {
        this(10, 3);
    }

    /**
     * 记录一次 Agent 操作
     *
     * @param action 操作名称（如 "search", "save", "delete"）
     * @param params 操作参数（如 "Java基础"）
     * @deprecated 该方法操作默认 session（ID=0），不适合多会话并发场景。
     *             请使用 {@link #detectLoop(Long, String)} 按 session 维度记录和检测。
     */
    @Deprecated
    public void record(String action, String params) {
        List<String> history = getOrCreateHistory(DEFAULT_SESSION_ID);
        String entry = action + ":" + (params == null ? "" : params);
        history.add(entry);
        log.debug("循环检测器记录[默认session]: {} (总计 {} 次)", entry, history.size());
    }

    /**
     * 检测是否循环（便捷方法）
     * <p>
     * 记录当前会话操作并立即检测是否循环，一步到位。
     * 适用于 InterviewServiceImpl 等调用方按会话维度检测循环。
     *
     * @param sessionId 会话 ID
     * @param answer    用户回答内容
     * @return true 表示检测到循环
     */
    public boolean detectLoop(Long sessionId, String answer) {
        List<String> history = getOrCreateHistory(sessionId);
        String entry = "answer:" + (answer == null ? "" : answer);
        history.add(entry);
        log.debug("循环检测器记录[session={}]: {} (总计 {} 次)", sessionId, entry, history.size());

        String violation = detectViolation(history);
        if (violation != null) {
            sessionViolations.put(sessionId, violation);
            log.warn("循环检测[session={}]: {}", sessionId, violation);
            return true;
        }
        return false;
    }

    /**
     * 检测给定历史记录是否构成循环
     * <p>
     * 按顺序执行三种检测：
     * 1. 最大轮次检测
     * 2. 连续相同操作检测
     * 3. Ping-Pong 模式检测
     * 一旦检测到任一循环模式，立即返回 true。
     * <p>
     * 注意：此方法仅做检测，不存储违规原因。如需获取违规描述，
     * 请通过 {@link #detectLoop(Long, String)} 触发检测后调用 {@link #getViolation(Long)}。
     *
     * @param history 某一会话的操作历史记录
     * @return true 表示检测到循环
     */
    public boolean isLooping(List<String> history) {
        return detectViolation(history) != null;
    }

    /**
     * 获取指定会话的违规原因
     *
     * @param sessionId 会话 ID
     * @return 违规原因描述，如果未检测到循环则返回 null
     */
    public String getViolation(Long sessionId) {
        return sessionViolations.get(sessionId);
    }

    /**
     * 检测连续相同操作
     * <p>
     * 从最近一条记录往前数，统计连续相同的操作次数。
     *
     * @param history 操作历史记录
     * @return true 表示连续相同操作超过阈值
     */
    private boolean checkConsecutiveSameAction(List<String> history) {
        if (history.size() < maxSameAction) {
            return false;
        }
        String lastAction = history.get(history.size() - 1);
        int consecutiveCount = 1;
        for (int i = history.size() - 2; i >= 0; i--) {
            if (history.get(i).equals(lastAction)) {
                consecutiveCount++;
            } else {
                break;
            }
        }
        return consecutiveCount >= maxSameAction;
    }

    /**
     * 检测 Ping-Pong 模式
     * <p>
     * 检查最近 6 条记录是否呈现 A->B->A->B->A->B 交替模式。
     *
     * @param history 操作历史记录
     * @return true 表示检测到 Ping-Pong 模式
     */
    private boolean checkPingPongPattern(List<String> history) {
        int size = history.size();
        if (size < 6) {
            return false;
        }
        String a = history.get(size - 1);
        String b = history.get(size - 2);
        if (a.equals(b)) {
            return false; // 相同操作不算 Ping-Pong
        }
        // 检查最近 6 条是否为 A->B->A->B->A->B 交替
        for (int i = 1; i <= 2; i++) {
            String expectedA = history.get(size - 1 - (i * 2));
            String expectedB = history.get(size - 2 - (i * 2));
            if (!expectedA.equals(a) || !expectedB.equals(b)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 内部检测方法：返回违规原因，null 表示未检测到循环
     *
     * @param history 操作历史记录
     * @return 违规原因描述，未检测到循环时返回 null
     */
    private String detectViolation(List<String> history) {
        // 1. 最大轮次检测
        if (history.size() > maxRounds) {
            return String.format("超过最大轮次限制: %d/%d", history.size(), maxRounds);
        }

        // 2. 连续相同操作检测
        if (checkConsecutiveSameAction(history)) {
            return String.format("连续相同操作超过阈值 %d 次", maxSameAction);
        }

        // 3. Ping-Pong 模式检测（A->B->A->B->...）
        if (checkPingPongPattern(history)) {
            return "检测到 Ping-Pong 循环模式";
        }

        return null;
    }

    /**
     * 获取或创建指定会话的操作历史记录
     * <p>
     * sessionId 为 null 时使用默认 session ID。
     * 返回的 List 是线程安全的（Collections.synchronizedList 包装）。
     *
     * @param sessionId 会话 ID
     * @return 该会话的操作历史记录列表
     */
    private List<String> getOrCreateHistory(Long sessionId) {
        Long key = sessionId == null ? DEFAULT_SESSION_ID : sessionId;
        return sessionHistories.computeIfAbsent(key,
                k -> Collections.synchronizedList(new ArrayList<>()));
    }

    /**
     * 清理指定会话的历史记录和违规信息
     * <p>
     * 面试结束时调用，释放该会话占用的内存。
     *
     * @param sessionId 会话 ID
     */
    public void clear(Long sessionId) {
        sessionHistories.remove(sessionId);
        sessionViolations.remove(sessionId);
        log.info("循环检测器已清理 session: {}", sessionId);
    }

    /**
     * 重置检测器，清空所有会话的历史记录和违规信息
     */
    public void reset() {
        sessionHistories.clear();
        sessionViolations.clear();
        log.info("循环检测器已重置（所有 session 已清空）");
    }

    /**
     * 获取指定会话的当前操作历史记录数
     *
     * @param sessionId 会话 ID
     * @return 已记录的操作次数，会话不存在时返回 0
     */
    public int getActionCount(Long sessionId) {
        List<String> history = sessionHistories.get(sessionId);
        return history == null ? 0 : history.size();
    }
}
