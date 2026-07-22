package com.charles.interview.arena.agent.tool.impl;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.charles.interview.arena.admin.service.QuestionService;
import com.charles.interview.arena.agent.guardrail.tool.ToolPermission;
import com.charles.interview.arena.agent.memory.MemoryFacade;
import com.charles.interview.arena.agent.memory.semantic.model.WeakPoint;
import com.charles.interview.arena.agent.tool.api.ToolInput;
import com.charles.interview.arena.agent.tool.api.ToolResult;
import com.charles.interview.arena.mapper.InterviewSessionMapper;
import com.charles.interview.arena.mapper.QuestionBankQuestionMapper;
import com.charles.interview.arena.model.entity.InterviewSession;
import com.charles.interview.arena.model.entity.Question;
import com.charles.interview.arena.model.entity.QuestionBankQuestion;
import com.charles.interview.arena.model.enums.InterviewModeEnum;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 抽题工具（模型可调用）
 * <p>
 * 两种调用方：
 * - 编排层（面试开始）：isFirst=true，记忆驱动出题（老用户优先考薄弱点，新用户随机）
 * - ReAct 面试官（换题时）：模型自主调用，自动排除本场已用题目
 * <p>
 * 会话状态一致性：抽题成功后由本工具统一更新工作记忆
 * （setCurrentQuestion / resetQuestionRound / addUsedQuestion），
 * 无论调用方是代码还是模型，状态都不会漏更新。
 * <p>
 * mode/bankId 优先取入参；模型调用时通常不传，自动从 InterviewSession 加载。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PickQuestionTool implements com.charles.interview.arena.agent.tool.api.Tool {

    private final QuestionService questionService;
    private final QuestionBankQuestionMapper questionBankQuestionMapper;
    private final InterviewSessionMapper sessionMapper;
    private final MemoryFacade memoryFacade;

    @Override
    public String getName() { return "pickQuestion"; }

    @Override
    public String getDescription() { return "从题库抽取一道新题（自动排除本场已考过的题目，并切换为当前题目）"; }

    @Override
    public String getInputSchema() {
        return "{}（无需参数，面试模式与题库信息自动从当前会话获取）";
    }

    @Override
    public ToolPermission.Level getPermissionLevel() { return ToolPermission.Level.READ; }

    @Override
    public ToolResult execute(ToolInput input) {
        Long sessionId = input.getSessionId();
        Long userId = input.getUserId();
        boolean isFirst = Boolean.TRUE.equals(input.getParam("isFirst", Boolean.class));
        boolean excludeUsed = !Boolean.FALSE.equals(input.getParam("excludeUsed", Boolean.class));

        // mode/bankId 优先取入参（编排层直调），否则从会话加载（模型调用）
        Integer mode = input.getParam("mode", Integer.class);
        Long bankId = input.getParam("bankId", Long.class);
        if (mode == null && sessionId != null) {
            InterviewSession session = sessionMapper.selectById(sessionId);
            if (session != null) {
                mode = session.getMode();
                bankId = session.getBankId();
            }
        }

        InterviewModeEnum modeEnum = InterviewModeEnum.fromValue(mode);
        if (modeEnum == null) {
            return ToolResult.failure("面试模式非法: mode=" + mode);
        }

        Question question;
        if (isFirst) {
            question = pickFirstWithMemory(modeEnum, bankId, userId);
        } else {
            Set<String> usedIds = excludeUsed ? getUsedQuestionIds(sessionId) : Collections.emptySet();
            question = pickQuestion(modeEnum, bankId, usedIds);
        }

        if (question == null) {
            return ToolResult.failure("题库中暂无可用题目");
        }

        // 统一更新工作记忆：当前题目切换 + 追问轮次归零 + 记入已用集
        if (sessionId != null) {
            memoryFacade.setCurrentQuestion(sessionId, question.getId());
            memoryFacade.resetQuestionRound(sessionId);
            memoryFacade.addUsedQuestion(sessionId, question.getId());
        }

        log.info("抽题成功: questionId={}, title={}, isFirst={}", question.getId(), question.getTitle(), isFirst);
        return ToolResult.success(question);
    }

    /**
     * 记忆驱动出题：老用户优先考薄弱点，新用户随机
     */
    private Question pickFirstWithMemory(InterviewModeEnum mode, Long bankId, Long userId) {
        try {
            var profile = memoryFacade.retrieveProfile(userId);
            if (profile == null || profile.getTotalInterviews() == 0) {
                log.info("新用户，无画像，随机抽题: userId={}", userId);
                return pickQuestion(mode, bankId, Collections.emptySet());
            }

            List<WeakPoint> weakPoints = memoryFacade.retrievePersistentWeakPoints(userId);
            if (weakPoints != null && !weakPoints.isEmpty()) {
                WeakPoint worst = weakPoints.get(0);
                log.info("记忆驱动出题: userId={}, 薄弱点={}, mastery={}",
                        userId, worst.getTopic(), worst.getAvgMastery());

                LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<Question>()
                        .like(Question::getTitle, worst.getTopic())
                        .last("ORDER BY RAND() LIMIT 1");
                Question q = questionService.getOne(wrapper);
                if (q != null) return q;
            }
        } catch (Exception e) {
            log.warn("记忆驱动出题失败，降级为随机抽题: userId={}, err={}", userId, e.getMessage());
        }
        return pickQuestion(mode, bankId, Collections.emptySet());
    }

    /**
     * 从工作记忆获取已用题目集
     */
    private Set<String> getUsedQuestionIds(Long sessionId) {
        if (sessionId == null) return Collections.emptySet();
        Set<String> usedIds = memoryFacade.getUsedQuestions(sessionId);
        return usedIds != null ? usedIds : Collections.emptySet();
    }

    /**
     * 通用抽题方法
     */
    private Question pickQuestion(InterviewModeEnum mode, Long bankId, Set<String> excludeIds) {
        if (mode == InterviewModeEnum.SPECIFIED_BANK) {
            LambdaQueryWrapper<QuestionBankQuestion> wrapper = new LambdaQueryWrapper<QuestionBankQuestion>()
                    .eq(QuestionBankQuestion::getQuestionBankId, bankId);
            if (!excludeIds.isEmpty()) {
                wrapper.notIn(QuestionBankQuestion::getQuestionId,
                        excludeIds.stream().map(Long::parseLong).toList());
            }
            wrapper.last("ORDER BY RAND() LIMIT 1");
            QuestionBankQuestion rel = questionBankQuestionMapper.selectOne(wrapper);
            return rel != null ? questionService.getById(rel.getQuestionId()) : null;
        } else {
            LambdaQueryWrapper<Question> wrapper = new LambdaQueryWrapper<>();
            if (!excludeIds.isEmpty()) {
                wrapper.notIn(Question::getId,
                        excludeIds.stream().map(Long::parseLong).toList());
            }
            wrapper.last("ORDER BY RAND() LIMIT 1");
            return questionService.getOne(wrapper);
        }
    }
}
