#ifndef _DEVICE_DM_H_
#define _DEVICE_DM_H_
#include "cJSON.h"

/**
 * 设备物模型初始化（LED 外设等）
 */
void device_dm_init(void);

/**
 * 处理平台下发的单个属性设置
 * @param key 属性标识（如 switch / brightness / rgb）
 * @param value_js 属性值 cJSON 节点
 */
void device_property_handle(const char* key, cJSON* value_js);

/**
 * 生成上报属性 JSON 字符串（调用方负责 free）
 * 格式：{"temperature":26.5,"humidity":60.0,"switch":true,"brightness":50}
 * @return JSON 字符串（需 free）
 */
char* device_property_upload(void);

#endif
