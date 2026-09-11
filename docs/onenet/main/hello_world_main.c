#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/task.h"
#include "wifi_manager.h"
#include "esp_log.h"
#include "nvs_flash.h"
#include "cJSON.h"
#include "onenet_mqtt.h"
#include "onenet_dm.h"

#define TAG "main"
static EventGroupHandle_t s_wifi_ev = NULL;
#define EV_WIFI_CONNECTED_BIT           (1<<0)
static void wifi_state_callback(WIFI_STATE state)
{
    if (state == WIFI_STATE_CONNECTED)
    {
        xEventGroupSetBits(s_wifi_ev,EV_WIFI_CONNECTED_BIT);
    }
}

void app_main(void)
{
    nvs_flash_init();
    onenet_dm_init();
    s_wifi_ev = xEventGroupCreate();
    wifi_manager_init(wifi_state_callback);
    wifi_manager_connect("www", "88888888");
    EventBits_t ev;
    //等待wifi连接成功
    while(1)
    {
        ev = xEventGroupWaitBits(s_wifi_ev,EV_WIFI_CONNECTED_BIT,pdTRUE,pdFALSE,pdMS_TO_TICKS(5000));
        if (ev & EV_WIFI_CONNECTED_BIT)
        {
            onenet_start();// 启动onenet任务
        }
    }
}
