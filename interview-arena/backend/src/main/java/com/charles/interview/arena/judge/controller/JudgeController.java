package com.charles.interview.arena.judge.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.charles.interview.arena.common.BaseResponse;
import com.charles.interview.arena.common.ErrorCode;
import com.charles.interview.arena.common.ResultUtils;
import com.charles.interview.arena.exception.ThrowUtils;
import com.charles.interview.arena.judge.enums.JudgeStatus;
import com.charles.interview.arena.judge.mq.JudgeProducer;
import com.charles.interview.arena.judge.service.JudgeResultService;
import com.charles.interview.arena.judge.service.JudgeService;
import com.charles.interview.arena.judge.service.SubmissionService;
import com.charles.interview.arena.model.dto.QuestionSubmitDTO;
import com.charles.interview.arena.model.entity.JudgeResult;
import com.charles.interview.arena.model.entity.Submission;
import com.charles.interview.arena.model.vo.JudgeResultVO;
import com.charles.interview.arena.model.vo.SubmissionVO;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/judge")
@RequiredArgsConstructor
public class JudgeController {

    private final SubmissionService submissionService;
    private final JudgeResultService judgeResultService;
    private final JudgeProducer judgeProducer;
    private final JudgeService judgeService;

    /**
     * 提交代码(异步判题)
     * 1. 存 submission(status=PENDING)
     * 2. 发 MQ 消息
     * 3. 立即返回 submissionId
     */
    @PostMapping("/submit")
    public BaseResponse<Long> submit(@Valid @RequestBody QuestionSubmitDTO dto, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");

        // 1. 存提交记录
        Submission submission = new Submission();
        submission.setQuestionId(dto.getQuestionId());
        submission.setUserId(userId);
        submission.setLanguageCode(dto.getLanguageCode());
        submission.setCode(dto.getCode());
        submission.setStatus(JudgeStatus.PENDING.getValue());
        boolean saved = submissionService.save(submission);
        ThrowUtils.throwIf(!saved, ErrorCode.OPERATION_ERROR, "提交失败");

        // 2. 发 MQ 消息(异步判题)
        judgeProducer.sendJudgeMessage(submission.getId());

        // 3. 立即返回 submissionId(不等判题完成)
        return ResultUtils.success(submission.getId());
    }

    /**
     * 【本地测试用】同步执行判题,不走 MQ
     * 用于无 Docker 环境时,快速验证 JudgeService 判题逻辑
     */
    @PostMapping("/test-sync/{submissionId}")
    public BaseResponse<String> testSyncJudge(@PathVariable Long submissionId) {
        judgeService.doJudge(submissionId);
        return ResultUtils.success("判题完成, submissionId=" + submissionId);
    }

    /**
     * 查询提交状态(前端轮询用)
     * 前端拿到 submissionId 后,每秒调一次这个接口
     * status 为 PENDING/JUDGING 时继续等,其他状态表示判题完成
     */
    @GetMapping("/status/{submissionId}")
    public BaseResponse<SubmissionVO> getStatus(@PathVariable Long submissionId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");

        Submission submission = submissionService.getById(submissionId);
        ThrowUtils.throwIf(submission == null, ErrorCode.NOT_FOUND_ERROR, "提交记录不存在");
        ThrowUtils.throwIf(!submission.getUserId().equals(userId), ErrorCode.NO_AUTH_ERROR, "无权查看他人提交");

        SubmissionVO vo = new SubmissionVO();
        BeanUtils.copyProperties(submission, vo);
        return ResultUtils.success(vo);
    }

    /**
     * 查询判题详情(各用例结果)
     * 判题完成后,前端调这个接口展示详情
     */
    @GetMapping("/result/{submissionId}")
    public BaseResponse<JudgeResultVO> getResult(@PathVariable Long submissionId, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");

        // 查判题结果(按 submissionId 查)
        JudgeResult judgeResult = judgeResultService.lambdaQuery()
                .eq(JudgeResult::getSubmissionId, submissionId)
                .one();
        ThrowUtils.throwIf(judgeResult == null, ErrorCode.NOT_FOUND_ERROR, "判题结果不存在");
        ThrowUtils.throwIf(!judgeResult.getUserId().equals(userId), ErrorCode.NO_AUTH_ERROR, "无权查看他人结果");

        JudgeResultVO vo = new JudgeResultVO();
        BeanUtils.copyProperties(judgeResult, vo);
        return ResultUtils.success(vo);
    }
}
