package com.charles.interview.arena.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class QuestionSubmitDTO {

    /**
     * 题目ID
     */
    @NotNull(message = "题目ID不能为空")
    private Long questionId;

    /**
     * 语言代码(java/python3)
     */
    @NotBlank(message = "语言代码不能为空")
    private String languageCode;

    /**
     * 用户提交的代码
     */
    @NotBlank(message = "代码不能为空")
    private String code;
}
