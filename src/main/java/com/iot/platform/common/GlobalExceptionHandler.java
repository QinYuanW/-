package com.iot.platform.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/*
@RestControllerAdvice (@ControllerAdvice + @ResponseBody)
作为全局增强类，对所有 Controller 生效（来自 @ControllerAdvice）
方法返回值自动序列化为 JSON 返回给前端（来自 @ResponseBody）
 */
@Slf4j
@RestControllerAdvice // 全局异常处理类
public class GlobalExceptionHandler {
/*
@ExceptionHandler
标记一个方法为"异常处理方法" —— 当控制器（Controller）执行过程中抛出指定类型的异常时，
Spring 会自动调用这个方法来处理异常，而不是让异常直接抛给上层或返回丑陋的 500 错误页面。
 */
    @ExceptionHandler(BizException.class) // 处理业务异常
    public Result<Void> handleBiz(BizException e) {
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class) // 处理参数验证异常
    public Result<Void> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getDefaultMessage())
                .orElse("参数错误");
        return Result.fail(ErrorCode.PARAM_ERROR, msg);
    }

    @ExceptionHandler(Exception.class) // 处理其他异常
    public Result<Void> handleOther(Exception e) {
        log.error("unhandled exception", e);
        return Result.fail(ErrorCode.INTERNAL_ERROR, "服务内部错误");
    }
}
