package com.charles.interview.arena.agent.llm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 模块配置属性
 * <p>
 * 对应 application.yaml 中 interview.llm 前缀的配置。
 * 与 spring.ai.openai（OpenAI 兼容自动配置）互补：
 * - spring.ai.openai：配置 api-key、base-url、model、temperature（OpenAI Starter 自动读取）
 * - interview.llm：配置业务层的模型用途分类、备用模型、Token 预算
 * <p>
 * 设计参考：LangChain 的 ChatModel 分离模式
 * - primary：主模型（面试/RAG 日常使用）
 * - fallback：备用模型（主模型故障时降级，用更轻量的模型降低成本）
 * - budget：Token 预算三级控制
 */
@Data
@ConfigurationProperties(prefix = "interview.llm")
public class LlmProperties {

    /** 主模型配置 */
    private ModelConfig primary = new ModelConfig();

    /** 备用模型配置（主模型故障时降级） */
    private ModelConfig fallback = new ModelConfig();

    /** Token 预算配置 */
    private BudgetConfig budget = new BudgetConfig();

    @Data
    public static class ModelConfig {
        /** 模型名称（如 MiniMax-M3 / abab6.5s-chat） */
        private String model = "MiniMax-M3";

        /** 温度参数（0-2，越高越随机，面试场景用 0.7） */
        private double temperature = 0.7;

        /** 该模型的系统提示词（留空则由 LlmConfig 按用途注入） */
        private String systemPrompt;
    }

    @Data
    public static class BudgetConfig {
        /** 全局 Token 预算（所有会话共享） */
        private int global = 100000;

        /** 单会话 Token 预算 */
        private int session = 20000;

        /** 单轮对话 Token 预算 */
        private int round = 5000;
    }
}
