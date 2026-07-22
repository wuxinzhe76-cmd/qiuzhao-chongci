package com.charles.interview.arena.common;

public class ResultUtils {
    public static <T> BaseResponse<T> success(T data){
        return new BaseResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }
    public static BaseResponse<?> success(){
        return new BaseResponse<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage());
    }
    public static BaseResponse<?> error(ErrorCode errorCode){
        return new BaseResponse<>(errorCode.getCode(), errorCode.getMessage());
    }
    public static BaseResponse<?> error(int code, String message){
        return new BaseResponse<>(code, message);
    }
}
