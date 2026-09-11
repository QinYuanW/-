#include "onenet_dm.h"
#include "led_ws2812.h"
#include "driver/gpio.h"
#include "driver/ledc.h"
#include <string.h>


static ws2812_strip_handle_t ws2812_handle = NULL;
static int led_brightness = 0;
static int led_status = 0;

static int ws2812_led_red = 0;
static int ws2812_led_green = 0;
static int ws2812_led_blue = 0;

/**
 * @brief 初始化物模型数据
 */
void onenet_dm_init(void)
{
    ws2812_init(GPIO_NUM_18, 3, &ws2812_handle); // 初始化ws2812条灯
    // LED定时器初始化
    ledc_timer_config_t timer_config = {
        .clk_cfg = LEDC_AUTO_CLK,             // 自动选择时钟源
        .duty_resolution = LEDC_TIMER_12_BIT, // 12位分辨率
        .freq_hz = 5000,                    // 500kHz
        .timer_num = LEDC_TIMER_0,            // 使用定时器0
    };
    ledc_timer_config(&timer_config);

    // 初始化PWM通道
    ledc_channel_config_t channel_config = {
        .channel = LEDC_CHANNEL_0, // 使用通道0
        .duty = 0,                 // 初始占空比为0
        .gpio_num = GPIO_NUM_15,   // 使用GPIO15
        .timer_sel = LEDC_TIMER_0, // 使用定时器0
    };
    ledc_channel_config(&channel_config);

    ledc_fade_func_install(0); // 安装渐变函数
}

/**
 * @brief 处理物模型数据
 * @param property_js 物模型数据的JSON对象
 */
void onenet_property_handle(cJSON *property_js)
{
    /*
    {
    "id": "123",            //平台下发的数据包id
    "version": "1.0",       //版本号，固定1.0
    "params": {             //上报的params参数，里面包含物模型数据，参见OneNET自己创建的产品
        "Brightness":50,    //亮度50，整型(1-100)
        "LightSwitch":true, //开关，bool型
        "RGBColor":         //RGB颜色，平台上指出，这个RGBColor是个结构体，也就是一个对象非普通值
        {
            "Red":100,      //红色值
            "Green":100,    //绿色值
            "Blue":100,     //蓝色值
        }
    }
}
    */
    cJSON *params_js = cJSON_GetObjectItem(property_js, "params");
    if (params_js)
    {
        cJSON *nmae_js = params_js->child; // 获取params_js的第一个子项
        while (nmae_js)
        {
            if (strcmp(nmae_js->string, "Brightness") == 0) // 如果是亮度
            {
                led_brightness = cJSON_GetNumberValue(nmae_js);                         // 获取亮度值
                int duty = led_brightness * 4095 / 100;                                 // 将亮度值转换为PWM占空比
                ledc_set_duty_and_update(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0, duty, 0); // 设置PWM占空比
            }
            else if (strcmp(nmae_js->string, "LightSwitch") == 0) // 如果是开关
            {
                if (cJSON_IsTrue(nmae_js)) // 如果开关为true(开灯)
                {
                    led_status = 1;
                    led_brightness = 50;                                                    // 设置亮度50
                    int duty = led_brightness * 4095 / 100;                                 // 将亮度值转换为PWM占空比
                    ledc_set_duty_and_update(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0, duty, 0); // 设置PWM占空比
                }
                else // 如果开关为false(关灯)
                {
                    led_status = 0;
                    led_brightness = 0;                                                  // 设置亮度0
                    ledc_set_duty_and_update(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0, 0, 0); // 设置PWM占空比为0
                }
            }
            else if (strcmp(nmae_js->string, "RGBColor") == 0) // 如果是RGB颜色
            {
                ws2812_led_red = cJSON_GetNumberValue(cJSON_GetObjectItem(nmae_js, "Red"));     // 获取红色值
                ws2812_led_green = cJSON_GetNumberValue(cJSON_GetObjectItem(nmae_js, "Green")); // 获取绿色值
                ws2812_led_blue = cJSON_GetNumberValue(cJSON_GetObjectItem(nmae_js, "Blue"));   // 获取蓝色值
                for (int i = 0; i < 3; i++)                                                     // 遍历3个LED
                {
                    ws2812_write(ws2812_handle, i, ws2812_led_red, ws2812_led_green, ws2812_led_blue); // 设置LED颜色
                }
            }
            nmae_js = nmae_js->next; // 获取下一个子项
        }
    }
}

/**
 * @brief 上报物模型数据
 * @return 物模型数据的JSON对象
 */
cJSON *onenet_property_upload_dm(void)
{
    /*
    {
    "id": "123",         //平台下发的数据包id
    "version": "1.0",    //版本号，固定1.0
    "params": {          //上报的物模型数据，可以带多个
        "Brightness":{   //亮度值，整型
            "value":50   //值
        },
        "LightSwitch":{  //开关，布尔型
            "value":true //值
        },
        "RGBColor":{     //颜色，结构体，也就是对象非普通值
            "value":{    //值
                "Red":100,
                "Green":100,
                "Blue":100,
            }
        }
    }
}*/
    cJSON *root = cJSON_CreateObject();
    cJSON_AddStringToObject(root, "id", "123");
    cJSON_AddStringToObject(root, "version", "1.0");

    cJSON *params_js = cJSON_AddObjectToObject(root, "params");
    // 往params中填充灯开关值
    cJSON *light_js = cJSON_AddObjectToObject(params_js, "LightSwitch");
    cJSON_AddBoolToObject(light_js, "value", led_status);
    // 往params中填充灯亮度值
    cJSON *brightness_js = cJSON_AddObjectToObject(params_js, "Brightness");
    cJSON_AddNumberToObject(brightness_js, "value", led_brightness);
    // 往params中填充RGB值
    cJSON *color_js = cJSON_AddObjectToObject(params_js, "RGBColor");
    cJSON *color_value_js = cJSON_AddObjectToObject(color_js, "value");
    cJSON_AddNumberToObject(color_value_js, "Red", ws2812_led_red);
    cJSON_AddNumberToObject(color_value_js, "Green", ws2812_led_green);
    cJSON_AddNumberToObject(color_value_js, "Blue", ws2812_led_blue);
    return root;
}
