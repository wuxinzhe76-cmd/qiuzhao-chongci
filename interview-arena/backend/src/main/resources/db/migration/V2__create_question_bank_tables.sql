-- 题库表
CREATE TABLE IF NOT EXISTS `question_bank` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`       VARCHAR(128) NOT NULL COMMENT '题库名称',
    `description` TEXT         DEFAULT NULL COMMENT '描述',
    `picture`     VARCHAR(512) DEFAULT NULL COMMENT '封面图URL',
    `user_id`     BIGINT       NOT NULL COMMENT '创建用户ID',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0未删 1已删',
    PRIMARY KEY (`id`),
    INDEX `idx_title` (`title`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题库表';

-- 题目表
CREATE TABLE IF NOT EXISTS `question` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`            VARCHAR(128)  NOT NULL COMMENT '题目标题',
    `content`          TEXT          DEFAULT NULL COMMENT '题目内容',
    `answer`           TEXT          DEFAULT NULL COMMENT '推荐答案',
    `tags`             VARCHAR(1024) DEFAULT NULL COMMENT '标签(JSON数组)',
    `type`             VARCHAR(50)   NOT NULL DEFAULT 'PROGRAMMING' COMMENT '类型:PROGRAMMING/CHOICE/FILL_IN',
    `difficulty`       VARCHAR(20)   NOT NULL DEFAULT 'MEDIUM' COMMENT '难度:EASY/MEDIUM/HARD',
    `template`         TEXT          DEFAULT NULL COMMENT '代码模板(JSON)',
    `time_limit`       INT           NOT NULL DEFAULT 1000 COMMENT '时间限制(ms)',
    `memory_limit`     INT           NOT NULL DEFAULT 256 COMMENT '内存限制(MB)',
    `accepted_count`   INT           NOT NULL DEFAULT 0 COMMENT '通过人数',
    `submission_count` INT           NOT NULL DEFAULT 0 COMMENT '提交次数',
    `acceptance_rate`  DECIMAL(5,2)  NOT NULL DEFAULT 0.00 COMMENT '通过率(%)',
    `user_id`          BIGINT        NOT NULL COMMENT '创建用户ID',
    `create_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除:0未删 1已删',
    PRIMARY KEY (`id`),
    INDEX `idx_title` (`title`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题目表';

-- 题库-题目关联表(多对多,物理删除)
CREATE TABLE IF NOT EXISTS `question_bank_question` (
    `id`               BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question_bank_id` BIGINT   NOT NULL COMMENT '题库ID',
    `question_id`      BIGINT   NOT NULL COMMENT '题目ID',
    `user_id`          BIGINT   NOT NULL COMMENT '操作用户ID',
    `create_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_bank_question` (`question_bank_id`, `question_id`),
    INDEX `idx_question_id` (`question_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题库-题目关联表';