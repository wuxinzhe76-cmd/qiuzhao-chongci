package com.charles.interview.arena.agent.perception.intent;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.perception.model.Intent;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 意图分类器(两级路由:规则优先 + LLM 兜底)
 * <p>
 * 第一级:关键词规则匹配(80% 请求,零成本)
 * 第二级:LLM 分类(20% 未知意图,低成本小模型)
 * <p>
 * 设计原则:规则兜底不每次调 LLM,节省成本。
 * 后续可升级为三级(规则 -> Embedding -> LLM)。
 */
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    /** 面试相关关键词 */
    private static final List<String> INTERVIEW_KEYWORDS = List.of(
            "开始面试", "面试", "答题", "回答", "下一题", "结束面试"
    );

    /** 历史追问关键词 */
    private static final List<String> MEMORY_KEYWORDS = List.of(
            "上次", "之前", "上次面试", "上次答", "上次卡", "我之前", "历史"
    );

    /** 知识查询关键词 */
    private static final List<String> RAG_KEYWORDS = List.of(
            "什么是", "原理", "区别", "为什么", "怎么实现", "如何实现", "讲讲", "说说"
    );

    /** 时效性关键词(触发联网) */
    private static final List<String> TIME_KEYWORDS = List.of(
            "最新", "2026", "当前", "现在", "最近", "新版"
    );

    private static final String INTENT_LLM_PROMPT = """
            你是意图分类器。请判断用户输入的意图,输出 JSON。
            
            意图枚举:
            - START_INTERVIEW: 开始面试
            - ANSWER_QUESTION: 提交面试回答
            - END_INTERVIEW: 结束面试
            - KNOWLEDGE_QUERY: 知识题查询(什么是/原理/区别)
            - MEMORY_QUERY: 历史追问(上次/之前/历史)
            - HYBRID_QUERY: 混合查询
            - SAVE_TO_KB: 保存到知识库
            - UNKNOWN: 未知意图
            
            【用户输入】
            %s
            
            【输出要求】
            严格输出 JSON: {"intent": "KNOWLEDGE_QUERY", "confidence": 0.9}
            只输出 JSON,不要其他文字。
            """;

    public IntentClassifier(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 意图分类(两级路由)
     *
     * @param text 用户输入文本
     * @return 识别出的意图
     */
    public Intent classify(String text) {
        if (text == null || text.isBlank()) {
            return Intent.UNKNOWN;
        }

        // 第一级:规则匹配(80% 请求在此分流,零成本)
        Intent ruleResult = classifyByRules(text);
        if (ruleResult != Intent.UNKNOWN) {
            log.info("意图分类[规则命中]: {} | query='{}'", ruleResult, abbreviate(text, 50));
            return ruleResult;
        }

        // 第二级:LLM 分类(20% 未知意图,有成本)
        Intent llmResult = classifyByLlm(text);
        log.info("意图分类[LLM命中]: {} | query='{}'", llmResult, abbreviate(text, 50));
        return llmResult;
    }

    /**
     * 第一级:规则匹配
     */
    private Intent classifyByRules(String text) {
        boolean hasInterview = INTERVIEW_KEYWORDS.stream().anyMatch(text::contains);
        boolean hasMemory = MEMORY_KEYWORDS.stream().anyMatch(text::contains);
        boolean hasRag = RAG_KEYWORDS.stream().anyMatch(text::contains);
        boolean hasTime = TIME_KEYWORDS.stream().anyMatch(text::contains);

        // 面试指令优先
        if (hasInterview) {
            if (text.contains("开始")) return Intent.START_INTERVIEW;
            if (text.contains("结束")) return Intent.END_INTERVIEW;
            return Intent.ANSWER_QUESTION;
        }

        // 保存知识库
        if (text.contains("保存") || text.contains("存入知识库")) {
            return Intent.SAVE_TO_KB;
        }

        // 混合场景
        if (hasMemory && hasRag) return Intent.HYBRID_QUERY;
        if (hasMemory) return Intent.MEMORY_QUERY;
        if (hasRag || hasTime) return Intent.KNOWLEDGE_QUERY;

        // 规则未命中
        return Intent.UNKNOWN;
    }

    /**
     * 第二级:LLM 分类
     */
    private Intent classifyByLlm(String text) {
        try {
            String prompt = String.format(INTENT_LLM_PROMPT, text);
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                return Intent.UNKNOWN;
            }

            // 容错:去掉可能的 markdown 代码块包裹
            String cleaned = response.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
            }

            var root = objectMapper.readTree(cleaned);
            String intentStr = root.path("intent").asText("UNKNOWN");
            try {
                return Intent.valueOf(intentStr);
            } catch (IllegalArgumentException e) {
                log.warn("LLM 返回未知意图: {}", intentStr);
                return Intent.UNKNOWN;
            }

        } catch (Exception e) {
            log.warn("LLM 意图分类失败,降级为 UNKNOWN: {}", e.getMessage());
            return Intent.UNKNOWN;
        }
    }

    private String abbreviate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
