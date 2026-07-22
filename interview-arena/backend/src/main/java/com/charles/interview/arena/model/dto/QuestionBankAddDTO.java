package com.charles.interview.arena.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class QuestionBankAddDTO {

    /**
     * 主键ID(更新时必填,新增时为空)
     */
    private Long id;

    @NotBlank(message = "题库名称不能为空")
    @Size(max = 128, message = "题库名称最长128字符")
    private String title;

    private String description;

    private String picture;
}
