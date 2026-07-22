package com.charles.interview.arena.model.enums;

import lombok.Getter;

/**
 * 题目难度枚举
 */
@Getter
public enum QuestionDifficulty {

    EASY("EASY", "简单"),
    MEDIUM("MEDIUM", "中等"),
    HARD("HARD", "困难");

    private final String value;
    private final String message;

    QuestionDifficulty(String value, String message) {
        this.value = value;
        this.message = message;
    }

    public static QuestionDifficulty fromValue(String value) {
        for (QuestionDifficulty difficulty : values()) {
            if (difficulty.value.equals(value)) {
                return difficulty;
            }
        }
        throw new IllegalArgumentException("未知题目难度: " + value);
    }
}
