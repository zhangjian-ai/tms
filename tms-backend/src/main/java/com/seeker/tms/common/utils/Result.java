package com.seeker.tms.common.utils;

import com.seeker.tms.common.enums.ResultStatus;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private int code;
    private String msg;
    private T data;

    public static <T> Result<T> builder(int code, String msg, T data) {
        return new Result<T>(code, msg, data);
    }

    public static <T> Result<T> success() {
        return new Result<T>(ResultStatus.SUCCESS.getCode(), ResultStatus.SUCCESS.getValue(), null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<T>(ResultStatus.SUCCESS.getCode(), ResultStatus.SUCCESS.getValue(), data);
    }

    public static <T> Result<T> fail() {
        return new Result<T>(ResultStatus.FAILED.getCode(), ResultStatus.FAILED.getValue(), null);
    }

    public static <T> Result<T> fail(T data) {
        return new Result<T>(ResultStatus.FAILED.getCode(), ResultStatus.FAILED.getValue(), data);
    }
}
