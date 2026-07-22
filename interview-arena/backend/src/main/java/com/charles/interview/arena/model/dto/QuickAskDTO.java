package com.charles.interview.arena.model.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Quick Ask 请求 DTO（蓝图 §5.5.6）
 * <p>
 * 用户在主页搜索框直接提问，不限于题库已有的题目。
 */
@Data
public class QuickAskDTO {

    @NotBlank(message = "查询内容不能为空")
    private String query;
}
