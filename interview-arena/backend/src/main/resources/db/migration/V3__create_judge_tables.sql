-- 编程语言表
CREATE TABLE IF NOT EXISTS `programming_language` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `language_name`   VARCHAR(64)  NOT NULL COMMENT '语言名称(Java/Python3)',
    `language_code`   VARCHAR(32)  NOT NULL COMMENT '语言代码(java/python3)',
    `version`         VARCHAR(64)  DEFAULT NULL COMMENT '版本号',
    `compile_command` VARCHAR(512) DEFAULT NULL COMMENT '编译命令(解释型语言为空)',
    `run_command`     VARCHAR(512) NOT NULL COMMENT '运行命令',
    `icon`            VARCHAR(512) DEFAULT NULL COMMENT '图标URL',
    `is_active`       TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用:0禁用 1启用',
    `user_id`         BIGINT       NOT NULL COMMENT '创建用户ID',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除:0未删 1已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_language_code` (`language_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='编程语言表';

-- 测试用例表
CREATE TABLE IF NOT EXISTS `test_case` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question_id` BIGINT   NOT NULL COMMENT '题目ID',
    `input`       TEXT     NOT NULL COMMENT '输入样例',
    `output`      TEXT     NOT NULL COMMENT '期望输出',
    `is_example`  TINYINT  NOT NULL DEFAULT 0 COMMENT '0隐藏 1示例(前端展示)',
    `score`       INT      NOT NULL DEFAULT 100 COMMENT '分值',
    `user_id`     BIGINT   NOT NULL COMMENT '创建用户ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted`  TINYINT  NOT NULL DEFAULT 0 COMMENT '逻辑删除:0未删 1已删',
    PRIMARY KEY (`id`),
    INDEX `idx_question_id` (`question_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='测试用例表';

-- 代码提交表(流水表,不逻辑删除)
CREATE TABLE IF NOT EXISTS `submission` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question_id`      BIGINT      NOT NULL COMMENT '题目ID',
    `user_id`          BIGINT      NOT NULL COMMENT '提交用户ID',
    `language_code`    VARCHAR(32) NOT NULL COMMENT '语言代码(java/python3)',
    `code`             TEXT        NOT NULL COMMENT '用户提交的代码',
    `status`           VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '判题状态:PENDING/JUDGING/ACCEPTED/WA/TLE/MLE/RE/CE',
    `execution_time`   INT         DEFAULT NULL COMMENT '执行时间(ms)',
    `execution_memory` INT         DEFAULT NULL COMMENT '执行内存(KB)',
    `total_test_case`  INT         DEFAULT NULL COMMENT '总测试用例数',
    `passed_test_case` INT         DEFAULT NULL COMMENT '通过用例数',
    `error_message`    TEXT        DEFAULT NULL COMMENT '错误信息(CE/RE时)',
    `ip`               VARCHAR(64) DEFAULT NULL COMMENT '提交IP',
    `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_question_id` (`question_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='代码提交表';

-- 判题结果详情表(流水表,不逻辑删除)
CREATE TABLE IF NOT EXISTS `judge_result` (
    `id`                 BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `submission_id`      BIGINT      NOT NULL COMMENT '提交ID',
    `question_id`        BIGINT      NOT NULL COMMENT '题目ID',
    `user_id`            BIGINT      NOT NULL COMMENT '提交用户ID',
    `language_code`      VARCHAR(32) NOT NULL COMMENT '语言代码',
    `code`               TEXT        NOT NULL COMMENT '提交的代码(冗余存)',
    `verdict`            VARCHAR(32) NOT NULL COMMENT '最终判定:ACCEPTED/WA/TLE/MLE/RE/CE',
    `execution_time`     INT         DEFAULT NULL COMMENT '执行时间(ms)',
    `execution_memory`   INT         DEFAULT NULL COMMENT '执行内存(KB)',
    `passed_test_case`   INT         DEFAULT NULL COMMENT '通过用例数',
    `total_test_case`    INT         DEFAULT NULL COMMENT '总用例数',
    `test_case_results`  TEXT        DEFAULT NULL COMMENT '各用例结果(JSON数组)',
    `compile_output`     TEXT        DEFAULT NULL COMMENT '编译输出(CE时有值)',
    `error_message`      TEXT        DEFAULT NULL COMMENT '错误信息',
    `judge_time`         DATETIME    DEFAULT NULL COMMENT '判题完成时间',
    `create_time`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_submission_id` (`submission_id`),
    INDEX `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='判题结果详情表';
