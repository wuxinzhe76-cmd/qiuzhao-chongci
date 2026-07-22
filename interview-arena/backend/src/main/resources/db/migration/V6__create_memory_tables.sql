-- ===================================================================
-- V6: 记忆架构表（蓝图 §5.4.5）
-- 用户知识画像（语义记忆） + 用户记忆摘要（跨会话画像）
-- ===================================================================

-- 用户知识画像表（语义记忆持久化）
CREATE TABLE IF NOT EXISTS `user_knowledge_profile` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        BIGINT       NOT NULL COMMENT '用户 ID',
    `topic`          VARCHAR(256) NOT NULL COMMENT '知识点名称（如 Java volatile）',
    `topic_vector`   JSON         DEFAULT NULL COMMENT '知识点向量（Milvus 同步用）',
    `avg_mastery`    INT          NOT NULL DEFAULT 0 COMMENT '该知识点历史平均掌握度 0-100',
    `exam_count`     INT          NOT NULL DEFAULT 0 COMMENT '被考察次数',
    `weak_count`     INT          NOT NULL DEFAULT 0 COMMENT '掌握度 < 60 的次数',
    `is_persistent`  TINYINT      NOT NULL DEFAULT 0 COMMENT '是否顽固薄弱点（连续2次<60）',
    `last_exam_time` DATETIME     DEFAULT NULL COMMENT '最近一次考察时间',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_topic` (`user_id`, `topic`),
    INDEX `idx_user_weak` (`user_id`, `avg_mastery`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户知识画像（语义记忆持久化）';

-- 用户记忆摘要表（跨会话画像）
CREATE TABLE IF NOT EXISTS `user_memory_summary` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`             BIGINT       NOT NULL COMMENT '用户 ID',
    `total_interviews`    INT          NOT NULL DEFAULT 0 COMMENT '总面试次数',
    `avg_score`           DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '历史平均分',
    `weak_topics`         VARCHAR(1024) DEFAULT NULL COMMENT 'Top-3 薄弱知识点（JSON 数组）',
    `preferred_tags`      VARCHAR(512)  DEFAULT NULL COMMENT '偏好标签（JSON 数组）',
    `recommended_review`  TEXT          DEFAULT NULL COMMENT 'AI 生成的推荐复习方向',
    `last_interview_time` DATETIME      DEFAULT NULL COMMENT '最近面试时间',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户记忆摘要（跨会话画像）';
