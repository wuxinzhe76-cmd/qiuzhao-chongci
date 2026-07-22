package com.charles.interview.arena.agent.rag.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 个人知识库服务（蓝图 §5.5.6 save-to-kb）
 * <p>
 * 用户选择将 Quick Ask 答案存入个人知识库 → 写入 Milvus 用户私有 collection。
 * <p>
 * 注：当前简化实现，写入与题库共享的 collection（interview_arena_qa），
 * 通过 metadata.userId 区分用户。生产环境建议为每个用户创建独立 collection。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserKnowledgeBaseService {

    private final VectorStore vectorStore;

    /**
     * 保存到个人知识库
     *
     * @param userId   用户 ID
     * @param question 用户问题
     * @param answer   AI 回答
     * @return 是否保存成功
     */
    public boolean saveToKb(Long userId, String question, String answer) {
        try {
            String content = "用户问题：" + question + "\nAI 回答：" + answer;
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", userId);
            metadata.put("type", "user_kb");
            metadata.put("question", question);
            metadata.put("source", "quick_ask");

            Document doc = new Document(content, metadata);
            vectorStore.add(List.of(doc));

            log.info("已保存到个人知识库：userId={}, question='{}'", userId, question);
            return true;
        } catch (Exception e) {
            log.error("保存到个人知识库失败：userId={}", userId, e);
            return false;
        }
    }
}
