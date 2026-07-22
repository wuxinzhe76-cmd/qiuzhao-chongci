package com.charles.interview.arena.agent.orchestration.interviewer;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.charles.interview.arena.common.ErrorCode;
import com.charles.interview.arena.exception.ThrowUtils;
import com.charles.interview.arena.mapper.InterviewSessionMapper;
import com.charles.interview.arena.model.dto.interview.InterviewAnswerDTO;
import com.charles.interview.arena.model.dto.interview.InterviewStartDTO;
import com.charles.interview.arena.model.entity.InterviewSession;
import com.charles.interview.arena.model.vo.InterviewAnswerVO;
import com.charles.interview.arena.model.vo.InterviewStartVO;
import com.charles.interview.arena.agent.orchestration.interviewer.InterviewOrchestrator;
import com.charles.interview.arena.agent.orchestration.interviewer.InterviewService;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 面试服务薄层（委托给 Orchestrator）
 * <p>
 * 职责：参数校验 + 权限校验 + 委托编排。
 * 不含业务逻辑，所有流程编排由 InterviewOrchestrator 完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewServiceImpl extends ServiceImpl<InterviewSessionMapper, InterviewSession>
        implements InterviewService {

    private final InterviewOrchestrator orchestrator;

    @Override
    public InterviewStartVO startInterview(InterviewStartDTO dto, Long userId) {
        // 参数校验
        ThrowUtils.throwIf(dto.getMode() == null, ErrorCode.PARAMS_ERROR, "面试模式不能为空");
        ThrowUtils.throwIf(dto.getMode() == 1 && dto.getBankId() == null,
                ErrorCode.PARAMS_ERROR, "指定题库模式下 bankId 不能为空");

        // 委托编排
        return orchestrator.startInterview(dto, userId);
    }

    @Override
    public InterviewAnswerVO answerInterview(InterviewAnswerDTO dto, Long userId) {
        ThrowUtils.throwIf(dto.getSessionId() == null, ErrorCode.PARAMS_ERROR, "sessionId 不能为空");
        ThrowUtils.throwIf(dto.getAnswer() == null || dto.getAnswer().isBlank(),
                ErrorCode.PARAMS_ERROR, "回答内容不能为空");

        return orchestrator.answerInterview(dto, userId);
    }

    @Override
    public Boolean endInterview(Long sessionId, Long userId) {
        ThrowUtils.throwIf(sessionId == null, ErrorCode.PARAMS_ERROR, "sessionId 不能为空");
        return orchestrator.endInterview(sessionId, userId);
    }
}
