package com.charles.interview.arena.agent.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.charles.interview.arena.common.BaseResponse;
import com.charles.interview.arena.common.ErrorCode;
import com.charles.interview.arena.common.ResultUtils;
import com.charles.interview.arena.exception.ThrowUtils;
import com.charles.interview.arena.model.dto.interview.InterviewAnswerDTO;
import com.charles.interview.arena.model.dto.interview.InterviewStartDTO;
import com.charles.interview.arena.model.vo.InterviewAnswerVO;
import com.charles.interview.arena.model.vo.InterviewStartVO;
import com.charles.interview.arena.agent.orchestration.interviewer.InterviewService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 面试控制器（蓝图 §5.4）
 * <p>
 * 三个接口：
 * <ul>
 *   <li>POST /api/interview/start  开始面试（mode + bankId）</li>
 *   <li>POST /api/interview/answer 提交回答（sessionId + answer）</li>
 *   <li>POST /api/interview/end/{sessionId} 结束面试</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/interview")
@RequiredArgsConstructor
public class InterviewController {

    private final InterviewService interviewService;

    /**
     * 开始面试
     */
    @PostMapping("/start")
    public BaseResponse<InterviewStartVO> start(@Valid @RequestBody InterviewStartDTO dto,
                                                HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "未登录");
        InterviewStartVO vo = interviewService.startInterview(dto, userId);
        return ResultUtils.success(vo);
    }

    /**
     * 提交回答（循环调用）
     */
    @PostMapping("/answer")
    public BaseResponse<InterviewAnswerVO> answer(@Valid @RequestBody InterviewAnswerDTO dto,
                                                  HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "未登录");
        InterviewAnswerVO vo = interviewService.answerInterview(dto, userId);
        return ResultUtils.success(vo);
    }

    /**
     * 结束面试（用户主动 / 自动触发）
     */
    @PostMapping("/end/{sessionId}")
    public BaseResponse<Boolean> end(@PathVariable Long sessionId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        ThrowUtils.throwIf(userId == null, ErrorCode.NOT_LOGIN_ERROR, "未登录");
        Boolean result = interviewService.endInterview(sessionId, userId);
        return ResultUtils.success(result);
    }
}
