package com.charles.interview.arena.judge.service.Impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.charles.interview.arena.judge.codesandbox.CodeSandbox;
import com.charles.interview.arena.judge.codesandbox.model.ExecuteResponse;
import com.charles.interview.arena.judge.enums.JudgeStatus;
import com.charles.interview.arena.judge.service.JudgeResultService;
import com.charles.interview.arena.judge.service.JudgeService;
import com.charles.interview.arena.judge.service.SubmissionService;
import com.charles.interview.arena.judge.service.TestCaseService;
import com.charles.interview.arena.model.entity.JudgeResult;
import com.charles.interview.arena.model.entity.Question;
import com.charles.interview.arena.model.entity.Submission;
import com.charles.interview.arena.model.entity.TestCase;
import com.charles.interview.arena.admin.service.QuestionService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class JudgeServiceImpl implements JudgeService {

    private final SubmissionService submissionService;
    private final QuestionService questionService;
    private final TestCaseService testCaseService;
    private final JudgeResultService judgeResultService;
    private final CodeSandbox codeSandbox;

    @Override
    public void doJudge(Long submissionId) {
        // ① 取 submission
        Submission submission = submissionService.getById(submissionId);

        // ② 取 question(拿 timeLimit + memoryLimit)
        Question question = questionService.getById(submission.getQuestionId());

        // ③ 取 testCases(这道题的所有用例)
        List<TestCase> testCases = testCaseService.lambdaQuery()
                .eq(TestCase::getQuestionId, submission.getQuestionId())
                .list();

        // ④ 更新 submission.status = JUDGING
        submission.setStatus(JudgeStatus.JUDGING.getValue());
        submissionService.updateById(submission);

        // ⑤ 逐个用例执行判题
        int passedCount = 0;
        String finalVerdict = JudgeStatus.ACCEPTED.getValue();
        int maxTime = 0;
        List<String> caseDetails = new ArrayList<>();

        for (TestCase tc : testCases) {
            // 1. 调用沙箱执行
            ExecuteResponse resp = codeSandbox.execute(
                    submission.getLanguageCode(),
                    submission.getCode(),
                    tc.getInput(),
                    question.getTimeLimit(),
                    question.getMemoryLimit()
            );

            // 2. 记录最大执行时间
            long execTime = resp.getExecutionTime();
            if (execTime > maxTime) {
                maxTime = (int) execTime;
            }

            // 3. 判断这个用例的结果(按优先级)
            String caseVerdict;
            String actualOutput = resp.getStdout() != null ? resp.getStdout().trim() : "";
            String expectedOutput = tc.getOutput() != null ? tc.getOutput().trim() : "";

            if (resp.getErrorMessage() != null && resp.getErrorMessage().contains("Time Limit")) {
                // 超时
                caseVerdict = JudgeStatus.TIME_LIMIT_EXCEEDED.getValue();
            } else if (resp.getExitCode() != 0) {
                // 运行时错误(非0退出)
                caseVerdict = JudgeStatus.RUNTIME_ERROR.getValue();
            } else if (actualOutput.equals(expectedOutput)) {
                // 输出匹配,通过
                caseVerdict = JudgeStatus.ACCEPTED.getValue();
                passedCount++;
            } else {
                // 答案错误
                caseVerdict = JudgeStatus.WRONG_ANSWER.getValue();
            }

            // 4. 记录用例详情(存 JSON 片段)
            caseDetails.add(String.format(
                    "{\"testCaseId\":%d,\"verdict\":\"%s\",\"time\":%d}",
                    tc.getId(), caseVerdict, execTime
            ));

            // 5. 第一个失败的用例决定最终状态
            if (finalVerdict.equals(JudgeStatus.ACCEPTED.getValue())
                    && !caseVerdict.equals(JudgeStatus.ACCEPTED.getValue())) {
                finalVerdict = caseVerdict;
            }
        }

        // ⑥ 存 judge_result
        JudgeResult judgeResult = new JudgeResult();
        judgeResult.setSubmissionId(submissionId);
        judgeResult.setQuestionId(submission.getQuestionId());
        judgeResult.setUserId(submission.getUserId());
        judgeResult.setLanguageCode(submission.getLanguageCode());
        judgeResult.setCode(submission.getCode());
        judgeResult.setVerdict(finalVerdict);
        judgeResult.setExecutionTime(maxTime);
        judgeResult.setPassedTestCase(passedCount);
        judgeResult.setTotalTestCase(testCases.size());
        judgeResult.setTestCaseResults("[" + String.join(",", caseDetails) + "]");
        judgeResult.setJudgeTime(LocalDateTime.now());
        judgeResultService.save(judgeResult);

        // ⑦ 更新 submission 最终状态
        submission.setStatus(finalVerdict);
        submission.setPassedTestCase(passedCount);
        submission.setTotalTestCase(testCases.size());
        submission.setExecutionTime(maxTime);
        submissionService.updateById(submission);
    }
}
