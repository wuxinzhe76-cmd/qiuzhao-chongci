package com.charles.interview.arena.agent.orchestration.react;

import java.util.List;

import lombok.Builder;
import lombok.Data;

/**
 * ReAct 执行请求
 * <p>
 * 由编排层（面试 Orchestrator / 询问 Service）构造：
 * 不同 Agent 提供不同的 persona、任务描述、最终答案格式和工具白名单。
 */
@Data
@Builder
public class ReActRequest {

    /** 会话 ID（代码注入进 ToolInput，模型不可伪造） */
    private Long sessionId;

    /** 用户 ID（代码注入进 ToolInput，模型不可伪造） */
    private Long userId;

    /** Agent 人设（渲染进系统提示词，如面试官/知识库助手） */
    private String persona;

    /** 本轮任务描述（当前题目、用户输入、上下文等） */
    private String task;

    /** final_answer 的格式要求说明（渲染进系统提示词） */
    private String finalAnswerSpec;

    /** 本 Agent 允许使用的工具白名单（Registry 中注册的工具名） */
    private List<String> allowedTools;
}
