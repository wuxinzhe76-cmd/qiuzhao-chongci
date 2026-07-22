package com.charles.interview.arena.model.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class QuestionAddDTO {

    /**
     * 主键ID(更新时必填,新增时为空)
     */
    private Long id;

    @NotBlank(message = "题目标题不能为空")
    @Size(max = 128, message = "题目标题最长128字符")
    private String title;

    private String content;

    private String answer;

    private List<String> tags;

    /**
     * 类型:PROGRAMMING/CHOICE/FILL_IN(默认 PROGRAMMING)
     */
    private String type;

    /**
     * 难度:EASY/MEDIUM/HARD(默认 MEDIUM)
     */
    private String difficulty;

    private String template;

    /**
     * 时间限制ms(默认 1000)
     */
    private Integer timeLimit;

    /**
     * 内存限制MB(默认 256)
     */
    private Integer memoryLimit;
}
