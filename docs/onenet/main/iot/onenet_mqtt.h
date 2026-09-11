#ifndef _ONENET_MQTT_H_
#define _ONENET_MQTT_H_
#include "esp_err.h"

//产品ID
#define  ONENET_PRODUCT_ID  "aUwL9Q32FG"

//产品秘钥
//#define  ONENET_ACCESS_KEY  "Q49Lb1Ubn3NBDSEFxZnH2yl1t1MphYmh/3M5A2"
#define  ONENET_ACCESS_KEY    "Q49Lb1Ubn3NBDSEFxZnH2yl1t1MphYmh/3M5A2EPge8="

//设备名称
#define ONENET_DEVICE_NAME  "esp32led01"
/**
 * 启动onenet连接
 * @return 错误码
 */
esp_err_t onenet_start(void);

esp_err_t onenet_post_property_data(const char* data);
#endif