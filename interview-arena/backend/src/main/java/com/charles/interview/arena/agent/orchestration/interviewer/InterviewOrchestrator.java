package com.charles.interview.arena.agent.orchestration.interviewer;

import java.util.List;

import org.springframework.stereotype.Component;

import com.charles.interview.arena.agent.planning.PlanningService;
import com.charles.interview.arena.agent.planning.model.PlanningAction;
import com.charles.interview.arena.agent.planning.model.PlanningDecision;
import com.charles.interview.arena.agent.planning.harness.GoalDriftDetector;
import com.charles.interview.arena.agent.planning.harness.LoopDetector;
import com.charles.interview.arena.agent.guardrail.input.InputSanitizer;
import com.charles.interview.arena.agent.guardrail.output.OutputMonitor;
import com.charles.interview.arena.agent.orchestration.harness.ThreeLayerController;
import com.charles.interview.arena.agent.runtime.state.AgentStateStore;
import com.charles.interview.arena.agent.runtime.state.InterviewAgentState;
import com.charles.interview.arena.agent.llm.prompt.PromptManager;
import com.charles.interview.arena.agent.memory.MemoryFacade;
import com.charles.interview.arena.agent.orchestration.interviewer.KafkaReportProducer;
import com.charles.interview.arena.agent.orchestration.react.ReActExecutor;
import com.charles.interview.arena.agent.orchestration.react.ReActRequest;
import com.charles.interview.arena.agent.orchestration.react.ReActResult;
import com.charles.interview.arena.agent.orchestration.interviewer.InterviewLlmService;
import com.charles.interview.arena.agent.tool.api.ToolExecutor;
import com.charles.interview.arena.agent.tool.api.ToolInput;
import com.charles.interview.arena.agent.tool.api.ToolResult;
import com.charles.interview.arena.common.ErrorCode;
import com.charles.interview.arena.exception.ThrowUtils;
import com.charles.interview.arena.mapper.InterviewSessionMapper;
import com.charles.interview.arena.model.dto.interview.AiInterviewResponseDTO;
import com.charles.interview.arena.model.dto.interview.InterviewAnswerDTO;
import com.charles.interview.arena.model.dto.interview.InterviewStartDTO;
import com.charles.interview.arena.model.entity.InterviewSession;
import com.charles.interview.arena.model.entity.Question;
import com.charles.interview.arena.model.enums.ActionDirectiveEnum;
import com.charles.interview.arena.model.vo.InterviewAnswerVO;
import com.charles.interview.arena.model.vo.InterviewStartVO;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 面试编排器（Orchestrator）-- 状态机护栏 + ReAct 决策
 * <p>
 * 分工原则：
 * <pre>
 * 代码（确定性，模型无权跳过）：
 *   ├── Harness 护栏：InputSanitizer / GoalDriftDetector / LoopDetector / OutputMonitor
 *   ├── 记忆写入：MemoryFacade.rememberTurn（每轮必落库，不交给模型决定）
 *   ├── 轮次状态：incrementRound / incrementQuestionRound
 *   ├── ThreeLayerController：对模型指令的最终裁决（追问超限强制换题 / 总轮次强制结束）
 *   └── 结束副作用：发报告 MQ + 清理短期记忆
 *
 * ReAct 循环（模型自主决策，ReActExecutor）：
 *   ├── 面试官 persona + 工具白名单：getQuestionDetail / pickQuestion / getWeakPoints
 *   ├── 模型自己决定：查题目详情 -> 评估回答 -> 追问 / 调 pickQuestion 换题 / 结束
 *   └── final_answer = { reply_to_user, action_directive, current_topic_mastery }
 * </pre>
 * <p>
 * 确定性话术（开场 / 代码强制换题的过渡）走 InterviewLlmService 单次生成，不进循环。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewOrchestrator {

    private final ToolExecutor toolExecutor;
    private final MemoryFacade memoryFacade;
    private final AgentStateStore agentStateStore;
    private final PlanningService planningService;
    private final ThreeLayerController threeLayerController;
    private final InterviewSessionMapper sessionMapper;
    private final ReActExecutor reActExecutor;
    private final InterviewLlmService interviewLlmService;
    private final PromptManager promptManager;
    private final KafkaReportProducer kafkaReportProducer;
    private final ObjectMapper objectMapper;

    // Harness 组件
    private final InputSanitizer inputSanitizer;
    private final OutputMonitor outputMonitor;
    private final LoopDetector loopDetector;
    private final GoalDriftDetector goalDriftDetector;

    /** 面试官 ReAct 工具白名单 */
    private static final List<String> INTERVIEWER_TOOLS =
            List.of("getQuestionDetail", "pickQuestion", "getWeakPoints");

    // ==================== 1. 开始面试 ====================

    /**
     * 开始面试（确定性流程，无决策空间，不走 ReAct）
     * <p>
     * 流程：创建 session -> 抽题（记忆驱动，工具内更新工作记忆）-> 生成开场 -> 记忆落库
     */
    public InterviewStartVO startInterview(InterviewStartDTO dto, Long userId) {
        // 1. 创建面试会话
        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setMode(dto.getMode());
        session.setBankId(dto.getBankId());
        session.setStatus(0);
        sessionMapper.insert(session);
        Long sessionId = session.getId();

        // 2. 抽第一道题（记忆驱动出题；工具内部完成 setCurrentQuestion/addUsedQuestion）
        ToolResult pickResult = toolExecutor.execute("pickQuestion",
                ToolInput.builder().sessionId(sessionId).userId(userId).build()
                        .with("mode", dto.getMode())
                        .with("bankId", dto.getBankId())
                        .with("isFirst", true));
        Question firstQuestion = pickResult.getDataAs(Question.class);
        ThrowUtils.throwIf(firstQuestion == null, ErrorCode.OPERATION_ERROR, "题库中暂无可用题目");

        // 3. 生成开场提问（确定性单次生成）
        AiInterviewResponseDTO aiResp = interviewLlmService.generateOpening(firstQuestion);
        String openingQuestion = aiResp.getReplyToUser();

        // 4. 记住这一轮（情景记忆落库 + 工作记忆对话历史，一次写入）
        memoryFacade.rememberTurn(sessionId, firstQuestion.getId(), "assistant", openingQuestion, 0);

        log.info("面试开始: sessionId={}, userId={}, questionId={}", sessionId, userId, firstQuestion.getId());

        InterviewStartVO vo = new InterviewStartVO();
        vo.setSessionId(sessionId);
        vo.setOpeningQuestion(openingQuestion);
        return vo;
    }

    // ==================== 2. 提交回答 ====================

    /**
     * 提交回答（每轮调用）
     * <p>
     * 流程：护栏前置 -> 记忆写入 -> ReAct 决策循环 -> 护栏后置 -> 指令路由
     */
    public InterviewAnswerVO answerInterview(InterviewAnswerDTO dto, Long userId) {
        Long sessionId = dto.getSessionId();
        InterviewSession session = sessionMapper.selectById(sessionId);
        ThrowUtils.throwIf(session == null, ErrorCode.NOT_FOUND_ERROR, "面试会话不存在");
        ThrowUtils.throwIf(session.getStatus() != 0, ErrorCode.OPERATION_ERROR, "面试已结束，不能继续作答");

        // 0. Harness：防注入清洗
        String sanitizedAnswer = inputSanitizer.sanitizeInput(dto.getAnswer());

        // 0.5 Harness：目标漂移检测（用户在"提问"而非"回答"时拦截，省 Token + 防上下文污染）
        String driftResponse = goalDriftDetector.checkDrift(sessionId, sanitizedAnswer);
        if (driftResponse != null) {
            return handleDrift(sessionId, session, sanitizedAnswer, driftResponse);
        }

        // 1. 记住用户回答（MySQL 落库 + Redis 对话历史，一次写入）
        Long currentQuestionId = agentStateStore.getCurrentQuestion(sessionId);
        memoryFacade.rememberTurn(sessionId, currentQuestionId, "user", sanitizedAnswer,
                (int) agentStateStore.getRound(sessionId) + 1);

        // 2. 推进轮次（AgentStateStore,独立于 Memory）
        long newRound = agentStateStore.incrementRound(sessionId);
        long newQuestionRound = agentStateStore.incrementQuestionRound(sessionId);

        // 3. Harness：循环检测（用户复读机行为 -> 强制换题）
        if (loopDetector.detectLoop(sessionId, sanitizedAnswer)) {
            log.warn("检测到对话循环，强制切换下一题: sessionId={}", sessionId);
            return handleNextQuestion(sessionId, session, newRound);
        }

        // 4. 取当前题目（任务上下文 + 泄露检测基准；工具带 Redis 缓存）
        ToolResult questionResult = toolExecutor.execute("getQuestionDetail",
                ToolInput.builder().sessionId(sessionId).build()
                        .with("questionId", currentQuestionId));
        Question currentQuestion = questionResult.getDataAs(Question.class);

        // 4.5 加载状态 + 获取可用动作（注入 Prompt 软约束）
        InterviewAgentState state = agentStateStore.load(sessionId);
        String actionsPrompt = planningService.renderActionsForPrompt(state);

        // 5. ReAct 决策循环：模型在可用动作约束内自主决策
        ReActResult reactResult = reActExecutor.run(ReActRequest.builder()
                .sessionId(sessionId)
                .userId(userId)
                .persona(promptManager.get("interviewer-react-persona"))
                .task(buildInterviewTask(currentQuestion, currentQuestionId,
                        newQuestionRound, newRound, sanitizedAnswer, sessionId, actionsPrompt))
                .finalAnswerSpec(promptManager.get("interview-final-answer-spec"))
                .allowedTools(INTERVIEWER_TOOLS)
                .build());

        AiInterviewResponseDTO aiResp = parseFinalAnswer(reactResult);

        // 6. Harness：参考答案泄露检测 + 输出监控
        if (currentQuestion != null
                && interviewLlmService.isAnswerLeaked(aiResp.getReplyToUser(), currentQuestion.getAnswer())) {
            aiResp = interviewLlmService.safeResponse();
        }
        aiResp.setReplyToUser(outputMonitor.monitor(aiResp.getReplyToUser()));

        // 7. 状态对齐：模型已通过 pickQuestion 切换了当前题目（工作记忆已更新），
        //    但指令没写 NEXT_QUESTION -> 以工具轨迹为准修正指令
        boolean modelPickedNext = reactResult.isSuccess() && reactResult.calledTool("pickQuestion");
        String directiveValue = aiResp.getActionDirective();
        if (modelPickedNext && ActionDirectiveEnum.fromValue(directiveValue) == ActionDirectiveEnum.DEEP_DIVE) {
            log.warn("模型已换题但指令为 DEEP_DIVE，修正为 NEXT_QUESTION: sessionId={}", sessionId);
            directiveValue = ActionDirectiveEnum.NEXT_QUESTION.getValue();
        }

        // 8. PlanningService：验证 LLM 决策（硬约束 + 恢复策略）
        PlanningDecision decision = planningService.validateActionString(directiveValue, state);

        // 9. 记住 AI 回复（归属本轮被评估的题目）
        memoryFacade.rememberTurn(sessionId, currentQuestionId, "assistant",
                aiResp.getReplyToUser(), (int) newRound);

        // 10. 指令路由（基于 PlanningDecision）
        boolean ended = false;
        switch (decision.action()) {
            case DEEP_DIVE -> log.info("面试 {} 继续追问: questionRound={}, source={}",
                    sessionId, newQuestionRound, decision.source());
            case PROPOSE_NEXT_QUESTION -> {
                if (!modelPickedNext) {
                    // 代码兜底强制换题（模型没调 pickQuestion）：代码抽题 + 确定性过渡话术
                    InterviewAnswerVO nextVo = handleNextQuestion(sessionId, session, newRound);
                    nextVo.setCurrentTopicMastery(aiResp.getCurrentTopicMastery());
                    return nextVo;
                }
                // 模型已自主换题：过渡话术已包含在 replyToUser 中，状态已由工具更新
                log.info("面试 {} 模型自主换题: newQuestionId={}",
                        sessionId, agentStateStore.getCurrentQuestion(sessionId));
            }
            case END_INTERVIEW -> {
                ended = true;
                log.info("面试 {} 触发结束: reason={}, source={}",
                        sessionId, decision.reason(), decision.source());
            }
            default -> log.info("面试 {} 动作: {} reason={}",
                    sessionId, decision.action(), decision.reason());
        }

        if (ended) {
            doEndInterview(session, sessionId, userId);
        }

        // 映射 PlanningAction -> ActionDirectiveEnum(兼容前端 VO)
        String voDirective = switch (decision.action()) {
            case PROPOSE_NEXT_QUESTION -> ActionDirectiveEnum.NEXT_QUESTION.getValue();
            case END_INTERVIEW -> ActionDirectiveEnum.END_INTERVIEW.getValue();
            default -> ActionDirectiveEnum.DEEP_DIVE.getValue();
        };

        InterviewAnswerVO vo = new InterviewAnswerVO();
        vo.setReplyToUser(aiResp.getReplyToUser());
        vo.setActionDirective(voDirective);
        vo.setCurrentTopicMastery(aiResp.getCurrentTopicMastery());
        vo.setIsEnded(ended);
        return vo;
    }

    // ==================== 3. 结束面试 ====================

    /**
     * 结束面试（用户主动）
     */
    public Boolean endInterview(Long sessionId, Long userId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null || session.getStatus() != 0) {
            return true;
        }
        doEndInterview(session, sessionId, userId);
        return true;
    }

    // ==================== 内部方法 ====================

    /**
     * 目标漂移处理：落库但不进对话历史（防 LLM 被带偏），第二次违规强制换题
     */
    private InterviewAnswerVO handleDrift(Long sessionId, InterviewSession session,
                                          String sanitizedAnswer, String driftResponse) {
        log.warn("目标漂移拦截: sessionId={}, response={}", sessionId, driftResponse);

        long currentRound = agentStateStore.getRound(sessionId);
        Long questionId = agentStateStore.getCurrentQuestion(sessionId);
        memoryFacade.saveRecordOnly(sessionId, questionId, "user", sanitizedAnswer, (int) currentRound + 1);
        memoryFacade.saveRecordOnly(sessionId, questionId, "assistant", driftResponse, (int) currentRound + 1);

        if (goalDriftDetector.shouldForceNextQuestion(sessionId)) {
            return handleNextQuestion(sessionId, session, currentRound + 1);
        }

        InterviewAnswerVO vo = new InterviewAnswerVO();
        vo.setReplyToUser(driftResponse);
        vo.setActionDirective(ActionDirectiveEnum.DEEP_DIVE.getValue());
        vo.setCurrentTopicMastery(0);
        vo.setIsEnded(false);
        return vo;
    }

    /**
     * 代码兜底换题：抽题（工具内更新工作记忆）-> 确定性过渡话术 -> 记忆落库
     * <p>
     * 触发场景：循环检测 / 漂移二次违规 / ThreeLayerController 强制换题（模型未调 pickQuestion）
     */
    private InterviewAnswerVO handleNextQuestion(Long sessionId, InterviewSession session, long currentRound) {
        ToolResult pickResult = toolExecutor.execute("pickQuestion",
                ToolInput.builder().sessionId(sessionId).build()
                        .with("mode", session.getMode())
                        .with("bankId", session.getBankId())
                        .with("excludeUsed", true));

        if (!pickResult.isSuccess() || pickResult.getData() == null) {
            // 题库已抽完，强制结束
            doEndInterview(session, sessionId, session.getUserId());
            InterviewAnswerVO vo = new InterviewAnswerVO();
            vo.setReplyToUser("题库已全部讨论完毕，本次面试到此结束。");
            vo.setActionDirective(ActionDirectiveEnum.END_INTERVIEW.getValue());
            vo.setIsEnded(true);
            return vo;
        }

        Question nextQuestion = pickResult.getDataAs(Question.class);

        // 过渡话术（确定性单次生成，不走 ReAct）
        AiInterviewResponseDTO transResp = interviewLlmService.generateTransition(nextQuestion);
        String replyToUser = transResp.getReplyToUser();

        memoryFacade.rememberTurn(sessionId, nextQuestion.getId(), "assistant", replyToUser, (int) currentRound);

        InterviewAnswerVO vo = new InterviewAnswerVO();
        vo.setReplyToUser(replyToUser);
        vo.setActionDirective(ActionDirectiveEnum.NEXT_QUESTION.getValue());
        vo.setCurrentTopicMastery(0);
        vo.setIsEnded(false);
        return vo;
    }

    /**
     * 实际结束面试：session 状态 -> 1 -> 发报告 MQ -> 清理短期记忆
     * <p>
     * 记忆整合（consolidate）由 KafkaReportConsumer 收到 Kafka 后异步执行：
     * 避免同步调 LLM 阻塞返回，且保证只整合一次（防画像重复累加）。
     */
    private void doEndInterview(InterviewSession session, Long sessionId, Long userId) {
        session.setStatus(1);
        sessionMapper.updateById(session);

        // 发 Kafka -> 异步生成报告 + 记忆整合
        kafkaReportProducer.sendInterviewReportEvent(sessionId, userId);

        goalDriftDetector.clear(sessionId);
        memoryFacade.clearAll(sessionId);
        agentStateStore.clear(sessionId);

        log.info("面试结束: sessionId={}, userId={}", sessionId, userId);
    }

    /**
     * 构建面试官 ReAct 任务描述（含可用动作软约束）
     */
    private String buildInterviewTask(Question currentQuestion, Long currentQuestionId,
                                      long questionRound, long totalRound,
                                      String userAnswer, Long sessionId, String actionsPrompt) {
        String title = currentQuestion != null && currentQuestion.getTitle() != null
                ? currentQuestion.getTitle() : "（未知）";
        List<String> history = memoryFacade.getRecentHistory(sessionId);
        String historyText = history.isEmpty() ? "（暂无历史对话）" : String.join("\n", history);

        return "当前题目：ID=" + currentQuestionId + "，标题：" + title + "\n"
                + "本题已追问轮次：" + questionRound + "；面试总轮次：" + totalRound + "\n\n"
                + "【可用动作】\n" + actionsPrompt + "\n\n"
                + "【最近对话】\n" + historyText + "\n\n"
                + "【候选人本轮回答】\n" + userAnswer + "\n\n"
                + "请先调用 getQuestionDetail 获取本题参考答案并评估候选人回答，"
                + "然后从可用动作中选择一个,最后输出 final_answer。";
    }

    /**
     * 解析 ReAct final_answer 为面试结构化响应，失败降级为兜底响应
     */
    private AiInterviewResponseDTO parseFinalAnswer(ReActResult reactResult) {
        if (!reactResult.isSuccess()) {
            log.warn("ReAct 循环失败，降级为兜底响应: {}", reactResult.getErrorMessage());
            return interviewLlmService.fallbackResponse();
        }
        try {
            AiInterviewResponseDTO dto = objectMapper.convertValue(
                    reactResult.getFinalAnswer(), AiInterviewResponseDTO.class);
            if (dto == null || dto.getReplyToUser() == null || dto.getReplyToUser().isBlank()) {
                return interviewLlmService.fallbackResponse();
            }
            if (dto.getCurrentTopicMastery() == null) {
                dto.setCurrentTopicMastery(0);
            }
            return dto;
        } catch (Exception e) {
            log.warn("final_answer 解析失败，降级为兜底响应: {}", e.getMessage());
            return interviewLlmService.fallbackResponse();
        }
    }
}
