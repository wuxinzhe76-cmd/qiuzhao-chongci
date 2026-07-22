package com.charles.interview.arena.agent.orchestration.react;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAlias;

import lombok.Data;

/**
 * ReAct 单步结构化输出（LLM 每步返回的 JSON）
 * <p>
 * 协议：action 与 final_answer 二选一。
 * <ul>
 *   <li>调工具：{"thought": "...", "action": "pickQuestion", "action_input": {...}}</li>
 *   <li>给答案：{"thought": "...", "final_answer": {...}}</li>
 * </ul>
 * 由 LlmInvoker 的结构化输出三层校验兜底解析。
 */
@Data
public class ReActStep {

    /** 推理过程（必填，便于审计与调试） */
    private String thought;

    /** 要调用的工具名；不调用工具时为 null */
    private String action;

    /** 工具参数；不调用工具时为 null */
    @JsonAlias({"action_input", "actionInput"})
    private Map<String, Object> actionInput;

    /** 最终答案（JSON 对象）；调用工具时为 null */
    @JsonAlias({"final_answer", "finalAnswer"})
    private Map<String, Object> finalAnswer;
}
