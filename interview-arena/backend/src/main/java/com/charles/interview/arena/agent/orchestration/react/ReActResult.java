package com.charles.interview.arena.agent.orchestration.react;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * ReAct 执行结果
 */
@Data
@AllArgsConstructor
public class ReActResult {

    /** 是否成功产出最终答案 */
    private boolean success;

    /** 最终答案（LLM final_answer 的 JSON 对象，由编排层按各自 DTO 转换） */
    private Map<String, Object> finalAnswer;

    /** 全部工具调用轨迹（按执行顺序） */
    private List<ReActTrace> traces;

    /** 失败原因 */
    private String errorMessage;

    public static ReActResult success(Map<String, Object> finalAnswer, List<ReActTrace> traces) {
        return new ReActResult(true, finalAnswer, traces, null);
    }

    public static ReActResult failure(String errorMessage, List<ReActTrace> traces) {
        return new ReActResult(false, null, traces != null ? traces : new ArrayList<>(), errorMessage);
    }

    /**
     * 轨迹中是否包含某工具的成功调用
     */
    public boolean calledTool(String toolName) {
        return traces.stream().anyMatch(t ->
                t.getAction().equals(toolName) && t.getResult() != null && t.getResult().isSuccess());
    }
}
