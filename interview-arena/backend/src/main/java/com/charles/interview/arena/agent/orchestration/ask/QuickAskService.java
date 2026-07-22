package com.charles.interview.arena.agent.orchestration.ask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import com.charles.interview.arena.agent.llm.prompt.PromptManager;
import com.charles.interview.arena.agent.perception.model.Intent;
import com.charles.interview.arena.agent.memory.retrieval.MultiStrategyMemoryRetriever;
import com.charles.interview.arena.agent.memory.retrieval.MultiStrategyMemoryRetriever.MemoryFragment;
import com.charles.interview.arena.agent.perception.intent.IntentClassifier;
import com.charles.interview.arena.agent.rag.model.SourceQuestion;
import com.charles.interview.arena.agent.rag.service.HybridRetriever;
import com.charles.interview.arena.agent.rag.service.RerankService;
import com.charles.interview.arena.agent.rag.service.SemanticCache;
import com.charles.interview.arena.agent.orchestration.react.ReActExecutor;
import com.charles.interview.arena.agent.orchestration.react.ReActRequest;
import com.charles.interview.arena.agent.orchestration.react.ReActResult;
import com.charles.interview.arena.agent.orchestration.react.ReActTrace;
import com.charles.interview.arena.model.vo.QuickAskResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Quick Ask 询问助手（ReAct Agent）
 * <p>
 * 主链路（ReAct 循环，模型自主决策检索策略）：
 * <pre>
 * 1. 语义缓存查询（仅知识题路由可用缓存，个性化答案不进全局缓存）
 * 2. ReAct 循环：模型自选工具
 *    - retrieveKnowledge：题库混合检索（权威，高权重）
 *    - retrieveMemory：用户学习记忆（个性化历史）
 *    - webSearch：联网搜索（时效性/题库未命中，低权重标注来源）
 * 3. 从工具轨迹提取引用来源（题目溯源 + 联网 URL）
 * 4. 缓存写入（未使用用户记忆的答案才可全局共享）
 * </pre>
 * <p>
 * 降级链路（ReAct 失败时）：IntentClassifier 意图分类 + 确定性检索 + 单次生成，
 * 保证 LLM 决策能力不可用时询问功能仍然可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickAskService {

    private final ReActExecutor reActExecutor;
    private final PromptManager promptManager;
    private final SemanticCache semanticCache;
    private final IntentClassifier intentClassifier;

    // 降级链路依赖（确定性管道）
    private final ChatClient chatClient;
    private final HybridRetriever hybridRetriever;
    private final RerankService rerankService;
    private final MultiStrategyMemoryRetriever memoryRetriever;

    /** 询问助手 ReAct 工具白名单 */
    private static final List<String> ASK_TOOLS =
            List.of("retrieveKnowledge", "retrieveMemory", "webSearch");

    /** 降级链路提示词 */
    private static final String FALLBACK_SYSTEM_PROMPT = """
            你是一个专业的面试题知识库助手。
            基于题库精准内容（高权重）+ 用户历史学习记忆（个性化）回答用户问题。
            中文回答，条理清晰，Markdown 格式。
            """;

    private static final String FALLBACK_PROMPT_TEMPLATE = """
            请基于以下资料回答用户问题。

            【题库精准资料（高权重，权威）】
            %s

            【用户历史学习记忆（个性化）】
            %s

            【回答要求】
            1. 优先使用题库资料；题库无相关内容时如实说明
            2. 如果有用户历史学习记忆，结尾追加「## 针对你的建议」
            3. 如果用户在追问历史（如"我上次答错了什么"），主要基于用户历史学习记忆回答

            【用户问题】
            %s
            """;

    /**
     * Quick Ask 主流程
     *
     * @param userId 当前登录用户 ID
     * @param query  用户问题
     */
    public QuickAskResponse quickAsk(Long userId, String query) {
        QuickAskResponse response = new QuickAskResponse();
        response.setSourceQuestions(new ArrayList<>());
        response.setWebSources(new ArrayList<>());
        response.setCacheHit(false);
        response.setCanSaveToKb(true);

        // 1. 语义缓存（意图分类判定为纯知识题时才可用全局缓存）
        Intent intent = intentClassifier.classify(query);
        boolean cacheEligible = intent == Intent.KNOWLEDGE_QUERY;
        if (cacheEligible) {
            String cached = semanticCache.get(query);
            if (cached != null) {
                log.info("Quick Ask 语义缓存命中：query='{}'", query);
                response.setAnswer(cached);
                response.setCacheHit(true);
                return response;
            }
        }

        // 2. ReAct 循环：模型自主决定检索策略（替代关键词硬路由）
        ReActResult reactResult = reActExecutor.run(ReActRequest.builder()
                .userId(userId)
                .persona(promptManager.get("ask-react-persona"))
                .task("用户问题：" + query)
                .finalAnswerSpec(promptManager.get("ask-final-answer-spec"))
                .allowedTools(ASK_TOOLS)
                .build());

        if (!reactResult.isSuccess() || reactResult.getFinalAnswer().get("answer") == null) {
            log.warn("Quick Ask ReAct 失败，降级为确定性管道: {}", reactResult.getErrorMessage());
            return fallbackAsk(userId, query, intent, response);
        }

        String answer = String.valueOf(reactResult.getFinalAnswer().get("answer"));
        response.setAnswer(answer);

        // 3. 从工具轨迹提取引用来源
        boolean usedMemory = extractSourcesFromTraces(reactResult.getTraces(), response);

        // 4. 缓存写入：只有「知识题路由 + 未用用户记忆」的答案才可全局共享（防跨用户泄漏）
        if (cacheEligible && !usedMemory) {
            semanticCache.put(query, answer);
        }

        return response;
    }

    /**
     * 从 ReAct 工具轨迹提取来源信息
     *
     * @return 是否使用了用户记忆（决定能否进全局缓存）
     */
    @SuppressWarnings("unchecked")
    private boolean extractSourcesFromTraces(List<ReActTrace> traces, QuickAskResponse response) {
        boolean usedMemory = false;
        for (ReActTrace trace : traces) {
            if (trace.getResult() == null || !trace.getResult().isSuccess()) {
                continue;
            }
            switch (trace.getAction()) {
                case "retrieveKnowledge" -> {
                    Object data = trace.getResult().getData();
                    if (data instanceof List<?> docs) {
                        for (Object item : docs) {
                            if (item instanceof Map<?, ?> doc) {
                                Long questionId = toLong(doc.get("questionId"));
                                Object titleVal = doc.get("title");
                                String title = titleVal != null ? String.valueOf(titleVal) : "";
                                if (questionId != null) {
                                    response.getSourceQuestions().add(new SourceQuestion(questionId, title));
                                }
                            }
                        }
                    }
                }
                case "webSearch" -> {
                    Object data = trace.getResult().getData();
                    if (data instanceof Map<?, ?> web && web.get("sources") instanceof List<?> sources) {
                        for (Object url : sources) {
                            response.getWebSources().add(String.valueOf(url));
                        }
                    }
                }
                case "retrieveMemory" -> usedMemory = true;
                default -> { }
            }
        }
        return usedMemory;
    }

    // ==================== 降级链路（确定性管道，LLM 决策不可用时） ====================

    /**
     * 降级：IntentClassifier 意图分类 + 确定性检索 + 单次生成
     */
    private QuickAskResponse fallbackAsk(Long userId, String query, Intent intent,
                                         QuickAskResponse response) {
        boolean useMemory = userId != null && intent != Intent.KNOWLEDGE_QUERY;
        boolean useRag = intent != Intent.MEMORY_QUERY;

        // 1. 题库检索
        List<Document> topDocs = new ArrayList<>();
        if (useRag) {
            try {
                List<Document> candidates = hybridRetriever.retrieve(query);
                topDocs = rerankService.rerank(query, candidates, 5);
            } catch (Exception e) {
                log.warn("降级链路题库检索失败: {}", e.getMessage());
            }
        }
        for (Document doc : topDocs) {
            Long questionId = toLong(doc.getMetadata().get("questionId"));
            if (questionId != null) {
                response.getSourceQuestions().add(new SourceQuestion(
                        questionId, String.valueOf(doc.getMetadata().getOrDefault("title", ""))));
            }
        }

        // 2. 记忆检索
        String memoryContext = "（未启用用户记忆）";
        if (useMemory) {
            memoryContext = retrieveMemoryContext(userId, query);
        }

        // 3. 单次生成
        String kbContext = buildKbContext(topDocs);
        String userPrompt = String.format(FALLBACK_PROMPT_TEMPLATE, kbContext, memoryContext, query);
        String answer;
        try {
            answer = chatClient.prompt()
                    .system(FALLBACK_SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("Quick Ask 降级链路生成失败", e);
            answer = "抱歉，AI 服务暂时不可用，请稍后重试。";
        }
        response.setAnswer(answer);

        if (intent == Intent.KNOWLEDGE_QUERY) {
            semanticCache.put(query, answer);
        }
        return response;
    }

    private String retrieveMemoryContext(Long userId, String query) {
        try {
            List<MemoryFragment> fragments = memoryRetriever.retrieveWithRRF(userId, query);
            if (fragments == null || fragments.isEmpty()) {
                return "（暂无相关历史学习记忆）";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < fragments.size(); i++) {
                MemoryFragment f = fragments.get(i);
                sb.append("--- 记忆片段 ").append(i + 1)
                  .append("（").append(f.getSource()).append("）---\n")
                  .append(f.getContent()).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("降级链路记忆检索失败：userId={}, err={}", userId, e.getMessage());
            return "（记忆检索暂不可用）";
        }
    }

    private String buildKbContext(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "（题库中无相关资料）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            sb.append("--- 题库资料 ").append(i + 1).append(" ---\n");
            sb.append(docs.get(i).getText()).append("\n\n");
        }
        return sb.toString();
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
