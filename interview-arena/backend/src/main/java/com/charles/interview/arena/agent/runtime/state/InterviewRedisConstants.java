package com.charles.interview.arena.agent.runtime.state;

/**
 * 面试模块 Redis Key 常量（蓝图 §5.4）
 * <p>
 * 5 个 Key 前缀（统一 TTL 2h，会话级）：
 * <ul>
 *   <li>interview:history:{sessionId}    - List，滑动窗口最近 10 条对话</li>
 *   <li>interview:question:{sessionId}   - String，当前题目 ID</li>
 *   <li>interview:round:{sessionId}      - String，当前总轮次</li>
 *   <li>interview:questionRound:{sessionId} - String，当前题目已追问轮次</li>
 *   <li>interview:used:{sessionId}       - Set，已使用题目集</li>
 * </ul>
 * <p>
 * 蓝图明确要求使用 StringRedisTemplate（非 Redisson）。
 */
public final class InterviewRedisConstants {

    private InterviewRedisConstants() {}

    /** 对话历史（List，滑动窗口最近 10 条） */
    public static final String HISTORY_PREFIX = "interview:history:";

    /** 当前题目 ID（String） */
    public static final String QUESTION_PREFIX = "interview:question:";

    /** 当前总轮次（String） */
    public static final String ROUND_PREFIX = "interview:round:";

    /** 当前题目已追问轮次（String） */
    public static final String QUESTION_ROUND_PREFIX = "interview:questionRound:";

    /** 已使用题目集（Set） */
    public static final String USED_PREFIX = "interview:used:";

    /** Redis TTL（秒）= 2 小时 */
    public static final long TTL_SECONDS = 2 * 60 * 60L;

    /** 滑动窗口大小（最近 N 条对话） */
    public static final int HISTORY_WINDOW_SIZE = 10;

    public static String historyKey(Long sessionId) {
        return HISTORY_PREFIX + sessionId;
    }

    public static String questionKey(Long sessionId) {
        return QUESTION_PREFIX + sessionId;
    }

    public static String roundKey(Long sessionId) {
        return ROUND_PREFIX + sessionId;
    }

    public static String questionRoundKey(Long sessionId) {
        return QUESTION_ROUND_PREFIX + sessionId;
    }

    public static String usedKey(Long sessionId) {
        return USED_PREFIX + sessionId;
    }
}
