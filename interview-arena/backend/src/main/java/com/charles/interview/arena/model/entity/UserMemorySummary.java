package com.charles.interview.arena.model.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 用户记忆摘要实体（蓝图 §5.4.5 跨会话画像）
 * <p>
 * 一个用户对应一条记录（uk_user）。
 * weak_topics / preferred_tags 存 JSON 数组字符串。
 */
@Data
@TableName("user_memory_summary")
public class UserMemorySummary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Integer totalInterviews;

    private BigDecimal avgScore;

    /** Top-3 薄弱知识点（JSON 数组字符串） */
    private String weakTopics;

    /** 偏好标签（JSON 数组字符串） */
    private String preferredTags;

    /** AI 生成的推荐复习方向 */
    private String recommendedReview;

    private LocalDateTime lastInterviewTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
