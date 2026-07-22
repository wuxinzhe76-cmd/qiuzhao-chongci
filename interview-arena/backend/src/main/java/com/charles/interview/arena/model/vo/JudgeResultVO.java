package com.charles.interview.arena.model.vo;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class JudgeResultVO {

    private Long id;
    private Long submissionId;
    private String verdict;
    private Integer executionTime;
    private Integer passedTestCase;
    private Integer totalTestCase;
    private String testCaseResults;
    private String compileOutput;
    private String errorMessage;
    private LocalDateTime judgeTime;
    // 不含 code(脱敏)
}
