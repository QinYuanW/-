# EMQX 配置方案（MVP）

> 版本：v1.1  日期：2026-09-05
> 对象：EMQX 5.8 开源版（Docker）
> 依赖：`API契约.md`（回调 URL）+ `db/schema.sql`（鉴权凭据）
> 配置方式：**Dashboard 图形化为主**（已挂 `emqx_data` 卷，重启不丢），HOCON 配置附后作参考。
> v1.1 变更：§1 Topic 约定表与 §4 下发示例的指令 payload 对齐代码实现——统一为 `{"request_id","command_type","payload"}` 三层结构（见 `CommandServiceImpl.buildDownMessage()` 与固件 `platform_mqtt.c handle_command()`）；原扁平格式 `{"property_key":"switch","value":true}` 已废弃，不再使用。

***

## 0. 前置：网络拓扑（最容易踩的坑）

EMQX 在 Docker 容器里、SpringBoot 在宿主机，**双向调用地址完全不同**：

| 方向    | 谁调谁               | 用什么地址                              | 为什么                                |
| ----- | ----------------- | ---------------------------------- | ---------------------------------- |
| 认证/上报 | EMQX → SpringBoot | `http://host.docker.internal:8080` | 容器里写 `localhost` 指的是容器自己，**不是宿主机** |
| 下发    | SpringBoot → EMQX | `http://localhost:18083`           | 宿主机访问容器映射端口                        |

> `host.docker.internal` 是 Docker Desktop（Windows/Mac）内置的宿主机别名，你装了 Docker Desktop 就自带，无需额外配置。

***

## 1. Topic 约定（先定协议）

| 方向   | Topic 模板                    | 示例                          | payload 内容                               |
| ---- | --------------------------- | --------------------------- | ---------------------------------------- |
| 属性上报 | `up/{device_key}/property`  | `up/dev_demo_001/property`  | `{"temperature":26.5,"humidity":60}`     |
| 事件上报 | `up/{device_key}/event`     | `up/dev_demo_001/event`     | `{"key":"high_temp_alarm",...}`          |
| 指令下发 | `down/{device_key}/command` | `down/dev_demo_001/command` | `{"request_id":"...","command_type":1,"payload":{"switch":true}}` |

> MVP 只做属性上报 + 指令下发，事件后置。

***

## 2. HTTP 认证器（设备鉴权）

### 2.1 Dashboard 配置路径

`Access Control（访问控制）→ Authentication（认证）→ Create（创建）→ 选择 Password-Based → 后端选 HTTP Server`

### 2.2 关键配置项

| 配置项     | 值                                              | 说明                            |
| ------- | ---------------------------------------------- | ----------------------------- |
| Method  | `POST`                                         | 与 `API契约.md` 接口 1 对齐          |
| URL     | `http://host.docker.internal:8080/api/v1/auth` | ⚠️ 必须用 host.docker.internal   |
| Headers | `Content-Type: application/json`               | <br />                        |
| Body 模板 | 见下                                             | 只发 clientid/username/password |
| 认证缓存    | 开启（TTL 60s）                                    | ⚠️ 防连接风暴；5.8 需实测入口（见 §2.3 备注） |

**Body 模板：**

```json
{
  "clientid": "${clientid}",
  "username": "${username}",
  "password": "${password}"
}
```

### 2.3 认证流程时序

```
设备 ──CONNECT──▶ EMQX ──POST /api/v1/auth──▶ SpringBoot
                                              │ 查 Redis auth:{device_key}
                                              │ 比对 password
          EMQX ◀──204(允许) / 200+{"result":"deny"}(拒绝)──┘
设备 ◀──CONNACK── EMQX
```

> 认证结果缓存 TTL 60s 意味着：同一设备 60s 内重连不重复打接口，直接命中缓存。这是需求文档 7.4「连接风暴」的第一道防线。
> ⚠️ EMQX 5.x 的认证结果缓存实为「External Resource Cache」（节点级开关，默认 1 分钟，Dashboard 认证页右上角），部分文档标注 5.9+ 才支持。项目锁定 5.8，装好后需实测该入口是否存在；若无，防连接风暴靠后端 Redis 凭据缓存（契约 §1）兜底。

***

## 3. Webhook 数据转发（设备上报）

EMQX 5.x 用「规则 + 桥接」两件套：规则 SQL 匹配消息 → 动作转发到 Webhook 桥接。

### 3.1 规则 SQL

`Integration（集成）→ Rules（规则）→ Create`

```sql
SELECT
  clientid AS device_key,
  payload  AS properties,
  timestamp AS ts
FROM "up/+/property"
```

> `clientid` 即 `device_key`（因为 MQTT clientId = device\_key，见鉴权约定）；`up/+/property` 只匹配属性上报，避免把 `up/{device_key}/event` 事件报文也推进遥测接口污染数据。

### 3.2 Webhook 桥接

`Integration（集成）→ Connectors（连接器）→ Create → Webhook`

