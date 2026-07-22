package com.charles.interview.arena.model.enums;

import lombok.Getter;

/**
 * 面试模式枚举（蓝图 §5.4）
 */
@Getter
public enum InterviewModeEnum {

    SPECIFIED_BANK(1, "指定题库"),
    RANDOM_BIG_TECH(2, "大厂随机");

    private final Integer value;
    private final String message;

    InterviewModeEnum(Integer value, String message) {
        this.value = value;
        this.message = message;
    }

    public static InterviewModeEnum fromValue(Integer value) {
        if (value == null) {
            return null;
        }
        for (InterviewModeEnum m : values()) {
            if (m.value.equals(value)) {
                return m;
            }
        }
        return null;
    }
}
