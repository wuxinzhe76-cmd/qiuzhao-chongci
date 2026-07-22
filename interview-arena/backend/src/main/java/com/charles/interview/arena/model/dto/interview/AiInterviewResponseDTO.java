package com.charles.interview.arena.model.dto.interview;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import lombok.Data;

/**
 * AI 面试结构化输出 DTO（蓝图 §5.4）
 * <p>
 * 蓝图要求：通义千问返回严格 JSON，三个字段：
 * <ul>
 *   <li>reply_to_user: 对候选人说的话</li>
 *   <li>action_directive: DEEP_DIVE / NEXT_QUESTION / END_INTERVIEW</li>
 *   <li>current_topic_mastery: 0-100 整数</li>
 * </ul>
 * <p>
 * 三层校验：
 * 1. JSON -> DTO 解析（Spring AI .entity() 自动完成）
 * 2. Bean Validation 字段校验（@NotBlank / @Pattern / @Min / @Max）
 * 3. 业务语义校验（LlmInvoker 中手动校验）
 */
@Data
public class AiInterviewResponseDTO {

    /** 对候选人说的话 */
    @NotBlank(message = "reply_to_user 不能为空")
    @JsonAlias("reply_to_user")
    private String replyToUser;

    /** 行为指令：DEEP_DIVE / NEXT_QUESTION / END_INTERVIEW */
    @NotBlank(message = "action_directive 不能为空")
    @Pattern(regexp = "DEEP_DIVE|NEXT_QUESTION|END_INTERVIEW",
             message = "action_directive 必须是 DEEP_DIVE / NEXT_QUESTION / END_INTERVIEW 之一")
    @JsonAlias("action_directive")
    private String actionDirective;

    /** 当前题目掌握度 0-100 */
    @NotNull(message = "current_topic_mastery 不能为空")
    @Min(value = 0, message = "current_topic_mastery 不能小于 0")
    @Max(value = 100, message = "current_topic_mastery 不能大于 100")
    @JsonAlias("current_topic_mastery")
    private Integer currentTopicMastery;
}
