package com.seeker.tms.common.handler;

import com.seeker.tms.common.utils.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(100)
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public Result<String> ex(Exception exception) {
        log.error("请求处理异常: {}", exception.toString());
        return Result.builder(-1, "error", exception.getMessage());
    }
}
