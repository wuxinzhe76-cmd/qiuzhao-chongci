package com.charles.interview.arena.model.vo;

import lombok.Data;

/**
 * 开始面试响应 VO（蓝图 §5.4）
 */
@Data
public class InterviewStartVO {

    /** 面试会话 ID */
    private Long sessionId;

    /** 开场提问内容 */
    private String openingQuestion;
}
