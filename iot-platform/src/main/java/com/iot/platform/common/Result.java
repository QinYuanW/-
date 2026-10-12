package com.iot.platform.common;

import lombok.Data;

/**
 * 统一响应体，对应 API契约.md §0.1
 * { code, message, data }
 */
@Data
public class Result<T> {

    private int code; // 状态码
    private String message; // 提示信息
    private T data; // 数据

    /**
     * 成功响应
     */
    @SuppressWarnings("unchecked")
    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 0;
        r.message = "ok";
        r.data = data;
        return r;
    }

    /**
     * 成功响应，无数据
     */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /**
     * 失败响应
     */
    @SuppressWarnings("unchecked")
    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }
}
