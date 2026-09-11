#include "onenet_ota.h"
#include "esp_ota_ops.h"
#include <string.h>
#include "esp_http_client.h"
#include "esp_log.h"
#include <string.h>
#include "onenet_token.h"
#include "onenet_mqtt.h"
#include "cJSON.h"
#include "esp_https_ota.h"
#include "esp_system.h"
#include "freertos/FreeRTOS.h"
#include "FreeRTOS/task.h"

#define TAG "onenet_ota"

#define TOKEN_VALID_TIME 2067652178

static char target_version[32] = {0};
static int task_id = 0;

static bool ota_task_running = false;
#define ONENET_OTA_URL "http://iot-api.heclouds.com/fuse-ota"
// 获取应用版本
// @return 应用版本字符串
const char *get_app_version(void)
{
    static char app_version[32] = {0};
    if (app_version[0] == 0)
    {
        const esp_partition_t *running = esp_ota_get_running_partition(); // 获取当前运行的分区
        esp_app_desc_t app_desc;
        esp_ota_get_partition_description(running, &app_desc);              // 获取应用描述
        snprintf(app_version, sizeof(app_version), "%s", app_desc.version); // 格式化版本字符串
    }
    return app_version;
}
// 设置应用是否有效
void set_app_valid(int valid)
{
    const esp_partition_t *running = esp_ota_get_running_partition(); // 获取当前运行的分区
    esp_ota_img_states_t state;
    if (esp_ota_get_state_partition(running, &state) == ESP_OK) // 获取当前分区的状态
    {
        if (state == ESP_OTA_IMG_PENDING_VERIFY)
        {
            if (valid)
            {
                esp_ota_mark_app_valid_cancel_rollback(); // 标记应用有效，取消回滚
            }
            else
            {
                esp_ota_mark_app_invalid_rollback_and_reboot(); // 标记应用无效，回滚并重启
            }
        }
    }
}

#define OA_BUFF_LEN 1024
static uint8_t ota_data_buffer[OA_BUFF_LEN] = {0}; // 用于存储OTA数据的缓冲区
static int ota_data_size = 0;                      // OTA数据的大小
esp_err_t _http_event_handler(esp_http_client_event_t *evt)
{
    switch (evt->event_id)
    {
    case HTTP_EVENT_ERROR:
        ESP_LOGD(TAG, "HTTP_EVENT_ERROR");
        break;
    case HTTP_EVENT_ON_CONNECTED:
        ESP_LOGD(TAG, "HTTP_EVENT_ON_CONNECTED");
        break;
    case HTTP_EVENT_HEADER_SENT:
        ESP_LOGD(TAG, "HTTP_EVENT_HEADER_SENT");
        break;
    case HTTP_EVENT_ON_HEADER:
        ESP_LOGD(TAG, "HTTP_EVENT_ON_HEADER, key=%s, value=%s", evt->header_key, evt->header_value);
        break;
    case HTTP_EVENT_ON_DATA: // 收到响应数据，（保存数据到缓冲区）
    {
        ESP_LOGD(TAG, "HTTP_EVENT_ON_DATA, len=%d", evt->data_len);
        printf("HTTP_EVENT_ON_DATA data=%.*s\r\n", evt->data_len, (char *)evt->data);
        int copy_len = 0;
        if (evt->data_len > OA_BUFF_LEN - ota_data_size)
        {
            copy_len = OA_BUFF_LEN - ota_data_size;
        }
        else
        {
            copy_len = evt->data_len;
        }
        memcpy(&ota_data_buffer[ota_data_size], evt->data, copy_len); // 复制数据到缓冲区
        ota_data_size += copy_len;
        break;
    }
    case HTTP_EVENT_ON_FINISH:
        ESP_LOGD(TAG, "HTTP_EVENT_ON_FINISH");
        break;
    case HTTP_EVENT_DISCONNECTED:
        ESP_LOGI(TAG, "HTTP_EVENT_DISCONNECTED");
        break;
    case HTTP_EVENT_REDIRECT:
        ESP_LOGI(TAG, "HTTP_EVENT_REDIRECT");
        break;
    default:
        break;
    }
    return ESP_OK;
}

/**
 * @brief 连接HTTP服务器
 *
 * @param url HTTP服务器URL
 * @param method HTTP请求方法
 * @param payload HTTP请求体
 * @return esp_err_t 错误码
 * */
