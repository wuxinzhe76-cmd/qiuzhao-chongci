package com.charles.interview.arena.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TestCaseAddDTO {

    /**
     * 题目ID
     */
    @NotNull(message = "题目ID不能为空")
    private Long questionId;

    /**
     * 输入样例(喂给程序的 stdin)
     */
    @NotBlank(message = "输入不能为空")
    private String input;

    /**
     * 期望输出
     */
    @NotBlank(message = "输出不能为空")
    private String output;

    /**
     * 是否示例:0隐藏 1示例(默认0)
     */
    private Integer isExample = 0;

    /**
     * 分值(默认100)
     */
    private Integer score = 100;
}
