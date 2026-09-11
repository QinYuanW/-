idf_component_register(SRCS "device_dm.c" "led_ws2812.c" "iot/wifi_manager.c" "iot/platform_mqtt.c" "hello_world_main.c"
                    PRIV_REQUIRES spi_flash esp_wifi esp_netif esp_event nvs_flash mqtt json driver
                    INCLUDE_DIRS "." "iot")
