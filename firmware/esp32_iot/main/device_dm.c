#include "device_dm.h"
#include "led_ws2812.h"
#include "driver/gpio.h"
#include "driver/ledc.h"
#include "esp_random.h"
#include "esp_log.h"
#include <string.h>
#include <stdlib.h>

#define TAG "device_dm"

static ws2812_strip_handle_t ws2812_handle = NULL;
static int led_brightness = 50;
static bool led_switch = false;
static int ws2812_led_red = 0;
static int ws2812_led_green = 0;
static int ws2812_led_blue = 0;

// 模拟传感器读数（真实设备替换为实际传感器）
static float read_temperature(void)
{
    return 25.0f + (esp_random() % 60) / 10.0f; // 25.0 ~ 30.9 ℃
}

static float read_humidity(void)
{
    return 50.0f + (esp_random() % 200) / 10.0f; // 50.0 ~ 69.9 %
}

static void apply_led(void)
{
    int duty = led_switch ? (led_brightness * 4095 / 100) : 0;
    ledc_set_duty_and_update(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0, duty, 0);
}

void device_dm_init(void)
{
    ws2812_init(GPIO_NUM_18, 3, &ws2812_handle); // WS2812 灯带（GPIO18）

    ledc_timer_config_t timer_config = {
        .clk_cfg = LEDC_AUTO_CLK,
        .duty_resolution = LEDC_TIMER_12_BIT,
        .freq_hz = 5000,
        .timer_num = LEDC_TIMER_0,
    };
    ledc_timer_config(&timer_config);

    ledc_channel_config_t channel_config = {
        .channel = LEDC_CHANNEL_0,
        .duty = 0,
        .gpio_num = GPIO_NUM_15, // PWM LED（GPIO15）
        .timer_sel = LEDC_TIMER_0,
    };
    ledc_channel_config(&channel_config);
    ledc_fade_func_install(0);
}

void device_property_handle(const char* key, cJSON* value_js)
{
    if (!key || !value_js) {
        return;
    }
    if (strcmp(key, "switch") == 0) {
        led_switch = cJSON_IsTrue(value_js);
        apply_led();
        ESP_LOGI(TAG, "switch -> %s", led_switch ? "ON" : "OFF");
    } else if (strcmp(key, "brightness") == 0) {
        int v = (int)cJSON_GetNumberValue(value_js);
        led_brightness = v < 0 ? 0 : (v > 100 ? 100 : v);
        apply_led();
        ESP_LOGI(TAG, "brightness -> %d", led_brightness);
    } else if (strcmp(key, "rgb") == 0) {
        cJSON* r = cJSON_GetObjectItem(value_js, "r");
        cJSON* g = cJSON_GetObjectItem(value_js, "g");
        cJSON* b = cJSON_GetObjectItem(value_js, "b");
        ws2812_led_red = (int)cJSON_GetNumberValue(r);
        ws2812_led_green = (int)cJSON_GetNumberValue(g);
        ws2812_led_blue = (int)cJSON_GetNumberValue(b);
        for (int i = 0; i < 3; i++) {
            ws2812_write(ws2812_handle, i, ws2812_led_red, ws2812_led_green, ws2812_led_blue);
        }
        ESP_LOGI(TAG, "rgb -> (%d,%d,%d)", ws2812_led_red, ws2812_led_green, ws2812_led_blue);
    } else {
        ESP_LOGW(TAG, "unknown property: %s", key);
    }
}

char* device_property_upload(void)
{
    cJSON* root = cJSON_CreateObject();
    cJSON_AddNumberToObject(root, "temperature", read_temperature());
    cJSON_AddNumberToObject(root, "humidity", read_humidity());
    cJSON_AddBoolToObject(root, "switch", led_switch);
    cJSON_AddNumberToObject(root, "brightness", led_brightness);
    char* data = cJSON_PrintUnformatted(root);
    cJSON_Delete(root);
    return data;
}
