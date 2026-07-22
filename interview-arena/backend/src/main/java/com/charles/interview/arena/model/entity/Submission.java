package com.charles.interview.arena.model.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 代码提交表(流水表,不逻辑删除)
 */
@Data
@TableName("submission")
public class Submission {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 题目ID
     */
    private Long questionId;

    /**
     * 提交用户ID
     */
    private Long userId;

    /**
     * 语言代码(java/python3)
     */
    private String languageCode;

    /**
     * 用户提交的代码
     */
    private String code;

    /**
     * 判题状态:PENDING/JUDGING/ACCEPTED/WA/TLE/MLE/RE/CE
     */
    private String status;

    /**
     * 执行时间(ms)
     */
    private Integer executionTime;

    /**
     * 执行内存(KB)
     */
    private Integer executionMemory;

    /**
     * 总测试用例数
     */
    private Integer totalTestCase;

    /**
     * 通过用例数
     */
    private Integer passedTestCase;

    /**
     * 错误信息(CE/RE时)
     */
    private String errorMessage;

    /**
     * 提交IP
     */
    private String ip;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // 注意:流水表没有 isDeleted 字段,不加 @TableLogic
}
