package com.iot.platform.common;

/**
 * 永久性遥测失败（不可重试）。
 *
 * <p>D2-2 背压的核心：把「重试多少次都不会成功」的失败与「可能自愈」的失败彻底分开。
 * <ul>
 *   <li><b>永久（本异常）</b>：device_key 从未注册、报文反序列化失败 / 属性为空 —— 脏数据不会自愈，
 *       直接送死信，无权占用重试阶梯；</li>
 *   <li><b>瞬时（其他 RuntimeException）</b>：DB 连接断开 / 超时 / 死锁 —— 退避重试，耗尽后再进死信。</li>
 * </ul>
 *
 * <p>此前两者共用 {@code processSync} 的 boolean 返回值，无法区分：永久失败被无意义重试，
 * 瞬时失败被直接丢弃（BUG-21 的实验证明了后者会静默丢数据）。
 */
public class PermanentTelemetryException extends RuntimeException {

    public PermanentTelemetryException(String message) {
        super(message);
    }

    public PermanentTelemetryException(String message, Throwable cause) {
        super(message, cause);
    }
}
