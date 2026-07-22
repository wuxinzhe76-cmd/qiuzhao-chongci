package com.charles.interview.arena.model.enums;

import lombok.Getter;

/**
 * 题目类型枚举
 */
@Getter
public enum QuestionType {

    PROGRAMMING("PROGRAMMING", "编程题"),
    CHOICE("CHOICE", "选择题"),
    FILL_IN("FILL_IN", "填空题");

    private final String value;
    private final String message;

    QuestionType(String value, String message) {
        this.value = value;
        this.message = message;
    }

    public static QuestionType fromValue(String value) {
        for (QuestionType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知题目类型: " + value);
    }
}
