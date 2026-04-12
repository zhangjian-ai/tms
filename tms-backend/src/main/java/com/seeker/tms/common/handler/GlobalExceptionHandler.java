package com.seeker.tms.common.handler;

import com.seeker.tms.common.utils.Result;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(100)
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class) // 捕获的异常类型。这里捕获所有 Exception
    public Result<String> ex(Exception exception) {
        exception.printStackTrace();
        return Result.builder(-1, "error", exception.getMessage());
    }
}
