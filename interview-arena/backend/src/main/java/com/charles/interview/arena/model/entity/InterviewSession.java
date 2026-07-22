package com.charles.interview.arena.model.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 面试会话实体（蓝图 §4.3 + §5.4）
 * <p>
 * 一次模拟面试的会话主表，关联 interview_record 明细。
 * 主键用雪花算法（IdType.ASSIGN_ID），与蓝图设计一致。
 */
@Data
@TableName("interview_session")
public class InterviewSession {

    /** 主键（雪花算法） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 面试者 ID */
    private Long userId;

    /** 模式: 1-指定题库, 2-大厂随机 */
    private Integer mode;

    /** 关联题库 ID（mode=1 时有值） */
    private Long bankId;

    /** 0-进行中, 1-已结束, 2-已生成报告 */
    private Integer status;

    /** 本次面试综合评分（AI 生成） */
    private Integer score;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