| 配置项     | 值                                                   |
| ------- | --------------------------------------------------- |
| Method  | `POST`                                              |
| URL     | `http://host.docker.internal:8080/api/v1/telemetry` |
| Body 模板 | 见下                                                  |

**Body 模板：**

```json
{
  "device_key": "${device_key}",
  "ts": ${ts},
  "properties": ${properties}
}
```

> 与 `API契约.md` 接口 2 的请求体严格一致：device\_key（字符串）+ ts（毫秒时间戳）+ properties（属性对象）。

### 3.3 EMQX 5.8 实际配置要点（2026-09-04 实测）

EMQX 5.8 的「规则 + 桥接」是 **Connector / Action / Rule 三层**，API 字段有坑，实测要点：

1. **Connector（连接器）**：`POST /api/v5/connectors`，字段**平铺在根级别**（不是 `config` 嵌套），且**只放连接配置**（url / headers / connect_timeout / pool_size / enable）。传 body/method 会报 `unknown_fields`
2. **Action（动作）**：`POST /api/v5/actions`，`connector` 字段绑定连接器，**body / method / headers 模板放这里**（`parameters`）
3. **Rule（规则）**：`POST /api/v5/rules`，`actions: ["http:{action_name}"]` 引用 action

**两条转发链路（均已落地验证）：**

| 链路 | Connector | Action | Rule SQL |
|------|-----------|--------|----------|
| 属性上报 | `iot_telemetry_webhook` → `/api/v1/telemetry` | `fwd_telemetry`（body=`{"device_key":"${device_key}","ts":${ts},"properties":${properties}}`） | `SELECT clientid AS device_key, payload AS properties, timestamp AS ts FROM "up/+/property"` |
| 指令回报 | `iot_ack_webhook` → `/api/v1/commands/ack` | `fwd_ack`（body=`${payload}`） | `SELECT payload FROM "up/+/ack"` |

> 验证：停掉临时 `mqtt_bridge.py` 后，telemetry 持续入库（每分钟 48 条）+ ack 正常到达（`command.status 1→2`），全链路走 EMQX 规则引擎。

> **D1（Kafka 接入）**：EMQX Webhook 接口保持不变、仅作为**入口**；后端 `/api/v1/telemetry` 不再直接异步入库，而是以 `acks=all` 发布到 Kafka topic `iot.telemetry`（partition key = device_key），再由消费者组 `iot-telemetry-consumers`（并发 3、手动 ack）同步落库。`后端重启丢在途 → offset 续消费` 已验证通过（BUG-18）。边界：极端突发 + 后端宕机时，EMQX webhook `request_ttl=45s` 过期的积压会被丢弃（详见 BUG_LOG BUG-18）。

### 3.4 桥接配置要点（对应需求文档 7.2 风险）

- 开启**批量发送**（Batch）与**本地重试**，缓解 EMQX→Java 的 HTTP 调用压力；

- Java 侧异步接收 + 有界队列削峰（见接口契约 §2）；

- 关键上报设备侧用 `QoS=1` 保证至少一次投递。

***

## 4. 指令下发链路（EMQX REST API）

接口 5（控制下发）由 SpringBoot 调 EMQX REST API 推送指令。

| 项   | 值                                             |
| --- | --------------------------------------------- |
| URL | `POST http://localhost:18083/api/v5/publish`  |
| 认证  | Basic Auth（`admin` / `public`，Dashboard 初始账号） |

**请求：**

```json
{
  "topic": "down/dev_demo_001/command",
  "payload": "{\"request_id\":\"b8f4c2e1-9a3d-4f5e-8c7d-6a5b4c3d2e1f\",\"command_type\":1,\"payload\":{\"switch\":true}}",
  "qos": 1,
  "retain": false
}
```

> `payload` 是**字符串**（JSON 序列化后），不是对象。这是 EMQX REST API 的一个常见坑。

***

## 附 A：HOCON 配置参考（可选，进阶/版本管理）

若想用配置文件而非 Dashboard，在 `emqx.conf` 中追加：

```hocon
# 认证器
authentication = [
  {
    mechanism = password_based
    backend = http
    method = post
    url = "http://host.docker.internal:8080/api/v1/auth"
    headers = {"content-type" = "application/json"}
    body = {
      clientid = "${clientid}"
      username = "${username}"
      password = "${password}"
    }
  }
]
```

Webhook 桥接建议仍用 Dashboard 配置（桥接 + 规则联动，HOCON 冗长且易错）。

***

## 附 B：配置后的端到端验证清单

- [ ] MQTTX 用 `dev_demo_001` / `demo_secret_123` 连接 → 成功（认证器生效）

- [ ] MQTTX 用错误密码连接 → 被拒（403 生效）

- [ ] 设备发布到 `up/dev_demo_001/property` → SpringBoot 收到 Webhook（日志可见）

- [ ] SpringBoot 调 `/api/v5/publish` → MQTTX 订阅 `down/...` 收到指令

<br />
