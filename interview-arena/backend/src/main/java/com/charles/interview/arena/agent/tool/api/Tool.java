package com.charles.interview.arena.agent.tool.api;

import com.charles.interview.arena.agent.guardrail.tool.ToolPermission;

/**
 * 工具接口（模型可调用能力）
 * <p>
 * 设计参考：LangChain BaseTool + Spring AI FunctionCallback
 * <p>
 * 注册进 ToolRegistry 的工具供 ReAct Agent（LLM）自主选择调用：
 * LLM 产出 {action, action_input} -> ReActExecutor 从注册器取工具 -> ToolExecutor 执行。
 * 确定性步骤（落库、发 MQ、Harness 护栏）不封装为工具，由编排层直接代码调用。
 * <p>
 * Harness 集成：
 * - getPermissionLevel() 返回权限级别，ToolExecutor 在执行前检查
 * - Sentinel 限流 / 审计日志由 ToolExecutor 统一处理
 */
public interface Tool {

    /**
     * 工具名称（唯一标识，用于 ToolRegistry 注册和 LLM action 字段匹配）
     */
    String getName();

    /**
     * 工具描述（渲染进系统提示词，供 LLM 理解工具用途）
     */
    String getDescription();

    /**
     * 参数说明（渲染进系统提示词，供 LLM 构造 action_input）
     * <p>
     * 格式为简明 JSON 描述，如：{"query": "string, 检索关键词"}。
     * 无参数工具返回 "{}"（sessionId/userId 由代码注入，模型不可见不可伪造）。
     */
    default String getInputSchema() {
        return "{}";
    }

    /**
     * 工具权限级别（ToolExecutor 在执行前检查）
     * - READ：只读，可自由调用
     * - WRITE：写操作，需记录日志
     * - CRITICAL：危险操作，需人工审批
     */
    ToolPermission.Level getPermissionLevel();

    /**
     * 执行工具
     *
     * @param input 工具输入（含 sessionId、userId、业务参数）
     * @return 执行结果
     */
    ToolResult execute(ToolInput input);
}
