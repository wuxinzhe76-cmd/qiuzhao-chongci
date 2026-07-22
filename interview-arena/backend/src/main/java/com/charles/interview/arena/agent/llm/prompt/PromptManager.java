package com.charles.interview.arena.agent.llm.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * Prompt 管理器
 * <p>
 * 职责：从 YAML 资源文件加载 Prompt，支持版本追踪和 Spring AI 原生参数注入。
 * <p>
 * 设计目标：
 * 1. Prompt 与代码解耦 -- 修改 Prompt 不需要重新编译，只需改 YAML
 * 2. 版本追踪 -- 每个 Prompt 有 version，修改后递增，评分下降时可定位
 * 3. 原生注入 -- 通过 PromptRequest 返回模板+参数，由 LlmInvoker 使用 .text().param() 注入
 */
@Slf4j
@Component
public class PromptManager {

    private static final String PROMPT_FILE = "prompts/interview-prompts.yaml";

    /** Prompt ID -> Prompt 定义 */
    private final Map<String, PromptDefinition> prompts = new HashMap<>();

    @PostConstruct
    public void init() {
        try (InputStream is = new ClassPathResource(PROMPT_FILE).getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(is);

            @SuppressWarnings("unchecked")
            Map<String, Object> promptsMap = (Map<String, Object>) root.get("prompts");
            if (promptsMap == null) {
                log.error("Prompt 文件格式错误: 缺少 prompts 根节点");
                return;
            }

            for (Map.Entry<String, Object> entry : promptsMap.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> def = (Map<String, Object>) entry.getValue();
                PromptDefinition pd = new PromptDefinition();
                pd.setId(entry.getKey());
                pd.setVersion((String) def.get("version"));
                pd.setDescription((String) def.get("description"));
                pd.setContent((String) def.get("content"));
                if (def.get("placeholders") != null) {
                    @SuppressWarnings("unchecked")
                    List<String> placeholders = (List<String>) def.get("placeholders");
                    pd.setPlaceholders(placeholders);
                }
                prompts.put(entry.getKey(), pd);
            }

            log.info("Prompt 加载完成: {} 个, IDs={}", prompts.size(), prompts.keySet());

        } catch (IOException e) {
            log.error("Prompt 文件加载失败: {}", PROMPT_FILE, e);
            throw new RuntimeException("Prompt 文件加载失败", e);
        }
    }

    /**
     * 获取 Prompt 原文（不含占位符填充）
     */
    public String get(String promptId) {
        PromptDefinition pd = prompts.get(promptId);
        if (pd == null) {
            throw new IllegalArgumentException("未找到 Prompt: " + promptId + ", 已加载: " + prompts.keySet());
        }
        return pd.getContent();
    }

    /**
     * 创建带参数的 Prompt 请求（由 LlmInvoker 使用 Spring AI .text().param() 原生注入）
     *
     * @param promptId  Prompt ID
     * @param params    占位符参数（key 不含花括号，如 "questionTitle"）
     * @return PromptRequest（模板原文 + 参数）
     */
    public PromptRequest createRequest(String promptId, Map<String, String> params) {
        PromptDefinition pd = prompts.get(promptId);
        if (pd == null) {
            throw new IllegalArgumentException("未找到 Prompt: " + promptId + ", 已加载: " + prompts.keySet());
        }

        // 校验占位符是否遗漏
        if (pd.getPlaceholders() != null && params != null) {
            for (String placeholder : pd.getPlaceholders()) {
                String paramKey = placeholder.replace("{", "").replace("}", "");
                if (!params.containsKey(paramKey)) {
                    log.warn("Prompt [{}] 参数未提供: {} (version={})",
                            promptId, placeholder, pd.getVersion());
                }
            }
        }

        return PromptRequest.of(pd.getContent(), params != null ? params : Map.of());
    }

    /**
     * 创建无参数的 Prompt 请求
     */
    public PromptRequest createRequest(String promptId) {
        return createRequest(promptId, Map.of());
    }

    /**
     * 获取 Prompt 版本号（用于日志追踪）
     */
    public String getVersion(String promptId) {
        PromptDefinition pd = prompts.get(promptId);
        return pd != null ? pd.getVersion() : "unknown";
    }

    /**
     * Prompt 定义
     */
    @Data
    public static class PromptDefinition {
        private String id;
        private String version;
        private String description;
        private String content;
        private List<String> placeholders;
    }
}
