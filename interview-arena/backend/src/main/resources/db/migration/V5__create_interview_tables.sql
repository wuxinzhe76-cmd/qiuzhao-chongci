-- ===================================================================
-- V5: AI 面试模块表（蓝图 §4.3 + §5.4）
-- ===================================================================

-- 面试会话表
CREATE TABLE IF NOT EXISTS `interview_session` (
    `id`          BIGINT      NOT NULL COMMENT '主键(雪花算法)' ,
    `user_id`     BIGINT      NOT NULL COMMENT '面试者 ID',
    `mode`        TINYINT     NOT NULL COMMENT '模式: 1-指定题库, 2-大厂随机',
    `bank_id`     BIGINT      DEFAULT NULL COMMENT '关联题库 ID(模式1有值)',
    `status`      TINYINT     NOT NULL DEFAULT 0 COMMENT '0-进行中, 1-已结束, 2-已生成报告',
    `score`       INT         DEFAULT NULL COMMENT '本次面试综合评分(AI生成)',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除:0未删 1已删',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_bank_id` (`bank_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='面试会话表';

-- 面试问答明细表
CREATE TABLE IF NOT EXISTS `interview_record` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `session_id`  BIGINT      NOT NULL COMMENT '关联的面试会话 ID',
    `question_id` BIGINT      DEFAULT NULL COMMENT '当前讨论的具体题目 ID',
    `role`        VARCHAR(20) NOT NULL COMMENT 'user 或 assistant',
    `content`     TEXT        DEFAULT NULL COMMENT '回答或提问内容',
    `round_num`   INT         DEFAULT NULL COMMENT '当前对话属于第几轮',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_session_id` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='面试问答明细表';
