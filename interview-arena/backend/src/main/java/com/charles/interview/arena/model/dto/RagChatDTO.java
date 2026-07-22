package com.charles.interview.arena.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RagChatDTO {

    @NotBlank(message = "提问内容不能为空")
    private String message;
}
