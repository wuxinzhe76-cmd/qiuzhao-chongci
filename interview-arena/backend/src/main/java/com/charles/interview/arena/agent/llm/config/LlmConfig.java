package com.charles.interview.arena.agent.llm.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;

import com.charles.interview.arena.agent.llm.prompt.PromptManager;
import com.charles.interview.arena.agent.harness.common.FallbackChain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * LLM 模块配置（Bean 注册中心）
 * <p>
 * 职责：创建和管理所有 ChatClient Bean，注册降级链。
 * <p>
 * 两个 starter 共存：OpenAI（MiniMax Chat）+ DashScope（Embedding + Rerank）
 * OpenAI ChatModel 标记为 @Primary，作为 ChatClient.Builder 的默认 ChatModel。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
@RequiredArgsConstructor
public class LlmConfig {

    private final LlmProperties llmProperties;
    private final PromptManager promptManager;

    /**
     * 将 OpenAI ChatModel（MiniMax）标记为 @Primary
     * <p>
     * 两个 starter 共存时，Spring 会找到 dashscopeChatModel 和 openAiChatModel 两个 ChatModel，
     * 标记 openAiChatModel 为 @Primary，让 ChatClient.Builder 使用 MiniMax 而非 DashScope。
     */
    @Bean
    @Primary
    public ChatModel primaryChatModel(@Qualifier("openAiChatModel") ChatModel openAiChatModel) {
        return openAiChatModel;
    }

    // ==================== 1. 面试用 ChatClient ====================

    /**
     * 智能面试助手专用 ChatClient
     * <p>
     * 系统提示词从 PromptManager 加载（版本化管理）
     */
    @Bean
    @Primary
    public ChatClient interviewChatClient(ChatClient.Builder builder) {
        String systemPrompt = promptManager.get("interview-system-prompt");
        ChatClient client = builder
                .defaultSystem(systemPrompt)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();

        log.info("面试 ChatClient 创建完成: model={}, promptVersion={}",
                llmProperties.getPrimary().getModel(),
                promptManager.getVersion("interview-system-prompt"));
        return client;
    }

    // ==================== 2. RAG 用 ChatClient ====================

    /**
     * 提问助手（RAG）专用 ChatClient
     * <p>
     * 系统提示词：面试教练角色，基于知识库回答
     * RAG 流程在 RagService 中手动 DAG 编排（混合检索 + Rerank + 引用标注）
     */
    @Bean("ragChatClient")
    public ChatClient ragChatClient(ChatClient.Builder builder) {
        ChatClient client = builder
                .defaultSystem("你是一个专业的面试教练，基于面试题知识库回答用户问题，回答要准确、有条理。")
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();

        log.info("RAG ChatClient 创建完成");
        return client;
    }

    // ==================== 3. 降级 ChatClient ====================

    /**
     * 降级兜底 ChatClient
     * <p>
     * 主模型故障时使用，轻量系统提示词降低 Token 消耗
     */
    @Bean("fallbackChatClient")
    public ChatClient fallbackChatClient(ChatClient.Builder builder) {
        ChatClient client = builder
                .defaultSystem("你是面试教练。请简洁回答。")
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();

        log.info("降级 ChatClient 创建完成: model={}", llmProperties.getFallback().getModel());
        return client;
    }

    // ==================== 4. 降级链注册 ====================

    /**
     * 将主备 ChatClient 注册到 FallbackChain
     * <p>
     * 降级顺序：interviewChatClient -> fallbackChatClient -> 兜底响应
     */
    @Bean
    public String fallbackChainRegistration(
            ChatClient interviewChatClient,
            @org.springframework.beans.factory.annotation.Qualifier("fallbackChatClient") ChatClient fallbackChatClient,
            FallbackChain fallbackChain) {

        fallbackChain.registerPrimary(interviewChatClient);
        fallbackChain.registerFallback(fallbackChatClient);

        log.info("降级链注册完成: primary -> fallback -> 兜底响应");
        return "fallbackChainRegistered";
    }
}
