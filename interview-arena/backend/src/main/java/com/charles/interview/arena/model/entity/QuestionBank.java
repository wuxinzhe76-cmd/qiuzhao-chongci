package com.charles.interview.arena.model.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("question_bank")
public class QuestionBank {

    /**
     * 主键ID(自增)
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 题库名称
     */
    private String title;

    /**
     * 描述
     */
    private String description;

    /**
     * 封面图URL
     */
    private String picture;

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
