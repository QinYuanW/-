package com.iot.platform.common;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

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

    /**
     * 请求体反序列化失败（字段类型不对 / JSON 语法错）。
     *
     * <p><b>这条必须有。</b>否则它会落到下面的兜底 {@code Exception} → 返回 5000「服务内部错误」，
     * 客户端只会以为"服务挂了"，而真实原因是"你把这个字段的类型传错了"——排查方向被彻底带偏。
     *
     * <p>踩过的实例：{@code POST /api/v1/rules} 的 {@code action_payload} 后端要的是
     * <b>JSON 字符串</b>（控制台确实用 {@code JSON.stringify} 发的），而按"天真直觉"传一个 JSON 对象，
     * 就会得到一个 5000。当时为此花了十几分钟才定位到是类型问题。
     *
     * <p>这里把 Jackson 的报错<b>翻译成人话</b>：字段路径 + 期望类型。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        String detail = humanize(e);
        log.warn("request body not readable: {}", detail);
        return Result.fail(ErrorCode.PARAM_ERROR, "请求体格式不对：" + detail);
    }

    @ExceptionHandler(Exception.class) // 处理其他异常
    public Result<Void> handleOther(Exception e) {
        log.error("unhandled exception", e);
        return Result.fail(ErrorCode.INTERNAL_ERROR, "服务内部错误");
    }

    /** 把 Jackson / 语法错误翻译成「哪个字段、期望什么类型」 */
    private String humanize(HttpMessageNotReadableException e) {
        Throwable cause = e.getCause();
        if (cause instanceof MismatchedInputException mie) {
            String field = mie.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .filter(n -> n != null && !n.isBlank())
                    .collect(Collectors.joining("."));
            String want = mie.getTargetType() == null ? null : mie.getTargetType().getSimpleName();
            String where = field.isEmpty() ? "请求体" : "字段 " + field;
            String expect = want == null ? "" : "（期望 " + want + " 类型）";
            return where + " 类型不匹配" + expect + "，请检查 JSON 写法";
        }
        // JSON 语法错：Jackson 的多行消息第一行信息量最大，后面是源码片段与箭头
        Throwable specific = e.getMostSpecificCause();
        String msg = specific == null ? null : specific.getMessage();
        if (msg == null || msg.isBlank()) {
            return "JSON 解析失败";
        }
        return msg.split("\n")[0];
    }
}
