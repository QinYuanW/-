#include "onenet_mqtt.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include "esp_log.h"
#include "cJSON.h"
#include "mqtt_client.h"
#include "onenet_token.h"
#include "onenet_dm.h"
#include "onenet_ota.h"

#define TAG "onenet_mqtt"

static void onenet_property_ack(const char* id,int code,const char* message);
static void onenet_ota_ack(const char* id,int code,const char* message);
static void onenet_subscribe(void);
// token有效时间（2030年1月1日）
#define TM_EXPIRE_TIME 1924833600

static esp_mqtt_client_handle_t mqtt_handle = NULL;

/**
 * @brief 处理MQTT事件
 * @param event_handler_arg 事件处理函数的参数
 * @param event_base 事件基础类型
 * @param event_id 事件ID
 * @param event_data 事件数据
 */
static void aliot_mqtt_event_handler(void *event_handler_arg,
                                     esp_event_base_t event_base,
                                     int32_t event_id,
                                     void *event_data)
{
    esp_mqtt_event_handle_t event = event_data;
    esp_mqtt_client_handle_t client = event->client;

    // your_context_t *context = event->context;
    switch ((esp_mqtt_event_id_t)event_id)
    {
    case MQTT_EVENT_CONNECTED: // 连接成功
        ESP_LOGI(TAG, "mqtt connected");
        onenet_subscribe();// 订阅主题
        cJSON *property_js = onenet_property_upload_dm();// 生成上报所有数据的cJSON对象
        char* data = cJSON_PrintUnformatted(property_js);// 转换为JSON字符串
        onenet_post_property_data(data);// 上报所有数据
        onenet_ota_upload_version();// 上传应用版本号
        set_app_valid(true);// 设置应用为有效
        cJSON_Delete(property_js);// 释放JSON对象内存
        free(data);// 释放JSON字符串内存
        // s_is_mqtt_connected = true;
        // 连接成功后，订阅测试主题
        // esp_mqtt_client_subscribe_single(s_mqtt_client,MQTT_SUBSCRIBE_TOPIC,1);
        break;
    case MQTT_EVENT_DISCONNECTED: // 连接断开
        ESP_LOGI(TAG, "mqtt disconnected");
        // s_is_mqtt_connected = false;
        break;
    case MQTT_EVENT_SUBSCRIBED: // 收到订阅消息ACK
        ESP_LOGI(TAG, " mqtt subscribed ack, msg_id=%d", event->msg_id);
        break;
    case MQTT_EVENT_UNSUBSCRIBED: // 收到解订阅消息ACK
        break;
    case MQTT_EVENT_PUBLISHED: // 收到发布消息ACK
        ESP_LOGI(TAG, "mqtt publish ack, msg_id=%d", event->msg_id);
        break;
    case MQTT_EVENT_DATA: // 收到Pub消息,接收到的下行数据都会到这里
        printf("TOPIC=%.*s\r\n", event->topic_len, event->topic); // 收到Pub消息直接打印出来
        printf("DATA=%.*s\r\n", event->data_len, event->data);

        if(strstr(event->topic,"property/set"))// 处理property/set消息(是否为property/set主题设置)
        {
            cJSON *property = cJSON_Parse(event->data);// 解析JSON字符串
            onenet_property_handle(property);// 处理property消息
            cJSON *id_js = cJSON_GetObjectItem(property,"id");
            const char* id = cJSON_GetStringValue(id_js);
            onenet_property_ack(id,200,"success");// 返回属性
            cJSON_Delete(property);// 释放JSON对象内存
        }
        else if (strstr(event->topic,"ota/inform"))
        {
              cJSON *ota_js = cJSON_Parse(event->data);// 解析JSON字符串
            cJSON *id_js = cJSON_GetObjectItem(ota_js,"id");

            const char* id = cJSON_GetStringValue(id_js);
            onenet_ota_ack(id,200,"success");// 返回OTA
            cJSON_Delete(ota_js);// 释放JSON对象内存

            onenet_ota_start();// 启动OTA任务
        }
      
        break;
    case MQTT_EVENT_ERROR:
        ESP_LOGI(TAG, "MQTT_EVENT_ERROR");
        break;
    default:
        break;
    }
}
/**
 * 启动onenet连接
 * @return 错误码
 */
