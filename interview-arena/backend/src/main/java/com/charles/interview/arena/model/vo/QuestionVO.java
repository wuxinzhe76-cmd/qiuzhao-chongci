package com.charles.interview.arena.model.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

@Data
public class QuestionVO {

    private Long id;
    private String title;
    private String content;
    private String answer;
    private List<String> tags;
    private String type;
    private String difficulty;
    private String template;
    private Integer timeLimit;
    private Integer memoryLimit;
    private Integer acceptedCount;
    private Integer submissionCount;
    private BigDecimal acceptanceRate;
    private Long userId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
