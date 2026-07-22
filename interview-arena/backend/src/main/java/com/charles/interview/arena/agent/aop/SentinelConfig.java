package com.charles.interview.arena.agent.aop;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Sentinel 限流配置
 * <p>
 * 两层限流：
 * 1. HTTP 接口限流（JwtInterceptor 中 SphU.entry(path)）
 *    - 按路径做 QPS 限制，防止单接口被刷爆
 *    - origin = "user:{userId}" 或 "anonymous"，支持按用户限流
 * <p>
 * 2. 工具调用限流（ToolExecutor 中 SphU.entry(toolName)）
 *    - 按工具名做 QPS 限制，防止高并发打 MySQL
 * <p>
 * 限流触发时：
 * - HTTP 层：JwtInterceptor 抛 BusinessException("请求过于频繁")
 * - 工具层：ToolExecutor 返回 ToolResult.failure("系统繁忙")
 */
@Slf4j
@Configuration
public class SentinelConfig {

    @Bean
    public String initSentinelRules() {
        List<FlowRule> rules = new ArrayList<>();

        // ==================== HTTP 接口限流 ====================

        // 认证接口（防暴力登录/注册）
        rules.add(createFlowRule("/api/user/login", 5));
        rules.add(createFlowRule("/api/user/register", 3));
        rules.add(createFlowRule("/api/user/refresh", 5));

        // 面试接口（LLM 调用耗时，低 QPS 保护）
        rules.add(createFlowRule("/api/interview/start", 10));
        rules.add(createFlowRule("/api/interview/answer", 10));

        // RAG 接口（检索 + LLM 生成，中等 QPS）
        rules.add(createFlowRule("/api/rag/chat", 20));
        rules.add(createFlowRule("/api/rag/suggest", 50));

        // 题目浏览（公开接口，高 QPS）
        rules.add(createFlowRule("/api/question/list/page/vo", 100));
        rules.add(createFlowRule("/api/question/get/vo", 100));

        // 判题接口
        rules.add(createFlowRule("/api/judge/do", 5));

        // ==================== 工具调用限流（模型可调用工具） ====================

        rules.add(createFlowRule("pickQuestion", 20));
        rules.add(createFlowRule("getQuestionDetail", 100));
        rules.add(createFlowRule("getWeakPoints", 50));
        rules.add(createFlowRule("retrieveKnowledge", 20));
        rules.add(createFlowRule("retrieveMemory", 20));
        rules.add(createFlowRule("webSearch", 10));

        FlowRuleManager.loadRules(rules);
        log.info("Sentinel 限流规则已加载: {} 条（HTTP {} + 工具 {}）",
                rules.size(), 10, 6);

        return "sentinelRulesLoaded";
    }

    /**
     * 创建 QPS 限流规则
     *
     * @param resource 资源名（HTTP 路径 或 工具名）
     * @param qps      每秒最大请求数
     */
    private FlowRule createFlowRule(String resource, int qps) {
        FlowRule rule = new FlowRule();
        rule.setResource(resource);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(qps);
        rule.setLimitApp("default");
        return rule;
    }
}
