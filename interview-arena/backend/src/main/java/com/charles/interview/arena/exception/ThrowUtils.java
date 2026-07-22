package com.charles.interview.arena.exception;

import com.charles.interview.arena.common.ErrorCode;

public class ThrowUtils {
    private ThrowUtils() {}

    public static void throwIf(boolean condition, ErrorCode errorCode) {
        if (condition) {
            throw new BusinessException(errorCode);
        }
    }

    public static void throwIf(boolean condition, ErrorCode errorCode, String message) {
        if (condition) {
            throw new BusinessException(errorCode, message);
        }
    }
}
