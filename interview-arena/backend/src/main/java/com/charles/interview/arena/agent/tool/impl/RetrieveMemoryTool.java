package com.charles.interview.arena.agent.tool.impl;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.guardrail.tool.ToolPermission;
import com.charles.interview.arena.agent.memory.retrieval.MultiStrategyMemoryRetriever;
import com.charles.interview.arena.agent.memory.retrieval.MultiStrategyMemoryRetriever.MemoryFragment;
import com.charles.interview.arena.agent.tool.api.Tool;
import com.charles.interview.arena.agent.tool.api.ToolInput;
import com.charles.interview.arena.agent.tool.api.ToolResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户记忆检索工具（模型可调用，只读）
 * <p>
 * 四路并行检索（语义/关键词/时间/薄弱点）+ RRF 融合，
 * 用于询问助手回答「我上次答错了什么」「我哪里薄弱」等个性化历史问题。
 * userId 由代码注入，模型无法检索他人记忆。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrieveMemoryTool implements Tool {

    private final MultiStrategyMemoryRetriever memoryRetriever;

    @Override
    public String getName() { return "retrieveMemory"; }

    @Override
    public String getDescription() { return "检索当前用户的学习记忆（历次面试记录、薄弱点画像），用于回答个性化历史问题"; }

    @Override
    public String getInputSchema() {
        return "{\"query\": \"string, 检索关键词（如知识点名称或用户问题）\"}";
    }

    @Override
    public ToolPermission.Level getPermissionLevel() { return ToolPermission.Level.READ; }

    @Override
    public ToolResult execute(ToolInput input) {
        Long userId = input.getUserId();
        String query = input.getString("query");
        if (userId == null) {
            return ToolResult.failure("缺少用户上下文");
        }
        if (query == null || query.isBlank()) {
            return ToolResult.failure("缺少必要参数: query");
        }

        try {
            List<MemoryFragment> fragments = memoryRetriever.retrieveWithRRF(userId, query);
            List<Map<String, Object>> result = fragments.stream()
                    .map(f -> Map.<String, Object>of(
                            "content", f.getContent(),
                            "source", f.getSource(),
                            "strategy", f.getStrategy()))
                    .toList();

            log.info("记忆检索: userId={}, query='{}', 命中 {} 条", userId, query, result.size());
            return ToolResult.success(result);
        } catch (Exception e) {
            log.warn("记忆检索失败: userId={}, err={}", userId, e.getMessage());
            return ToolResult.failure("记忆检索失败: " + e.getMessage());
        }
    }
}
