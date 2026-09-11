# ESP32 设备端固件（对接自研 IoT PaaS 平台）

> 基于 OneNET 官方示例改造：把连接目标从 OneNET 换成自研平台（EMQX + SpringBoot）。
> 保留硬件逻辑（PWM LED + WS2812），替换协议层（认证 / 主题 / 数据格式）。

## 功能

- WiFi 连接（`wifi_manager`）
- MQTT 连接自研平台 EMQX（`platform_mqtt`），凭据 = `device_key / device_secret`
- 周期上报属性：`temperature` / `humidity` / `switch` / `brightness` → `up/{device_key}/property`
- 接收下行指令：`down/{device_key}/command` → 控制 LED → 回报 ACK 到 `up/{device_key}/ack`

## 接入协议（与后端契约对齐）

| 方向 | 主题 | 报文 |
|------|------|------|
| 属性上报 | `up/{device_key}/property` | `{"temperature":26.5,"humidity":60,"switch":true}` |
| 下行指令 | `down/{device_key}/command` | `{"request_id":"...","command_type":1,"payload":{"switch":true}}` |
| 指令回报 | `up/{device_key}/ack` | `{"request_id":"...","success":true}` |

> 上报报文由 EMQX 规则/桥接转发到 SpringBoot `/api/v1/telemetry`（自动补 `device_key` + `ts`）。

## 配置（烧录前必改）

在 `main/iot/platform_mqtt.h` 修改：

```c
#define IOT_BROKER_URI      "mqtt://<EMQX局域网IP>:1883"
#define IOT_DEVICE_KEY      "dev_demo_001"
#define IOT_DEVICE_SECRET   "demo_secret_123"
```

在 `main/hello_world_main.c` 修改 WiFi 账号：

```c
wifi_manager_connect("<SSID>", "<PASSWORD>");
```

> 设备凭据需先在 `iot_platform.device` 表注册（`db/seed.sql` 已预置 `dev_demo_001`）。

## 硬件

| 外设 | 引脚 | 属性 |
|------|------|------|
| PWM LED | GPIO15 | `switch`（开关）/ `brightness`（0-100） |
| WS2812 灯带 | GPIO18 | `rgb`（`{r,g,b}`） |

## 编译烧录

```bash
idf.py set-target esp32c3   # 按实际芯片
idf.py build
idf.py -p COMx flash monitor
```

> 本机未装 ESP-IDF，需在装有 ESP-IDF 的机器上编译。
