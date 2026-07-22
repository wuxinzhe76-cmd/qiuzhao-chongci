package com.charles.interview.arena.model.dto.interview;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 提交面试回答 DTO（蓝图 §5.4）
 */
@Data
public class InterviewAnswerDTO {

    @NotNull(message = "sessionId 不能为空")
    private Long sessionId;

    @NotBlank(message = "回答内容不能为空")
    private String answer;
}
