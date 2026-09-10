package com.iot.platform.common;

/**
 * 错误码常量，对应 API契约.md §0.2
 */
public final class ErrorCode {

    //禁止外部 new ErrorCode()，防止实例化。
    private ErrorCode() {
    }

    /** 成功 */
    public static final int SUCCESS = 0;
    /** 参数错误 */
    public static final int PARAM_ERROR = 4001;
    /** 设备不存在 */
    public static final int DEVICE_NOT_FOUND = 4004;
    /** 鉴权失败 */
    public static final int AUTH_FAILED = 4010;
    /** 服务内部错误 */
    public static final int INTERNAL_ERROR = 5000;
}
