package com.charles.interview.arena.agent.reflection.harness;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * 纠正提示构建器
 * <p>
 * 职责:在 ReAct 循环中,当 LLM 输出异常时,构建纠正提示回灌到 scratchpad。
 * <p>
 * 纠正场景:
 * 1. 模型输出无 action 且无 final_answer -> 提示二选一
 * 2. 模型请求白名单外的工具 -> 提示工具不存在
 * 3. 模型重复调用相同工具相同参数 -> 提示换路
 * 4. 工具返回空结果 -> 提示换工具或换关键词
 * <p>
 * 这些纠正占一步,模型下一轮可以纠正。
 */
@Component
public class CorrectionPromptBuilder {

    /**
     * 构建纠正提示:无 action 且无 final_answer
     */
    public String noActionNoAnswer() {
        return "Observation: 你的输出既没有 action 也没有 final_answer,请二选一。\n";
    }

    /**
     * 构建纠正提示:请求了白名单外的工具
     *
     * @param invalidAction 模型请求的非法工具名
     * @param allowedTools  允许的工具列表
     */
    public String toolNotAllowed(String invalidAction, List<String> allowedTools) {
        return "Observation: 工具 '" + invalidAction
                + "' 不存在或不可用,可用工具见系统提示词: " + allowedTools + "\n";
    }

    /**
     * 构建纠正提示:重复调用相同工具相同参数
     *
     * @param action 重复的工具名
     */
    public String duplicateCall(String action) {
        return "Observation: 你重复了与上一步完全相同的调用(" + action
                + "),请基于已有结果给出 final_answer,或换一个行动。\n";
    }

    /**
     * 构建纠正提示:工具返回空结果
     *
     * @param action      工具名
     * @param suggestion  建议(如换关键词/换工具)
     */
    public String emptyResult(String action, String suggestion) {
        return "Observation: 工具 " + action + " 返回了空结果。"
                + (suggestion != null ? "建议: " + suggestion : "")
                + "\n";
    }

    /**
     * 构建修复提示:LLM 输出校验失败
     *
     * @param validationError 校验错误信息
     */
    public String validationFailed(String validationError) {
        return "Observation: 你的输出校验失败: " + validationError
                + "\n请严格按 JSON Schema 重新输出。\n";
    }
}
