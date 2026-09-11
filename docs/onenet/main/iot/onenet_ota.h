#ifndef ONENET_OTA_H
#define ONENET_OTA_H
#include "esp_err.h"

// 获取应用版本
// @return 应用版本字符串
const char* get_app_version(void);
// 设置应用是否有效
void set_app_valid(int valid);
// 上传应用版本号
 esp_err_t onenet_ota_upload_version(void);
// 启动OTA任务
 void onenet_ota_start(void);

#endif