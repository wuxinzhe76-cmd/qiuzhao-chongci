package com.charles.interview.arena.model.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 面试问答明细实体（蓝图 §4.3 + §5.4）
 * <p>
 * 每轮对话即时落库（不等面试结束批量刷入），防止异常中断丢数据。
 * role: user / assistant
 */
@Data
@TableName("interview_record")
public class InterviewRecord {

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联的面试会话 ID */
    private Long sessionId;

    /** 当前讨论的具体题目 ID */
    private Long questionId;

    /** user 或 assistant */
    private String role;

    /** 回答或提问内容 */
    private String content;

    /** 当前对话属于第几轮 */
    private Integer roundNum;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