static esp_err_t onenet_ota_http_connect(const char *url, esp_http_client_method_t method, const char *payload)
{
    esp_http_client_config_t config = {
        .url = url,
        .event_handler = _http_event_handler,
    };
    esp_http_client_handle_t client = esp_http_client_init(&config);
    char *token = (char *)malloc(256); // 用于存储token的缓冲区
    memset(token, 0, 256);             // 初始化token缓冲区
    dev_token_generate(token, SIG_METHOD_SHA256, TOKEN_VALID_TIME, ONENET_PRODUCT_ID, NULL, ONENET_ACCESS_KEY);
    ESP_LOGI(TAG, "Authorization(token)=%s", token);
    // POST

    esp_http_client_set_method(client, method);                             // 设置HTTP请求方法
    esp_http_client_set_header(client, "Content-Type", "application/json"); // 设置请求头
    esp_http_client_set_header(client, "host", "iot-api.heclouds.com");     // 设置Host头
    esp_http_client_set_header(client, "Authorization", token);             // 设置Authorization头

    if (payload)
    {
        ESP_LOGI(TAG, "POST data=%s", payload);
        esp_http_client_set_post_field(client, payload, strlen(payload)); // 设置POST请求体
    }

    memset(ota_data_buffer, 0, sizeof(ota_data_buffer));
    ota_data_size = 0;

    esp_err_t err = esp_http_client_perform(client); // 执行HTTP请求
    free(token);
    esp_http_client_cleanup(client);
    return err;
}

/**
 * @brief 上报应用版本
 *
 * @return esp_err_t 错误码
 * */
esp_err_t onenet_ota_upload_version(void)
{
    char url[128];
    char version[128];
    esp_err_t err1 = ESP_FAIL;
    const char *app_version = get_app_version();                                                                 // 获取应用版本
    snprintf(url, sizeof(url), ONENET_OTA_URL "/%s/%s/version", ONENET_PRODUCT_ID, ONENET_DEVICE_NAME);          // 构建URL字符串
    snprintf(version, sizeof(version), "{\"s_version\":\"%s\",\"f_version\":\"%s\"}", app_version, app_version); // 构建POST请求体
    esp_err_t err = onenet_ota_http_connect(url, HTTP_METHOD_POST, version);
    if (err == ESP_OK)
    {
        cJSON *root = cJSON_Parse((const char *)ota_data_buffer);
        if (root)
        {
            cJSON *code_js = cJSON_GetObjectItem(root, "code");
            if (code_js && cJSON_GetNumberValue(code_js) == 0)
                err1 = ESP_OK;
            cJSON_Delete(root);
        }
    }
    else if (err1 != ESP_OK)
    {
        ESP_LOGI(TAG, "上报应用版本失败");
    }
    return err1;
}

/**
 * @brief 检查OTA升级任务
 *
 * @param type OTA任务类型
 * @param version OTA任务版本
 * @return esp_err_t 错误码
 * */
esp_err_t onenet_ota_check_task(const char *type, const char *version)
{
    char url[128];
    esp_err_t err1 = ESP_FAIL;
    snprintf(url, sizeof(url), ONENET_OTA_URL "/%s/%s/check?type=%s&version=%s", ONENET_PRODUCT_ID, ONENET_DEVICE_NAME, type, version); // 构建URL字符串
    esp_err_t err = onenet_ota_http_connect(url, HTTP_METHOD_GET, NULL);                                                                // GET请求
    if (err == ESP_OK)
    {
        cJSON *root = cJSON_Parse((const char *)ota_data_buffer);
        if (root)
        {
            cJSON *code_js = cJSON_GetObjectItem(root, "code");
            cJSON *data_js = cJSON_GetObjectItem(root, "data");
            cJSON *target_js = cJSON_GetObjectItem(data_js, "target");
            cJSON *tid_js = cJSON_GetObjectItem(data_js, "tid");
            if (code_js && cJSON_GetNumberValue(code_js) == 0)
            {
                if (target_js && tid_js)
                {
                    snprintf(target_version, sizeof(target_version), "%s", cJSON_GetStringValue(target_js)); // 获取目标版本
                    task_id = cJSON_GetNumberValue(tid_js);                                                  // 获取任务ID
                    err1 = ESP_OK;
                }
            }
            else
            {
                ESP_LOGI(TAG, "检查OTA升级任务失败");
            }
            cJSON_Delete(root);
        }
    }
    return err1;
}
/**
 * @brief 上报OTA升级任务状态
 *
 * @param task_id OTA升级任务ID
 * @param step OTA升级任务步骤
 * @return esp_err_t 错误码
 * */
