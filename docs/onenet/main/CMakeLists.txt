idf_component_register(SRCS "onenet_dm.c" "led_ws2812.c" "iot/onenet_token.c" "iot/wifi_manager.c" "iot/onenet_mqtt.c" "iot/onenet_ota.c" "hello_world_main.c"
                    PRIV_REQUIRES spi_flash esp_wifi esp_netif esp_event nvs_flash mqtt json driver app_update esp_http_client esp_https_ota
                    INCLUDE_DIRS "." "iot")