esp_err_t onenet_start(void)
{
    esp_mqtt_client_config_t mqtt_config;
    memset(&mqtt_config, 0, sizeof(esp_mqtt_client_config_t));
    mqtt_config.broker.address.uri = "mqtt://mqtts.heclouds.com";
    mqtt_config.broker.address.port = 1883;

    mqtt_config.credentials.client_id = ONENET_DEVICE_NAME;
    mqtt_config.credentials.username = ONENET_PRODUCT_ID;

    // 密码，onenet平台使用token来作为密码，token直接使用dev_token_generate计算即可
    static char token[256];
    //strcpy(token, "version=2018-10-31&res=products%2FaUwL9Q32FG%2Fdevices%2Fesp32led01&et=1924833600&method=sha1&sign=21hnxALi6ZD1CCrq7kOiWTCN%2Fz4%3D");
    dev_token_generate(token, SIG_METHOD_SHA1, TM_EXPIRE_TIME, ONENET_PRODUCT_ID,
                       ONENET_DEVICE_NAME, ONENET_ACCESS_KEY);
    mqtt_config.credentials.authentication.password = token;

    mqtt_handle = esp_mqtt_client_init(&mqtt_config);
    esp_mqtt_client_register_event(mqtt_handle, ESP_EVENT_ANY_ID, aliot_mqtt_event_handler, NULL);
    return esp_mqtt_client_start(mqtt_handle);
}
/**
 * 返回属性设置确认(property/set_reply下行响应)
 * @param code 错误码
 * @param message 信息
 * @return mqtt连接参数
 */
static void onenet_property_ack(const char* id,int code,const char* message)
{
    char topic[128];
    // 构建回复主题，格式为：$sys/{product_id}/{device_id}/thing/property/set_reply
    snprintf(topic,sizeof(topic),"$sys/%s/%s/thing/property/set_reply",ONENET_PRODUCT_ID,ONENET_DEVICE_NAME);
    cJSON *reply_js = cJSON_CreateObject();
    cJSON_AddStringToObject(reply_js,"id",id);
    cJSON_AddNumberToObject(reply_js,"code",code);
    cJSON_AddStringToObject(reply_js,"message",message);
    char* data = cJSON_PrintUnformatted(reply_js);// 转换为JSON字符串，不包含引号
    esp_mqtt_client_publish(mqtt_handle,topic,data,strlen(data),1,0); // 发布回复消息
    cJSON_free(data);// 释放JSON字符串内存
    cJSON_Delete(reply_js);// 释放JSON对象内存
}
/**
 * 返回OTA设置确认(ota/inform_reply下行响应)
 * @param code 错误码
 * @param message 信息
 * @return mqtt连接参数
 */
static void onenet_ota_ack(const char* id,int code,const char* message)
{
    char topic[128];
    snprintf(topic,sizeof(topic),"$sys/%s/%s/ota/inform_reply",ONENET_PRODUCT_ID,ONENET_DEVICE_NAME);
    cJSON *reply_js = cJSON_CreateObject();
    cJSON_AddStringToObject(reply_js,"id",id);
    cJSON_AddNumberToObject(reply_js,"code",code);
    cJSON_AddStringToObject(reply_js,"message",message);
    char* data = cJSON_PrintUnformatted(reply_js);// 转换为JSON字符串，不包含引号
    esp_mqtt_client_publish(mqtt_handle,topic,data,strlen(data),1,0); // 发布回复消息
    cJSON_free(data);// 释放JSON字符串内存
    cJSON_Delete(reply_js);// 释放JSON对象内存
}
/**
 * 订阅主题
 */
static void onenet_subscribe(void)
{
    char topic[128];
    // 构建订阅上报主题，格式为：$sys/{product_id}/{device_id}/thing/property/post/reply
    snprintf(topic,sizeof(topic),"$sys/%s/%s/thing/property/post/reply",ONENET_PRODUCT_ID,ONENET_DEVICE_NAME);
    esp_mqtt_client_subscribe_single(mqtt_handle,topic,1);// 订阅上报属性主题

    snprintf(topic,sizeof(topic),"$sys/%s/%s/thing/property/set",ONENET_PRODUCT_ID,ONENET_DEVICE_NAME);
    esp_mqtt_client_subscribe_single(mqtt_handle,topic,1);// 订阅下发属性控制主题

      snprintf(topic,sizeof(topic),"$sys/%s/%s/ota/inform",ONENET_PRODUCT_ID,ONENET_DEVICE_NAME);
    esp_mqtt_client_subscribe_single(mqtt_handle,topic,1);// 订阅OTA升级主题
}
/**
 * 上报数据
 * @param data 数据
 * @return 错误
 */
esp_err_t onenet_post_property_data(const char* data)
{
    if (!mqtt_handle)
        return ESP_FAIL;
    char topic[128];
    snprintf(topic,sizeof(topic),"$sys/%s/%s/thing/property/post",
        ONENET_PRODUCT_ID,ONENET_DEVICE_NAME);
    ESP_LOGI(TAG,"Upload topic:%s,payload:%s",topic,data);
    return esp_mqtt_client_publish(mqtt_handle,topic,data,strlen(data),1,0);
}