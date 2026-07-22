package com.charles.interview.arena.model.vo;

import lombok.Data;

/**
 * 提交面试回答响应 VO（蓝图 §5.4）
 */
@Data
public class InterviewAnswerVO {

    /** AI 回复内容 */
    private String replyToUser;

    /** 行为指令：DEEP_DIVE / NEXT_QUESTION / END_INTERVIEW */
    private String actionDirective;

    /** 当前题目掌握度 0-100 */
    private Integer currentTopicMastery;

    /** 面试是否已结束（END_INTERVIEW 触发或总轮次超限） */
    private Boolean isEnded;
}
