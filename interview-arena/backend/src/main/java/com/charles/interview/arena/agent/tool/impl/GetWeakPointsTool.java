package com.charles.interview.arena.agent.tool.impl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.guardrail.tool.ToolPermission;
import com.charles.interview.arena.agent.memory.MemoryFacade;
import com.charles.interview.arena.agent.memory.semantic.model.WeakPoint;
import com.charles.interview.arena.agent.memory.semantic.model.UserProfile;
import com.charles.interview.arena.agent.tool.api.Tool;
import com.charles.interview.arena.agent.tool.api.ToolInput;
import com.charles.interview.arena.agent.tool.api.ToolResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 用户薄弱点查询工具（模型可调用，只读）
 * <p>
 * ReAct 面试官用它了解候选人的历史画像与薄弱点，决定追问方向或出题偏好。
 * userId 由代码注入（ToolInput），模型无法查询他人数据。
 * <p>
 * 边界：模型只能「读」记忆；记忆的写入（rememberTurn/consolidate）是编排层与
 * 记忆子系统的确定性职责，不暴露为工具。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GetWeakPointsTool implements Tool {

    private final MemoryFacade memoryFacade;

    @Override
    public String getName() { return "getWeakPoints"; }

    @Override
    public String getDescription() { return "查询候选人的历史知识画像与薄弱点（总面试次数、平均分、掌握度低的知识点）"; }

    @Override
    public String getInputSchema() {
        return "{}（无需参数，自动查询当前候选人）";
    }

    @Override
    public ToolPermission.Level getPermissionLevel() { return ToolPermission.Level.READ; }

    @Override
    public ToolResult execute(ToolInput input) {
        Long userId = input.getUserId();
        if (userId == null) {
            return ToolResult.failure("缺少用户上下文");
        }

        Map<String, Object> result = new HashMap<>();
        UserProfile profile = memoryFacade.retrieveProfile(userId);
        if (profile == null || profile.getTotalInterviews() == null || profile.getTotalInterviews() == 0) {
            result.put("summary", "新用户，暂无历史画像");
            result.put("weakPoints", List.of());
            return ToolResult.success(result);
        }

        result.put("totalInterviews", profile.getTotalInterviews());
        result.put("avgScore", profile.getAvgScore());
        List<WeakPoint> weakPoints = profile.getWeakPoints();
        result.put("weakPoints", weakPoints != null ? weakPoints.stream()
                .map(w -> Map.of(
                        "topic", w.getTopic(),
                        "avgMastery", w.getAvgMastery(),
                        "examCount", w.getExamCount(),
                        "isPersistent", Boolean.TRUE.equals(w.getIsPersistent())))
                .toList() : List.of());

        log.info("查询薄弱点: userId={}, weakPoints={}", userId,
                weakPoints != null ? weakPoints.size() : 0);
        return ToolResult.success(result);
    }
}
