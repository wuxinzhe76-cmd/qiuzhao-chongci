package com.charles.interview.arena.judge.enums;

import lombok.Getter;

/**
 * 判题状态枚举
 * value: 数据库存储的值(缩写,省存储)
 * message: 中文描述(返回前端用)
 */
@Getter
public enum JudgeStatus {

    PENDING("PENDING", "等待判题"),
    JUDGING("JUDGING", "判题中"),
    ACCEPTED("ACCEPTED", "通过"),
    WRONG_ANSWER("WA", "答案错误"),
    TIME_LIMIT_EXCEEDED("TLE", "超时"),
    MEMORY_LIMIT_EXCEEDED("MLE", "内存超限"),
    RUNTIME_ERROR("RE", "运行时错误"),
    COMPILE_ERROR("CE", "编译错误");

    private final String value;
    private final String message;

    JudgeStatus(String value, String message) {
        this.value = value;
        this.message = message;
    }

    /**
     * 从字符串转枚举(数据库读出时用)
     */
    public static JudgeStatus fromValue(String value) {
        for (JudgeStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知判题状态: " + value);
    }
}
