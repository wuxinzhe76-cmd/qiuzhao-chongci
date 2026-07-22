package com.charles.interview.arena.agent.planning.harness;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 目标漂移检测器（Goal Drift Detector）
 * <p>
 * 关联 Harness 层：L5 熵管理（目标漂移防护）
 * 关联八股题号：Day04 #15 目标漂移
 * <p>
 * 核心问题：
 * LLM 充当面试官时，几轮对话后可能"忘记职责"，降级为普通知识讲解器。
 * 用户利用这一点不断提问，导致：
 * 1. 上下文膨胀 -> LLM 失忆更严重（恶性循环）
 * 2. 面试轮数推进缓慢 -> 无法完成评估
 * 3. Token 消耗失控
 * <p>
 * 防护策略（代码层拦截，不依赖 LLM 自律）：
 * - 检测用户是否在"提问"而非"回答"
 * - 第一次违规：警告 + 引导回面试
 * - 第二次违规：固定话术 + 强制换题
 * - 违规计数存 Redis（会话级，面试结束清理）
 * <p>
 * 设计原则：
 * - 检测到漂移时**跳过 LLM 调用**（省 Token + 防止上下文污染）
 * - 违规对话**不写入对话历史**（防止 LLM 被带偏）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoalDriftDetector {

    private final StringRedisTemplate stringRedisTemplate;

    /** Redis Key 前缀：违规计数 */
    private static final String DRIFT_COUNT_PREFIX = "interview:drift:";

    /** 最大违规次数：超过则强制换题 */
    private static final int MAX_VIOLATIONS = 2;

    /**
     * 提问模式检测（用户在"提问"而非"回答"）
     * <p>
     * 匹配规则：
     * 1. 以疑问词开头：什么是/为什么/怎么/请问/能不能/可以讲讲
     * 2. 包含问号且句子较短（典型的追问句式）
     * 3. 明确请求讲解：给我讲讲/帮我解释/详细说说
     */
    private static final List<Pattern> QUESTION_PATTERNS = List.of(
            // 明确的提问开头（只在短文本中判定）
            Pattern.compile("^(请问|能不能|可以.*吗|是不是|对吗|对不对)"),
            // 疑问词 + 问号结尾（短文本）
            Pattern.compile("^(什么是|什么叫|为什么|为何|怎么|如何).{0,20}[？?]$"),
            // 请求讲解 + 短文本
            Pattern.compile("(给我讲讲|帮我解释|详细说说|展开讲讲|具体说说|深入讲讲|你再*讲讲).{0,30}$"),
            // 疑问句（短文本以问号结尾）
            Pattern.compile("^.{5,30}[？?]$")
    );

    /**
     * 检测用户回答是否存在目标漂移
     * <p>
     * 调用时机：sanitizeInput 之后、evaluateAnswer 之前
     *
     * @param sessionId 会话 ID
     * @param answer    用户输入（已清洗）
     * @return null 表示正常（未漂移），非 null 表示检测到漂移（返回固定回复）
     */
    public String checkDrift(Long sessionId, String answer) {
        if (answer == null || answer.isBlank()) {
            return null;
        }

        // 1. 检测是否在"提问"而非"回答"
        if (!isAskingQuestion(answer)) {
            // 正常回答，重置违规计数
            resetViolations(sessionId);
            return null;
        }

        // 2. 检测到漂移，递增违规计数
        int violations = incrementViolations(sessionId);
        log.warn("目标漂移检测: sessionId={}, violations={}, answer={}",
                sessionId, violations, answer.length() > 50 ? answer.substring(0, 50) + "..." : answer);

        // 3. 根据违规次数返回固定话术
        if (violations == 1) {
            // 第一次违规：警告 + 引导
            return "面试过程中请不要主动提问。请回答当前问题，回答后我们会继续下一题。";
        } else {
            // 第二次及以上：固定话术 + 强制换题
            return "你不可以再进行一个询问了，请开始下一轮测试。";
        }
    }

    /**
     * 是否达到强制换题的违规次数
     */
    public boolean shouldForceNextQuestion(Long sessionId) {
        int violations = getViolations(sessionId);
        return violations >= MAX_VIOLATIONS;
    }

    /**
     * 检测用户是否在"提问"而非"回答"
     */
    private boolean isAskingQuestion(String answer) {
        // 超过 100 字符，大概率是正常回答而非提问，直接放行
        if (answer.length() > 100) {
            return false;
        }
        for (Pattern pattern : QUESTION_PATTERNS) {
            if (pattern.matcher(answer.trim()).find()) {
                return true;
            }
        }
        return false;
    }

    // ==================== Redis 违规计数 ====================

    private int incrementViolations(Long sessionId) {
        String key = DRIFT_COUNT_PREFIX + sessionId;
        Long count = stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, java.time.Duration.ofHours(2));
        return count != null ? count.intValue() : 1;
    }

    private int getViolations(Long sessionId) {
        String val = stringRedisTemplate.opsForValue().get(DRIFT_COUNT_PREFIX + sessionId);
        return val != null ? Integer.parseInt(val) : 0;
    }

    private void resetViolations(Long sessionId) {
        stringRedisTemplate.delete(DRIFT_COUNT_PREFIX + sessionId);
    }

    /**
     * 清理违规计数（面试结束时调用）
     */
    public void clear(Long sessionId) {
        stringRedisTemplate.delete(DRIFT_COUNT_PREFIX + sessionId);
    }
}
