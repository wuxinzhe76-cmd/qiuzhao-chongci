package com.charles.interview.arena.exception;


import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.charles.interview.arena.common.BaseResponse;
import com.charles.interview.arena.common.ErrorCode;
import com.charles.interview.arena.common.ResultUtils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> handleBusinessException(BusinessException e) {
        log.warn("业务异常 - code: {}, message: {}", e.getCode(), e.getMessage());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public BaseResponse<?> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        // 1. 取第一个字段错误消息
        String message = e.getBindingResult()
                        .getFieldErrors()
                        .stream()
                        .findFirst()
                        .map(FieldError::getDefaultMessage)
                        .orElse("参数校验失败");
        
        // 2. 打 warn 日志
        log.warn("参数校验失败: {}", message);
        
        // 3. 返回 PARAMS_ERROR + 具体错误消息
        return ResultUtils.error(ErrorCode.PARAMS_ERROR.getCode(), message);
    }
    @ExceptionHandler(Exception.class)
    public BaseResponse<?> handleException(Exception e) {
        log.error("系统异常", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR);
    }
    
}
