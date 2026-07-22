package com.charles.interview.arena.agent.tool.impl;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.guardrail.tool.ToolPermission;
import com.charles.interview.arena.model.entity.Question;
import com.charles.interview.arena.admin.service.QuestionService;
import com.charles.interview.arena.agent.tool.api.Tool;
import com.charles.interview.arena.agent.tool.api.ToolInput;
import com.charles.interview.arena.agent.tool.api.ToolResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 获取题目详情工具
 * <p>
 * 输入：questionId
 * 输出：Question（题目完整信息：title + content + answer）
 * <p>
 * 用于 LLM 调用前从 MySQL 加载题目完整信息。
 * <p>
 * Redis 缓存：题目详情读多写少，缓存 30 分钟减少 MySQL 查询。
 * 缓存 key: question:detail:{questionId}
 * <p>
 * 高并发防护：
 * - Sentinel 限流（ToolExecutor 层，100 QPS）
 * - Redis 缓存（本工具层，命中缓存不查 MySQL）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetQuestionDetailTool implements Tool {

    private final QuestionService questionService;

    @Override
    public String getName() { return "getQuestionDetail"; }

    @Override
    public String getDescription() { return "根据题目ID获取题目完整信息（标题、描述、参考答案）"; }

    @Override
    public String getInputSchema() {
        return "{\"questionId\": \"number, 题目 ID\"}";
    }

    @Override
    public ToolPermission.Level getPermissionLevel() { return ToolPermission.Level.READ; }

    @Override
    @Cacheable(value = "question:detail", key = "#input.params['questionId']", unless = "#result == null || !#result.isSuccess()")
    public ToolResult execute(ToolInput input) {
        Long questionId = input.getParam("questionId", Long.class);
        if (questionId == null) {
            return ToolResult.failure("缺少必要参数: questionId");
        }

        Question question = questionService.getById(questionId);
        if (question == null) {
            return ToolResult.failure("题目不存在: questionId=" + questionId);
        }

        log.debug("获取题目详情（未命中缓存）: questionId={}, title={}", questionId, question.getTitle());
        return ToolResult.success(question);
    }

    /**
     * 清除指定题目的缓存（题目内容更新时调用）
     */
    @CacheEvict(value = "question:detail", key = "#questionId")
    public void evictCache(Long questionId) {
        log.info("清除题目缓存: questionId={}", questionId);
    }
}
