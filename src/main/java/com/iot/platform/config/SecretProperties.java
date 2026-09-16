package com.iot.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 设备密钥加密配置。
 *
 * <p>{@code masterKey} 是 device_secret 加密存储的根密钥（AES-256，Base64 编码的 32 字节）。
 * 默认值仅供本地开发；生产必须通过环境变量 {@code IOT_DEVICE_SECRET_KEY} 覆盖 ——
 * 一旦用某个 key 加密过数据，更换 key 前必须先解密再重加密，否则历史密文将无法还原。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.device-secret")
public class SecretProperties {

    /** AES-256 主密钥（Base64 32 字节）；实际值由 application.yml 以 ${IOT_DEVICE_SECRET_KEY:…} 形式注入 */
    private String masterKey;
}