esp_err_t onenet_ota_upload_task_status(int task_id, int step)
{
    char url[128];
    char payload[16];
    esp_err_t err1 = ESP_FAIL;                                                                                     // 获取应用版本
    snprintf(url, sizeof(url), ONENET_OTA_URL "/%s/%s/%d/status", ONENET_PRODUCT_ID, ONENET_DEVICE_NAME, task_id); // 构建URL字符串
    snprintf(payload, sizeof(payload), "{\"step\":%d}", step);                                                     // 构建POST请求体
    esp_err_t err = onenet_ota_http_connect(url, HTTP_METHOD_POST, payload);
    if (err == ESP_OK)
    {
        cJSON *root = cJSON_Parse((const char *)ota_data_buffer);
        if (root)
        {
            cJSON *code_js = cJSON_GetObjectItem(root, "code");
            if (code_js && cJSON_GetNumberValue(code_js) == 0)
                err1 = ESP_OK;
            cJSON_Delete(root);
        }
    }
    else if (err1 != ESP_OK)
    {
        ESP_LOGI(TAG, "上报升级任务状态失败");
    }
    return err1;
}

esp_err_t onenet_ota_init_cb(esp_http_client_handle_t client)
{
    static char token[256]; // 用于存储token的缓冲区
    memset(token, 0, 256);  // 初始化token缓冲区
    dev_token_generate(token, SIG_METHOD_SHA256, TOKEN_VALID_TIME, ONENET_PRODUCT_ID, NULL, ONENET_ACCESS_KEY);
    // GET
    esp_http_client_set_method(client, HTTP_METHOD_GET);                    // 设置HTTP请求方法
    esp_http_client_set_header(client, "Content-Type", "application/json"); // 设置请求头
    esp_http_client_set_header(client, "host", "iot-api.heclouds.com");     // 设置Host头
    esp_http_client_set_header(client, "Authorization", token);             // 设置Authorization头
    ESP_LOGI(TAG, "Authorization(token)=%s", token);
    return ESP_OK;

}
/**
 * @brief 下载OTA升级任务
 *
 * @param task_id OTA升级任务ID
 * @return esp_err_t 错误码
 * */
esp_err_t onenet_ota_download(int task_id)
{
    char url[128];
    snprintf(url, sizeof(url), ONENET_OTA_URL "/%s/%s/%d/download", ONENET_PRODUCT_ID, ONENET_DEVICE_NAME, task_id); // 构建URL字符串
    esp_http_client_config_t http_config =
        {
            .url = url,
        };
    esp_https_ota_config_t ota_config =
        {
            .http_config = &http_config,
            .http_client_init_cb = onenet_ota_init_cb,
        };
    esp_err_t ota_ret = ESP_FAIL;
    ota_ret = esp_https_ota(&ota_config);
    if (ota_ret == ESP_OK)
    {
        ESP_LOGI(TAG, "OTA升级任务下载成功");
    }
    else
    {
        ESP_LOGI(TAG, "OTA升级任务下载失败, err=%d", ota_ret);
    }
    return ota_ret;
}

static void onenet_ota_task(void *param)
{
    esp_err_t err = ESP_FAIL;
    // 上报版本号
    err = onenet_ota_upload_version();
    if (err != ESP_OK)
    {
        ESP_LOGI(TAG, "Upload version failed, err=%d", err);
        goto delete_ota_task;
    }
    // 检测OTA升级任务
    err = onenet_ota_check_task("1", get_app_version());
     if (err != ESP_OK)
    {
        ESP_LOGI(TAG, "Check task failed, err=%d", err);
        goto delete_ota_task;
    }
    // 上报OTA升级任务状态
    err = onenet_ota_upload_task_status(task_id, 10);
     if (err != ESP_OK)
    {
        ESP_LOGI(TAG, "Upload task status failed, err=%d", err);
        goto delete_ota_task;
    }
    // 下载OTA升级任务
    err = onenet_ota_download(task_id);
     if (err != ESP_OK)
    {
        ESP_LOGI(TAG, "Download task failed, err=%d", err);
        goto delete_ota_task;
    }
    // 上报OTA升级任务状态
    err = onenet_ota_upload_task_status(task_id, 100);
     if (err != ESP_OK)
    {
        ESP_LOGI(TAG, "Upload task status failed, err=%d", err);
        goto delete_ota_task;
    }
    // 重启设备
    esp_restart();
delete_ota_task:
    ota_task_running = false;
    vTaskDelete(NULL);
}

void onenet_ota_start(void)
{
    if (ota_task_running)
    {
        ESP_LOGI(TAG, "OTA task is running, skip");
        return;
    }
    ota_task_running = true;
    ESP_LOGI(TAG, "Start OTA task");
    xTaskCreatePinnedToCore(onenet_ota_task, "onenet_ota_task", 8192, NULL, 2, NULL, 1);
}