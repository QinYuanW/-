# EMQX 配置方案（MVP）

> 版本：v1.2  日期：2026-09-16
> 对象：EMQX 5.8 开源版（Docker）
> 依赖：`API契约.md`（回调 URL）+ `db/schema.sql`（鉴权凭据）
> 配置方式：**Dashboard 图形化为主**（已挂 `emqx_data` 卷，重启不丢），HOCON 配置附后作参考。
> v1.1 变更：§1 Topic 约定表与 §4 下发示例的指令 payload 对齐代码实现——统一为 `{"request_id","command_type","payload"}` 三层结构（见 `CommandServiceImpl.buildDownMessage()` 与固件 `platform_mqtt.c handle_command()`）；原扁平格式 `{"property_key":"switch","value":true}` 已废弃，不再使用。
> v1.2 变更（F2）：新增 §5 **MQTT over TLS（8883）**——自签证书（带 SAN）替换 EMQX 无 SAN 默认证书，`certs/` 目录随仓库版本化、走 bind mount；§2.3 补充拒绝响应必须声明 `Content-Type: application/json`（BUG-29）。

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

> ⚠️ **拒绝响应必须带 `Content-Type: application/json`**（BUG-29）：`{"result":"deny"}` 若以 `text/plain` 返回，EMQX 5.x 判为 `unsupported_content_type` → `parse_http_response_failed`，靠 fail-closed 才拒绝，且每次拒绝刷 ERROR 日志。后端 `AuthController` 已显式声明。
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

## 5. MQTT over TLS（8883）— F2

### 5.1 现状（零 Dashboard 改动）

EMQX 5.8 自带 `ssl:default` listener，已绑定 `0.0.0.0:8883`，证书路径固定指向 `${EMQX_ETC_DIR}/certs/{cert,key,cacert}.pem`。因此**只需替换这三个文件 + 重启容器**，无需动任何 listener 配置：

| 参数 | 值 | 说明 |
| --- | --- | --- |
| listener | `ssl:default` | 类型 `ssl`，`enable_authn = true` |
| bind | `0.0.0.0:8883` | compose 已映射 `8883:8883` |
| verify | `verify_none` | **单向 TLS**：客户端校验服务器，服务器不校验客户端证书 |
| certfile / keyfile / cacertfile | `${EMQX_ETC_DIR}/certs/*.pem` | 由 compose 的 `./certs:/opt/emqx/etc/certs:ro` 提供 |

### 5.2 为什么必须换证书

EMQX 自带证书 `CN=Server` 且**无 SAN**。现代 TLS 栈（ESP32 esp-tls / 浏览器 / paho）**只认 SAN、不认 CN**，设备用 `localhost`/局域网 IP 连接 8883 必然校验失败。F2 用一张带 SAN 的自签证书替换之。

### 5.3 证书生成与接线

```bash
# 生成（在 EMQX 容器内跑，镜像自带 openssl 3.x；SAN_IP 填宿主局域网 IP）
docker exec -e SAN_IP=10.254.217.62 emqx sh /opt/emqx/etc/certs/gen-certs.sh
docker restart emqx
```

- 生成脚本与说明见仓库 `certs/`（`gen-certs.sh` / `README.md`）；证书/私钥为构建产物，**不入库**（`.gitignore` 排除 `*.pem / *.key / *.csr / *.srl / san.cnf`）。
- **换证书 = 替换文件 + 重启**，不依赖 `docker cp`（`docker cp` 在容器重建后会丢，TLS 会静默退回无 SAN 的默认证书 → 排查起来很隐蔽）。

### 5.4 端到端验证（2026-09-16 实测 · paho-mqtt）

| 用例 | 端口 | 证书校验 | 密钥 | 结果 |
| --- | :---: | :---: | :---: | --- |
| A | 8883 | ✅ ca_certs | 正确 | `CONNACK 0 Success` —— **TLS 握手 + 认证回调全通** |
| B | 8883 | ✅ ca_certs | 错误 | `CONNACK 135 Not authorized` —— 认证在 TLS 之上仍生效 |
| C | 1883 | 明文 | 正确 | `CONNACK 0 Success`（对照组） |
| D | 8883 | ❌ 不校验 | 正确 | `CONNACK 0 Success` —— 加密但不校验对端，仅演示降级风险 |

> 服务器实际证书：`TLSv1.3`、`CN=localhost`、issuer `YunPingTai-Test-CA`、`SAN = DNS:localhost, IP:127.0.0.1, IP:10.254.217.62`、有效期 10 年。
> ⚠️ 容器重建（`docker compose down/up`）已验证：因证书走 bind mount、认证器配置持久在 `emqx_data` 卷，重建后 4 组用例结果完全复现。

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
