package com.charles.interview.arena.model.vo;

import java.time.LocalDateTime;

import lombok.Data;

@Data
public class SubmissionVO {

    private Long id;
    private Long questionId;
    private String languageCode;
    private String status;
    private Integer executionTime;
    private Integer executionMemory;
    private Integer passedTestCase;
    private Integer totalTestCase;
    private String errorMessage;
    private LocalDateTime createTime;
    // 不含 code(防止代码泄露)
}
