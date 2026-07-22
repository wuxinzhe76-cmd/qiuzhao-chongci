package com.charles.interview.arena.exception;

import com.charles.interview.arena.common.ErrorCode;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int code;
    public BusinessException(ErrorCode errorCode){
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }
    public BusinessException(ErrorCode errorCode, String message){
        super(message);
        this.code = errorCode.getCode();
    }
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    
}
