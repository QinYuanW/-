#ifndef _PLATFORM_MQTT_H_
#define _PLATFORM_MQTT_H_
#include "esp_err.h"

// ============ 平台接入配置（按实际环境修改） ============
// EMQX broker 地址：真实设备连局域网/公网 EMQX，填对应 IP
// 当前：电脑 WLAN IP 10.194.159.62（网段变化时需同步改）
#define IOT_BROKER_URI      "mqtt://10.194.159.62:1883"
// 设备接入凭据（对应 iot_platform.device 表 device_key / device_secret）
#define IOT_DEVICE_KEY      "dev_demo_001"
#define IOT_DEVICE_SECRET   "demo_secret_123"
// 属性上报周期（毫秒）
#define IOT_REPORT_INTERVAL_MS  5000
// ========================================================

/**
 * 启动平台 MQTT 连接（阻塞等待 WiFi 就绪后调用）
 * @return 错误码
 */
esp_err_t platform_start(void);

/**
 * 上报属性数据（发布到 up/{device_key}/property）
 * @param data 属性 JSON 字符串，如 {"temperature":26.5,"humidity":60,"switch":true}
 * @return 错误码
 */
esp_err_t platform_post_property(const char* data);

#endif
