package com.charles.interview.arena.model.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;

import lombok.Data;

@Data
@TableName(value = "question", autoResultMap = true)
public class Question {

    /**
     * 主键ID(自增)
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 题目标题
     */
    private String title;

    /**
     * 题目内容
     */
    private String content;

    /**
     * 推荐答案
     */
    private String answer;

    /**
     * 标签列表(JSON数组,自动与 List<String> 互转)
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    /**
     * 类型:PROGRAMMING/CHOICE/FILL_IN
     */
    private String type;

    /**
     * 难度:EASY/MEDIUM/HARD
     */
    private String difficulty;

    /**
     * 代码模板(JSON)
     */
    private String template;

    /**
     * 时间限制(ms)
     */
    private Integer timeLimit;

    /**
     * 内存限制(MB)
     */
    private Integer memoryLimit;

    /**
     * 通过人数
     */
    private Integer acceptedCount;

    /**
     * 提交次数
     */
    private Integer submissionCount;

    /**
     * 通过率(%)
     */
    private BigDecimal acceptanceRate;

    /**
     * 创建用户ID
     */
    private Long userId;

    /**
     * 创建时间(插入时自动填充)
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间(插入和更新时自动填充)
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标志 (0:未删除, 1:已删除)
     */
    @TableLogic
    private Integer isDeleted;
}
