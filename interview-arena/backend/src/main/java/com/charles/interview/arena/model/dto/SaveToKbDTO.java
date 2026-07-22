package com.charles.interview.arena.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 保存到个人知识库请求 DTO（蓝图 §5.5.6 save-to-kb）
 */
@Data
public class SaveToKbDTO {

    @NotBlank(message = "问题不能为空")
    private String question;

    @NotBlank(message = "答案不能为空")
    private String answer;
}
