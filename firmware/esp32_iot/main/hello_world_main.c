#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/task.h"
#include "wifi_manager.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "cJSON.h"
#include "platform_mqtt.h"
#include "device_dm.h"

#define TAG "main"

static EventGroupHandle_t s_wifi_ev = NULL;
#define EV_WIFI_CONNECTED_BIT (1 << 0)

static void wifi_state_callback(WIFI_STATE state)
{
    if (state == WIFI_STATE_CONNECTED) {
        xEventGroupSetBits(s_wifi_ev, EV_WIFI_CONNECTED_BIT);
    }
}

// 周期上报任务：连接成功后启动
static void report_task(void* arg)
{
    while (1) {
        char* data = device_property_upload();
        platform_post_property(data);
        free(data);
        vTaskDelay(pdMS_TO_TICKS(IOT_REPORT_INTERVAL_MS));
    }
}

void app_main(void)
{
    nvs_flash_init();
    device_dm_init();
    s_wifi_ev = xEventGroupCreate();
    wifi_manager_init(wifi_state_callback);
    wifi_manager_connect("test", "18888888");

    EventBits_t ev;
    while (1) {
        ev = xEventGroupWaitBits(s_wifi_ev, EV_WIFI_CONNECTED_BIT, pdTRUE, pdFALSE, pdMS_TO_TICKS(5000));
        if (ev & EV_WIFI_CONNECTED_BIT) {
            ESP_LOGI(TAG, "wifi connected, starting platform mqtt...");
            platform_start();
            xTaskCreate(report_task, "report", 4096, NULL, 5, NULL);
            break;
        }
    }
}
