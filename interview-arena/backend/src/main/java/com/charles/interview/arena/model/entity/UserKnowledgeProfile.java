package com.charles.interview.arena.model.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 用户知识画像实体（蓝图 §5.4.5 语义记忆持久化）
 * <p>
 * 一个用户的一个知识点对应一条记录（uk_user_topic）。
 * topic_vector 字段存 JSON 向量，用于 Milvus 同步备份（实际检索走 Milvus）。
 */
@Data
@TableName(value = "user_knowledge_profile", autoResultMap = true)
public class UserKnowledgeProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 知识点名称（如 Java volatile） */
    private String topic;

    /** 知识点向量（JSON 格式，Milvus 同步用） */
    private String topicVector;

    /** 该知识点历史平均掌握度 0-100 */
    private Integer avgMastery;

    /** 被考察次数 */
    private Integer examCount;

    /** 掌握度 < 60 的次数 */
    private Integer weakCount;

    /** 是否顽固薄弱点（连续 2 次 < 60） */
    private Integer isPersistent;

    private LocalDateTime lastExamTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
