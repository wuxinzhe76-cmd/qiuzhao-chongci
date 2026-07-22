package com.charles.interview.arena.agent.orchestration.react;

import java.util.Map;

import com.charles.interview.arena.agent.tool.api.ToolResult;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * ReAct 工具调用轨迹（一次 action 的完整记录）
 * <p>
 * 编排层可从轨迹中提取信息：如询问助手从 retrieveKnowledge 轨迹提取引用来源，
 * 面试编排从 pickQuestion 轨迹判断模型是否已换题。
 */
@Data
@AllArgsConstructor
public class ReActTrace {

    /** 工具名 */
    private String action;

    /** 模型给出的工具参数 */
    private Map<String, Object> actionInput;

    /** 工具执行结果 */
    private ToolResult result;
}
