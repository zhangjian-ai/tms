package com.seeker.tms.common.handler;

import com.seeker.tms.common.utils.Result;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

@Order(1)
@RestControllerAdvice
public class ArgumentsExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidationExceptions(MethodArgumentNotValidException ex, WebRequest request) {

        Result<?> result = Result.builder(-1, ex.getBindingResult().getFieldErrors().toString(), null);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(result);
    }
}

