package com.charles.interview.arena.agent.orchestration.interviewer;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.charles.interview.arena.agent.llm.core.LlmInvoker;
import com.charles.interview.arena.agent.llm.core.LlmResult;
import com.charles.interview.arena.agent.llm.prompt.PromptManager;
import com.charles.interview.arena.agent.llm.prompt.PromptRequest;
import com.charles.interview.arena.model.dto.interview.AiInterviewResponseDTO;
import com.charles.interview.arena.model.entity.Question;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 面试官 LLM 话术服务（确定性单次生成，非 ReAct）
 * <p>
 * 承接原 GenerateOpeningTool / GenerateTransitionTool / AbstractLlmTool 的职责：
 * 开场提问与强制换题过渡属于编排层的确定性步骤（没有决策空间），
 * 按「工具仅供模型调用」的原则不再封装为 Tool，由编排层直接调用本 Service。
 * <p>
 * 评估与自主换题的决策已迁移至 ReAct 循环（ReActExecutor + 面试官 persona）。
 * <p>
 * 安全：保留参考答案泄露检测（滑动窗口连续 30 字符匹配即拦截）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewLlmService {

    private final LlmInvoker llmInvoker;
    private final PromptManager promptManager;

    /** 参考答案泄露检测：连续匹配字符数阈值 */
    private static final int LEAK_DETECTION_THRESHOLD = 30;

    /**
     * 生成面试开场提问（面试开始时，无决策空间的确定性调用）
     */
    public AiInterviewResponseDTO generateOpening(Question question) {
        log.info("生成开场提问: questionId={}, title={}", question.getId(), question.getTitle());
        PromptRequest systemPrompt = buildSystemPromptBeforeAnswer(question);
        PromptRequest userPrompt = promptManager.createRequest("opening-user-prompt");
        return callLlm(systemPrompt, userPrompt);
    }

    /**
     * 生成换题过渡话术（代码兜底强制换题时使用；模型自主换题的过渡语由 ReAct 循环产出）
     */
    public AiInterviewResponseDTO generateTransition(Question nextQuestion) {
        log.info("生成过渡话术: nextQuestionId={}, title={}", nextQuestion.getId(), nextQuestion.getTitle());
        PromptRequest systemPrompt = buildSystemPromptBeforeAnswer(nextQuestion);
        PromptRequest userPrompt = promptManager.createRequest("transition-user-prompt");
        return callLlm(systemPrompt, userPrompt);
    }

    /**
     * 参考答案泄露检测（编排层对 ReAct 最终回复也用此方法把关）
     * <p>
     * 检测 LLM 输出是否包含参考答案的连续片段（>= 30 字符匹配视为泄露）。
     */
    public boolean isAnswerLeaked(String llmOutput, String referenceAnswer) {
        if (llmOutput == null || referenceAnswer == null || referenceAnswer.length() < LEAK_DETECTION_THRESHOLD) {
            return false;
        }
        String normalizedOutput = llmOutput.replaceAll("\\s+", "");
        String normalizedAnswer = referenceAnswer.replaceAll("\\s+", "");

        for (int i = 0; i <= normalizedAnswer.length() - LEAK_DETECTION_THRESHOLD; i++) {
            String chunk = normalizedAnswer.substring(i, i + LEAK_DETECTION_THRESHOLD);
            if (normalizedOutput.contains(chunk)) {
                log.warn("[安全] 参考答案泄露检测命中: 匹配片段长度={}, 起始位置={}", LEAK_DETECTION_THRESHOLD, i);
                return true;
            }
        }
        return false;
    }

    /**
     * 泄露拦截后的安全响应（不包含任何答案信息）
     */
    public AiInterviewResponseDTO safeResponse() {
        AiInterviewResponseDTO dto = new AiInterviewResponseDTO();
        dto.setReplyToUser("请先尝试自己回答这道题，回答后我会给你点评。");
        dto.setActionDirective("DEEP_DIVE");
        dto.setCurrentTopicMastery(0);
        return dto;
    }

    /**
     * 兜底响应：LLM 异常时返回 END_INTERVIEW，避免面试卡死
     */
    public AiInterviewResponseDTO fallbackResponse() {
        AiInterviewResponseDTO dto = new AiInterviewResponseDTO();
        dto.setReplyToUser("抱歉，AI 服务暂时不可用，请稍后重试。");
        dto.setActionDirective("END_INTERVIEW");
        dto.setCurrentTopicMastery(0);
        return dto;
    }

    // ==================== 内部 ====================

    private AiInterviewResponseDTO callLlm(PromptRequest systemPrompt, PromptRequest userPrompt) {
        LlmResult<AiInterviewResponseDTO> result = llmInvoker.invoke(
                systemPrompt, userPrompt, AiInterviewResponseDTO.class);
        if (result.isSuccess()) {
            return result.getData();
        }
        log.warn("面试话术生成失败[{}]，降级到兜底响应: {}", result.getErrorType(), result.getErrorMessage());
        return fallbackResponse();
    }

    /**
     * 构建 System Prompt（用户回答前，不含参考答案，防泄露）
     */
    private PromptRequest buildSystemPromptBeforeAnswer(Question question) {
        String title = question.getTitle() != null ? question.getTitle() : "";
        String content = question.getContent() != null ? question.getContent() : "";
        return promptManager.createRequest("interview-system-prompt-before-answer", Map.of(
                "questionTitle", title,
                "questionContent", content
        ));
    }
}
