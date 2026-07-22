package com.charles.interview.arena.model.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 判题结果详情(流水表,不逻辑删除)
 */
@Data
@TableName("judge_result")
public class JudgeResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 提交ID
     */
    private Long submissionId;

    /**
     * 题目ID
     */
    private Long questionId;

    /**
     * 提交用户ID
     */
    private Long userId;

    /**
     * 语言代码
     */
    private String languageCode;

    /**
     * 提交的代码(冗余存,便于追溯)
     */
    private String code;

    /**
     * 最终判定:ACCEPTED/WA/TLE/MLE/RE/CE
     */
    private String verdict;

    /**
     * 执行时间(ms)
     */
    private Integer executionTime;

    /**
     * 执行内存(KB)
     */
    private Integer executionMemory;

    /**
     * 通过用例数
     */
    private Integer passedTestCase;

    /**
     * 总用例数
     */
    private Integer totalTestCase;

    /**
     * 各用例结果(JSON数组)
     */
    private String testCaseResults;

    /**
     * 编译输出(CE时有值)
     */
    private String compileOutput;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 判题完成时间(手动 set,不自动填充)
     */
    private LocalDateTime judgeTime;

    /**
     * 创建时间(插入时自动填充)
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
