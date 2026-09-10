package com.iot.platform.common;

import lombok.Getter;

/**
 * 业务异常类，继承 RuntimeException
 */
@Getter
public class BizException extends RuntimeException {

    private final int code; //存储错误码

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
