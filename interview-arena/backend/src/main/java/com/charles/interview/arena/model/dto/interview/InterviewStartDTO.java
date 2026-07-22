package com.charles.interview.arena.model.dto.interview;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 开始面试请求 DTO（蓝图 §5.4）
 * <p>
 * mode=1 指定题库：bankId 必填
 * mode=2 大厂随机：bankId 可空
 */
@Data
public class InterviewStartDTO {

    /** 模式：1-指定题库，2-大厂随机 */
    @NotNull(message = "面试模式不能为空")
    private Integer mode;

    /** 关联题库 ID（mode=1 时必填） */
    private Long bankId;
}
