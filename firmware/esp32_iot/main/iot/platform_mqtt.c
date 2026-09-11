#include "platform_mqtt.h"
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "esp_log.h"
#include "cJSON.h"
#include "mqtt_client.h"
#include "device_dm.h"

#define TAG "platform_mqtt"

static esp_mqtt_client_handle_t mqtt_handle = NULL;

// 下行指令主题：down/{device_key}/command
static void build_down_topic(char* buf, size_t len)
{
    snprintf(buf, len, "down/%s/command", IOT_DEVICE_KEY);
}

// 属性上报主题：up/{device_key}/property
static void build_up_property_topic(char* buf, size_t len)
{
    snprintf(buf, len, "up/%s/property", IOT_DEVICE_KEY);
}

// 指令回报主题：up/{device_key}/ack
static void build_up_ack_topic(char* buf, size_t len)
{
    snprintf(buf, len, "up/%s/ack", IOT_DEVICE_KEY);
}

/**
 * 处理平台下发的指令（down/{device_key}/command）
 * 报文格式：{"request_id":"...","command_type":1,"payload":{"property_key":"switch","value":true}}
 */
static void handle_command(const char* data)
{
    cJSON* root = cJSON_Parse(data);
    if (!root) {
        ESP_LOGW(TAG, "invalid command json: %s", data);
        return;
    }

    cJSON* id_js = cJSON_GetObjectItem(root, "request_id");
    const char* request_id = cJSON_GetStringValue(id_js);

    cJSON* payload_js = cJSON_GetObjectItem(root, "payload");
    if (payload_js) {
        // 逐属性执行：payload 内是 {property_key: value} 结构
        cJSON* item = payload_js->child;
        while (item) {
            device_property_handle(item->string, item);
            item = item->next;
        }
    }

    // 回报 ACK 到 up/{device_key}/ack
    if (request_id) {
        char topic[128];
        build_up_ack_topic(topic, sizeof(topic));
        cJSON* ack = cJSON_CreateObject();
        cJSON_AddStringToObject(ack, "request_id", request_id);
        cJSON_AddBoolToObject(ack, "success", 1);
        char* ack_str = cJSON_PrintUnformatted(ack);
        esp_mqtt_client_publish(mqtt_handle, topic, ack_str, strlen(ack_str), 1, 0);
        ESP_LOGI(TAG, "ack sent: %s", ack_str);
        cJSON_free(ack_str);
        cJSON_Delete(ack);
    }

    cJSON_Delete(root);
}

static void platform_mqtt_event_handler(void* handler_args,
                                        esp_event_base_t base,
                                        int32_t event_id,
                                        void* event_data)
{
    esp_mqtt_event_handle_t event = event_data;
    esp_mqtt_client_handle_t client = event->client;

    switch ((esp_mqtt_event_id_t)event_id) {
    case MQTT_EVENT_CONNECTED:
        ESP_LOGI(TAG, "mqtt connected to %s as %s", IOT_BROKER_URI, IOT_DEVICE_KEY);
        {
            char topic[128];
            build_down_topic(topic, sizeof(topic));
            esp_mqtt_client_subscribe_single(client, topic, 1);
            ESP_LOGI(TAG, "subscribed: %s", topic);
        }
        break;
    case MQTT_EVENT_DISCONNECTED:
        ESP_LOGW(TAG, "mqtt disconnected, auto-reconnecting...");
        break;
    case MQTT_EVENT_DATA:
        printf("TOPIC=%.*s\r\n", event->topic_len, event->topic);
        printf("DATA=%.*s\r\n", event->data_len, event->data);
        if (strstr(event->topic, "/command")) {
            handle_command(event->data);
        }
        break;
    case MQTT_EVENT_ERROR:
        ESP_LOGE(TAG, "mqtt error");
        break;
    default:
        break;
    }
}

esp_err_t platform_start(void)
{
    esp_mqtt_client_config_t mqtt_config;
    memset(&mqtt_config, 0, sizeof(mqtt_config));
    mqtt_config.broker.address.uri = IOT_BROKER_URI;
    mqtt_config.credentials.client_id = IOT_DEVICE_KEY;
    mqtt_config.credentials.username = IOT_DEVICE_KEY;
    mqtt_config.credentials.authentication.password = IOT_DEVICE_SECRET;

    mqtt_handle = esp_mqtt_client_init(&mqtt_config);
    esp_mqtt_client_register_event(mqtt_handle, ESP_EVENT_ANY_ID, platform_mqtt_event_handler, NULL);
    return esp_mqtt_client_start(mqtt_handle);
}

esp_err_t platform_post_property(const char* data)
{
    if (!mqtt_handle) {
        return ESP_FAIL;
    }
    char topic[128];
    build_up_property_topic(topic, sizeof(topic));
    ESP_LOGI(TAG, "upload topic:%s, payload:%s", topic, data);
    return esp_mqtt_client_publish(mqtt_handle, topic, data, strlen(data), 1, 0);
}
