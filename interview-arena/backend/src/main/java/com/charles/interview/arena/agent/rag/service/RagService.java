package com.charles.interview.arena.agent.rag.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.event.EventListener;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;

import com.charles.interview.arena.model.entity.Question;
import com.charles.interview.arena.model.entity.QuestionBank;
import com.charles.interview.arena.model.entity.QuestionBankQuestion;
import com.charles.interview.arena.agent.rag.event.QuestionChangedEvent;
import com.charles.interview.arena.agent.rag.model.QuestionEsDoc;
import com.charles.interview.arena.agent.rag.model.RagChatResponse;
import com.charles.interview.arena.agent.rag.model.SourceQuestion;
import com.charles.interview.arena.admin.service.QuestionBankQuestionService;
import com.charles.interview.arena.admin.service.QuestionBankService;
import com.charles.interview.arena.admin.service.QuestionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {
    private final QuestionService questionService;
    private final QuestionBankQuestionService questionBankQuestionService;
    private final QuestionBankService questionBankService;
    private final VectorStore vectorStore;
    private final ElasticsearchOperations elasticsearchOperations;
    private final ChatClient chatClient;
    private final HybridRetriever hybridRetriever;
    private final RerankService rerankService;
    private final QueryRewriteTransformer queryRewriteTransformer;
    private final DocumentDeduplicator documentDeduplicator;
    private final LostInTheMiddleRearranger lostInTheMiddleRearranger;
    private final SemanticCache semanticCache;

    // ==================== 离线索引（ETL） ====================

    public int importQuestionsToVectorStore(){
        List<Question> questions = questionService.list();
        // 构建 questionId → category 映射（取首个关联题库标题作为分类）
        Map<Long, String> categoryMap = buildCategoryMap();

        List<Document> documents = new ArrayList<>();
        for (Question question : questions) {
            String answer = question.getAnswer() != null ? question.getAnswer() : "暂无答案";
            String contentString = "题目：" + question.getTitle()
                + "\n内容：" + question.getContent()
                + "\n答案：" + answer;

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("questionId", question.getId());
            metadata.put("title", question.getTitle());
            metadata.put("difficulty", question.getDifficulty());
            metadata.put("category", categoryMap.getOrDefault(question.getId(), "未分类"));

            Document doc = new Document(contentString, metadata);
            documents.add(doc);
        }
        // 1. 分批写入 Milvus 向量库（DashScope text-embedding-v3 一次最多 10 条）
        int batchSize = 10;
        for (int i = 0; i < documents.size(); i += batchSize) {
            int end = Math.min(i + batchSize, documents.size());
            List<Document> batch = documents.subList(i, end);
            vectorStore.add(batch);
            log.info("已导入 {}/{} 条面试题到 Milvus 向量库", end, documents.size());
        }

        // 2. 写入 Elasticsearch 倒排索引（BM25 检索用，含 category 字段）
        for (Question question : questions) {
            QuestionEsDoc esDoc = toEsDoc(question, categoryMap);
            elasticsearchOperations.save(esDoc);
        }
        log.info("已导入 {} 条面试题到 Elasticsearch 倒排索引", questions.size());

        log.info("全部导入完成，共 {} 条", documents.size());
        return documents.size();
    }

    /**
     * 构建 questionId → category（题库标题）映射。
     * 一个题目可能属于多个题库，取第一个关联题库的标题作为分类。
     */
    private Map<Long, String> buildCategoryMap() {
        List<QuestionBankQuestion> relations = questionBankQuestionService.list();
        Map<Long, String> bankTitleMap = questionBankService.list().stream()
                .collect(Collectors.toMap(QuestionBank::getId, QuestionBank::getTitle, (a, b) -> a));
        Map<Long, String> result = new HashMap<>();
        for (QuestionBankQuestion rel : relations) {
            result.putIfAbsent(rel.getQuestionId(),
                    bankTitleMap.getOrDefault(rel.getQuestionBankId(), "未分类"));
        }
        return result;
    }

    /**
     * Question 实体 → ES 文档（含 category）
     */
    private QuestionEsDoc toEsDoc(Question question, Map<Long, String> categoryMap) {
        QuestionEsDoc esDoc = new QuestionEsDoc();
        esDoc.setQuestionId(question.getId());
        esDoc.setTitle(question.getTitle());
        esDoc.setContent(question.getContent());
        esDoc.setAnswer(question.getAnswer() != null ? question.getAnswer() : "暂无答案");
        esDoc.setDifficulty(question.getDifficulty());
        esDoc.setType(question.getType());
        esDoc.setCategory(categoryMap.getOrDefault(question.getId(), "未分类"));
        esDoc.setCreateTime(question.getCreateTime());
        return esDoc;
    }

    // ==================== 增量索引（事件驱动） ====================

    /**
     * 监听题目变更事件，增量同步 Milvus 向量库 + ES 倒排索引
     * <p>
     * 八股映射：
     * - blueprint 5.5：增量入库，题目增删改时同步更新 Milvus + ES
     * - 事件驱动解耦：QuestionService 发布事件，RagService 监听消费，无循环依赖
     * <p>
     * 异常处理：失败只记日志，不影响主业务流程（题目增删改不受 RAG 索引失败影响）
     */
    @EventListener
    public void handleQuestionChanged(QuestionChangedEvent event) {
        try {
            Question question = event.question();
            switch (event.action()) {
                case ADD -> indexSingleQuestion(question);
                case UPDATE -> {
                    removeSingleQuestion(question.getId());
                    indexSingleQuestion(question);
                }
                case DELETE -> removeSingleQuestion(question.getId());
            }
        } catch (Exception e) {
            log.error("RAG 增量索引失败，action={}, questionId={}", event.action(),
                    event.question() != null ? event.question().getId() : null, e);
        }
    }

    /**
     * 将单道题目写入 Milvus 向量库 + ES 倒排索引
     * <p>
     * 以 questionId 作为向量库文档 ID，便于增量删除
     */
    private void indexSingleQuestion(Question question) {
        Map<Long, String> categoryMap = buildCategoryMap();

        // 1. 写入 Milvus 向量库（以 questionId 作为文档 ID，便于增量删除）
        String answer = question.getAnswer() != null ? question.getAnswer() : "暂无答案";
        String contentString = "题目：" + question.getTitle()
                + "\n内容：" + question.getContent()
                + "\n答案：" + answer;

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("questionId", question.getId());
        metadata.put("title", question.getTitle());
        metadata.put("difficulty", question.getDifficulty());
        metadata.put("category", categoryMap.getOrDefault(question.getId(), "未分类"));

        Document doc = new Document(String.valueOf(question.getId()), contentString, metadata);
        vectorStore.add(List.of(doc));
        log.info("RAG 增量入库：题目 {} 已写入 Milvus 向量库", question.getId());

        // 2. 写入 ES 倒排索引
        QuestionEsDoc esDoc = toEsDoc(question, categoryMap);
        elasticsearchOperations.save(esDoc);
        log.info("RAG 增量入库：题目 {} 已写入 Elasticsearch 倒排索引", question.getId());
    }

    /**
     * 从 Milvus 向量库 + ES 倒排索引删除指定题目
     */
    private void removeSingleQuestion(Long questionId) {
        // 1. 从 Milvus 向量库删除
        vectorStore.delete(List.of(String.valueOf(questionId)));
        log.info("RAG 增量删除：题目 {} 已从 Milvus 向量库移除", questionId);

        // 2. 从 ES 倒排索引删除
        elasticsearchOperations.delete(String.valueOf(questionId), QuestionEsDoc.class);
        log.info("RAG 增量删除：题目 {} 已从 Elasticsearch 倒排索引移除", questionId);
    }

    // ==================== 在线检索 + 生成（Advanced RAG） ====================

    private static final String SYSTEM_PROMPT = "你是一个专业的面试题知识库助手。"
            + "只能基于检索到的面试题内容回答用户问题，不要编造未在上下文中出现的信息。"
            + "回答要准确、有条理，使用中文。";

    private static final String RAG_PROMPT_TEMPLATE = """
            请基于以下检索到的面试题资料回答用户问题。

            【检索到的面试题资料】
            {context}

            【回答要求】
            1. 只能基于上述资料回答，不要编造。
            2. 如果资料中没有相关内容，请如实说明。
            3. 回答要条理清晰，分点阐述。

            【用户问题】
            {question}
            """;

    /**
     * Advanced RAG 问答（Modular RAG 手动编排：混合检索 + Rerank + 引用标注 + 语义缓存）
     * <p>
     * 不再使用 RetrievalAugmentationAdvisor 黑盒，改为手动 DAG 编排，模块可插拔：
     * 1. 语义缓存查询：query → embedding → Redis cosine > 0.95 → 命中直接返回
     * 2. 混合检索：HybridRetriever（向量 Top-20 + BM25 Top-20 → RRF 融合 Top-10）
     * 3. Cross-Encoder 精排：RerankService（Top-10 → Top-5）
     * 4. 引用标注：从精排结果提取 questionId + title（溯源）
     * 5. 自定义中文 Prompt：拼接上下文 + 回答约束
     * 6. ChatClient 调通义千问生成
     * 7. 语义缓存写入：query + answer → Redis（TTL 1h）
     * <p>
     * 八股映射：
     * - #172 RAG 在线检索链路：retrieve → augment → generate
     * - #176 Modular RAG：DAG 图编排，模块可插拔（不依赖 Advisor 黑盒）
     * - blueprint 5.5.4 语义缓存：cosine > 0.95 命中
     * - blueprint 5.5：引用标注（溯源）+ 自定义中文 Prompt
     */
    public RagChatResponse ragChat(String userMessage) {
        // 1. 语义缓存查询
        String cached = semanticCache.get(userMessage);
        if (cached != null) {
            log.info("语义缓存命中，直接返回");
            RagChatResponse resp = new RagChatResponse();
            resp.setAnswer(cached);
            resp.setSourceQuestions(List.of());
            resp.setCacheHit(true);
            return resp;
        }

        // 2. 查询改写（Pre-Retrieval：纠正术语 + 扩展短 query）
        String rewrittenQuery = queryRewriteTransformer.rewrite(userMessage);

        // 3. 混合检索（向量 Top-20 + BM25 Top-20 → RRF 融合 Top-10）
        List<Document> candidates = hybridRetriever.retrieve(rewrittenQuery);

        // 4. Cross-Encoder 精排 Top-5
        List<Document> topDocs = rerankService.rerank(rewrittenQuery, candidates, 5);

        // 5. 去重 + Lost-in-the-middle 重排（最相关文档放 Prompt 首尾）
        topDocs = documentDeduplicator.deduplicate(topDocs);
        topDocs = lostInTheMiddleRearranger.rearrange(topDocs);

        // 6. 引用标注：从精排结果提取 questionId + title（溯源）
        List<SourceQuestion> sources = extractSources(topDocs);

        // 5. 拼接上下文 + 自定义中文 Prompt
        String context = buildContext(topDocs);
        String userPrompt = RAG_PROMPT_TEMPLATE
                .replace("{context}", context)
                .replace("{question}", userMessage);

        // 6. 调用通义千问生成
        String answer = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content();

        // 7. 语义缓存写入
        semanticCache.put(userMessage, answer);

        RagChatResponse response = new RagChatResponse();
        response.setAnswer(answer);
        response.setSourceQuestions(sources);
        response.setCacheHit(false);
        return response;
    }

    /**
     * 引用标注：从检索文档 metadata 提取 questionId + title，按 questionId 去重
     */
    private List<SourceQuestion> extractSources(List<Document> docs) {
        return docs.stream()
                .map(doc -> {
                    Map<String, Object> meta = doc.getMetadata();
                    Long questionId = toLong(meta.get("questionId"));
                    String title = String.valueOf(meta.getOrDefault("title", ""));
                    return new SourceQuestion(questionId, title);
                })
                .filter(s -> s.getQuestionId() != null)
                .collect(Collectors.toMap(
                        SourceQuestion::getQuestionId,
                        s -> s,
                        (a, b) -> a))
                .values().stream().toList();
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

    /**
     * 拼接检索文档为 Prompt 上下文
     */
    private String buildContext(List<Document> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            sb.append("--- 资料 ").append(i + 1).append(" ---\n");
            sb.append(docs.get(i).getText()).append("\n\n");
        }
        return sb.toString();
    }
}
