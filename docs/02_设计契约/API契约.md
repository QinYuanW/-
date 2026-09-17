# SpringBoot 接口契约（MVP）

> 版本：v1.3  日期：2026-09-10
> 定位：只定「接口签名 + 报文示例」，不写实现逻辑。
> 依据：`db/schema.sql` v1.2（字段决定入参出参）+ 需求文档 §3.4「6 步数据流」。
> 服务端口：`8080`；统一前缀：`/api/v1`。
> v1.1 变更：新增 §3.4 设备物模型 TSL 查询、§5 控制台登录（JWT）——支撑控制台前端「物模型驱动」动态渲染。
> v1.2 变更：§3.1 设备列表新增 `keyword` 模糊搜索参数 + 在线优先排序（`online_status DESC, last_online_at DESC`）——支撑前端搜索框与分页。
> v1.3 变更：§0.3 引用注释更新——「JWT 业务接口拦截器已落地」，查阅/下发等业务接口现已**强制校验 JWT**，仅 EMQX 三条回调与登录接口放行；补明放行清单。
> v1.4 变更：**新增 §7 统计与告警（仪表盘）、§8 设备事件记录、§9 实时推送（WebSocket）**——补齐前端「物联概览」与设备详情 tab 的数据缺口；**新增附录 B「已实现接口索引」**，补录 v1.3 之后已落地但未入档的接口（3.3 列表增强、E5 产品与物模型、E1 设备影子），恢复「本文档 = 接口单一事实源」的地位。

---

## 0. 通用约定

### 0.1 统一响应格式（业务接口）

业务接口统一返回：

```json
{
  "code": 0,
  "message": "ok",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | int | 0 成功；非 0 失败 |
| message | string | 提示信息 |
| data | object | 业务数据（列表接口含分页） |

### 0.2 错误码约定

| code | 含义 |
|------|------|
| 0 | 成功 |
| 4001 | 参数错误 |
| 4004 | 设备不存在 |
| 4010 | 鉴权失败 |
| 5000 | 服务内部错误 |

### 0.3 鉴权说明（重要区别）

- **接口 1（设备鉴权回调）**：不走统一 code 格式，遵循 **EMQX 5.x 认证协议**——`204` = 通过；`200` + body `result` 字段（`allow`/`deny`）判定，**且拒绝时 `Content-Type` 必须为 `application/json`**（详见 §1，BUG-29）。⚠️ 这是 5.x 协议，与 4.x 的「200=通过 / 403=拒绝」不兼容。
- **接口 2（Webhook 上报）**：EMQX 内部调用，MVP 阶段不鉴权（内网直连）。
- **接口 3/4（查询/下发）**：控制台登录后携带 JWT（`Authorization: Bearer <token>`），**已强制校验**（`WebMvcConfig` 注册 `JwtInterceptor`，匹配 `/api/v1/**`）。无 token / token 非法或过期 → 返回 `401`；校验通过放行。
- **接口 5（控制台登录）**：`POST /api/v1/login` 校验用户名密码（BCrypt），签发 JWT（HS256，默认 2h 过期）。

**JWT 拦截器放行清单**（`WebMvcConfig` / `JwtInterceptor` 实测）：
- 放行：`POST /api/v1/login`（登录，无 token 可调）、`POST /api/v1/auth`（EMQX 鉴权回调）、`POST /api/v1/telemetry`（EMQX 上报回调）、`POST /api/v1/commands/ack`（EMQX 指令确认回调）。
- 拦截校验：其余全部 `/api/v1/**`（设备列表、TSL、遥测查询、指令下发、设备注册等）。
> 配错放行规则会让 EMQX 三条回调整条链路返回 401 —— 调整拦截器后须回归复测这四条链表。

### 0.4 设备标识约定

设备接入标识统一用 `device_key`（对应 `device` 表 `device_key` 字段），MQTT 侧 `clientId = username = device_key`，`password = device_secret`。

---

## 1. 设备鉴权回调（EMQX auth_http）

设备连接 EMQX 时，EMQX 回调本接口校验凭据。

| 项 | 值 |
|----|----|
| 方法 | `POST` |
| 路径 | `/api/v1/auth` |
| 调用方 | EMQX（auth_http 认证器） |

**请求（EMQX 发来）：**

```json
{
  "clientid": "dev_demo_001",
  "username": "dev_demo_001",
  "password": "demo_secret_123"
}
```

**响应（EMQX 5.x 认证协议：看状态码 + body 的 result 字段）：**

| 场景 | 状态码 | Body |
|------|:---:|------|
| 认证通过 | `204` | 无（推荐，最简） |
| 认证通过（等价写法） | `200` | `{"result": "allow"}`（content-type: application/json） |
| 认证拒绝 | `200` | `{"result": "deny"}`（**content-type 必须 `application/json`**） |
| 服务异常（交由认证链） | `4xx/5xx` | 忽略 body |

> ⚠️ **拒绝响应的 `Content-Type` 必须是 `application/json`**（BUG-29）。EMQX 5.x 会校验响应头：若把 `{"result":"deny"}` 以 `text/plain` 返回（Spring 对 String 返回值的默认类型），EMQX 记 `unsupported_content_type → parse_http_response_failed`，只能靠 fail-closed 兜底拒绝，且每次拒绝刷 ERROR 日志；语义上更糟——一旦解析失败被判为 `ignore`（继续认证链），就可能误放行。

> ⚠️ EMQX 4.x 用「200=允许 / 403=拒绝」，5.x 已改为 body 的 `result` 字段 + 204 语义，两者不兼容。若返回 200 但 body 缺 `result` 字段，EMQX 判为 `ignore`（继续认证链，单认证器链尾默认拒绝），合法设备会被拒之门外。

**业务逻辑（D3 实现，F3 起 secret 以密文入缓存）：**
1. 取 `username`（即 `device_key`）查 Redis `auth:v4:{device_key}` → `secret密文|启用位|product_key`（缓存未命中则查 `device` 表回填）。
2. 对 secret **AES-256-GCM 解密**（历史明文直接兼容，认证通过后懒迁移转密）后比对 `password`，且设备/产品均启用 → 通过返回 `204`，否则返回 `200` + `{"result":"deny"}`（`Content-Type: application/json`）。
3. EMQX 侧开启认证结果缓存，防连接风暴（见 EMQX 配置 §2.2）。

---

## 2. 设备上报接收（EMQX → Webhook → Kafka → 消费者批量幂等入库）

设备按物模型格式上报属性，**EMQX 规则引擎**（`up/+/property`）经 **webhook** 转发给本接口；后端收到后写入 Kafka，由消费者组异步批量落库（D1 起替代原 Webhook 直推直落，耐久性下沉到 Kafka）。

| 项 | 值 |
|----|----|
| 方法 | `POST` |
| 路径 | `/api/v1/telemetry` |
| 调用方 | EMQX（规则引擎 webhook action `fwd_telemetry`） |

**请求（EMQX 规则转发后的报文）：**

```json
{
  "device_key": "dev_demo_001",
  "ts": 1724997600000,
  "properties": {
    "temperature": 26.5,
    "humidity": 60.0
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| device_key | string | 设备标识（规则取 MQTT `clientid` → 对应 device.device_key） |
| ts | long | 上报时间，毫秒时间戳（规则取 EMQX `timestamp`，缺省用服务器时间） |
| properties | object | 物模型属性包，key 对应 telemetry.property_key |

**响应：**

```json
{ "code": 0, "message": "ok" }
```

> **立即返回 200**：Controller 只做「反序列化 → 异步 `kafkaTemplate.send()`（非阻塞，key=device_key）」，不再同步等待任何 DB 操作（BUG-18：由同步 `.get()` 改为异步，webhook 毫秒级返回，避免 EMQX 侧积压触发 `request_ttl` 过期丢消息；发送失败仅计数不谎报）。耐久性由 Kafka producer `acks=all` 保证。

**业务逻辑（D1 链路，分两段）：**

*生产者段（`TelemetryController`）*
1. 反序列化为 `TelemetryRequest`，异步写入 Kafka topic `iot.telemetry`（key=device_key），`whenComplete` 回调失败计数；webhook 立即返回 200。

*消费者段（`TelemetryKafkaListener` + `TelemetryServiceImpl`，组 `iot-telemetry-consumers`，手动 ack）*
2. `device_key` → `device_id` 映射（走 Redis 缓存 `did:`，未命中查库回填）。
3. 同一上报报文的多属性拼成**一条 multi-values INSERT** 写 `device_telemetry(device_id, property_key, value, value_type, ts)`；`ts` 由毫秒时间戳转 `DATETIME(3)`。**幂等去重**：`ON DUPLICATE KEY UPDATE id=id`，唯一键 `uk_device_prop_val_ts(device_id, property_key, value, ts)`——真重放（offset 重放/webhook 同内容重发，value+ts 全同）被吞，并发不同上报（value 不同）不误删（BUG-19/BUG-20）。落库成功才 `acknowledge()`（提交 offset）。
4. 在线状态：Redis `SETNX 5s` 时间窗聚合刷新 `online_status=1` 与 `last_online_at`，缓和高并发 hot-row 锁竞争（BUG-17）。

---

## 3. 设备查询

### 3.1 设备列表

| 项 | 值 |
|----|----|
| 方法 | `GET` |
| 路径 | `/api/v1/devices?page=1&size=20&keyword=` |

| 参数 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| page | int | 否 | 页码（缺省 1） |
| size | int | 否 | 每页条数（缺省 20） |
| keyword | string | 否 | 按 `device_key` / `device_name` 模糊搜索（LIKE） |

> 排序：**在线设备优先**（`online_status DESC, last_online_at DESC`），在线组内按最近活跃时间倒序，离线组同理。

**响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "total": 1,
    "items": [
      {
        "id": 1,
        "device_key": "dev_demo_001",
        "device_name": "客厅温湿度计",
        "product_key": "p_demo_001",
        "online_status": 1,
        "last_online_at": "2026-08-30 15:00:00.000"
      }
    ]
  }
}
```

### 3.2 设备最新数据

| 项 | 值 |
|----|----|
| 方法 | `GET` |
| 路径 | `/api/v1/devices/{device_key}/telemetry/latest` |

**响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "temperature": 26.5,
    "humidity": 60.0,
    "switch": false
  }
}
```

> 每个属性取 `device_telemetry` 中该 `property_key` 的最新一条（按 device_id+property_key 前缀过滤，由 D2 幂等唯一键 `uk_device_prop_val_ts` 覆盖；若分组取最新出现退化，见数据库设计说明，补回 `(device_id,property_key,ts)` 普通索引）。

### 3.3 历史遥测区间查询

| 项 | 值 |
|----|----|
| 方法 | `GET` |
| 路径 | `/api/v1/devices/{device_key}/telemetry?property=temperature&start=1724990000000&end=1725000000000` |

**响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      { "ts": 1724997600000, "value": 26.5 },
      { "ts": 1724997660000, "value": 26.8 }
    ]
  }
}
```

| 参数 | 类型 | 说明 |
|------|------|------|
| property | string | 物模型属性标识（必填） |
| start / end | long | 毫秒时间戳起止（缺省 end=now，start=now-1h） |

> 按 device_id+property_key 前缀过滤（`uk_device_prop_val_ts` 覆盖；ts 范围裁剪依赖 value 前置，必要时补 `(device_id,property_key,ts)` 普通索引），用于前端画温度曲线（MVP 阶段 3 完成标志）。

### 3.4 设备物模型（TSL）查询

| 项 | 值 |
|----|----|
| 方法 | `GET` |
| 路径 | `/api/v1/devices/{device_key}/tsl` |

**响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "properties": [
      { "key": "temperature", "name": "温度", "type": "float", "unit": "℃", "access": "readonly" },
      { "key": "switch", "name": "开关", "type": "bool", "access": "readwrite" }
    ],
    "services": [],
    "events": [
      { "key": "high_temp_alarm", "name": "高温告警" }
    ]
  }
}
```

> 返回设备所属产品的物模型（`product.tsl` JSON 原样解析），前端据此**动态渲染**数据卡片与控件：`properties[readonly]`→数据卡片、`properties[readwrite]`→卡片+控件（bool→开关 / int·float→滑块 / string→输入）、`services`→服务按钮。设备不存在或未配置物模型返回 `4004`。

---

## 4. 控制下发

控制台/API 下发指令，Java 落库 + 调 EMQX REST API 推送给设备。

| 项 | 值 |
|----|----|
| 方法 | `POST` |
| 路径 | `/api/v1/devices/{device_key}/commands` |

**请求：**

```json
{
  "command_type": 1,
  "payload": {
    "switch": true
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| command_type | int | 1 属性下发；2 服务调用（对应 command.command_type） |
| payload | object | 指令内容（对应 command.payload，JSON）；属性下发为**扁平属性映射** `{属性key: 值}`，与遥测上报格式一致，后端原样透传至设备端 `down/{device_key}/command` |

**响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "request_id": "b8f4c2e1-9a3d-4f5e-8c7d-6a5b4c3d2e1f",
    "status": 0
  }
}
```

| 字段 | 说明 |
|------|------|
| request_id | 幂等键（UUID，对应 command.request_id，防重复下发） |
| status | 初始状态 0 待下发（状态机见 command 表） |

**业务逻辑（D7 实现）：**
1. 生成 `request_id`，落 `command` 表（status=0）。
2. 调 EMQX REST API `POST /api/v5/publish` 下发（topic 按设备约定，payload 为 JSON）。
3. 更新 `command.status=1`、`sent_at`；设备回报后更新 `status=2`、`ack_at`。

### 4.1 指令状态查询

| 项 | 值 |
|----|----|
| 方法 | `GET` |
| 路径 | `/api/v1/commands/{request_id}` |

**响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "request_id": "b8f4c2e1-9a3d-4f5e-8c7d-6a5b4c3d2e1f",
    "status": 2,
    "sent_at": "2026-08-30 15:00:00.000",
    "ack_at": "2026-08-30 15:00:01.500"
  }
}
```

> 控制台下发后轮询此接口，得知设备是否执行（status：2 已执行 / 3 失败 / 4 超时）。

---

## 5. 控制台登录（JWT）

控制台前端登录，校验通过后签发 JWT，后续查询/下发请求携带 `Authorization: Bearer <token>`。

| 项 | 值 |
|----|----|
| 方法 | `POST` |
| 路径 | `/api/v1/login` |

**请求：**

```json
{
  "username": "admin",
  "password": "admin123"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| username | string | 用户名（必填，对应 user.username） |
| password | string | 密码（必填，BCrypt 哈希比对） |

**响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiIsInVpZCI6MSwicm9sZSI6MSwiZXhwIjoxNzI1MDAwMDAwfQ...",
    "username": "admin",
    "role": 1
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| token | string | JWT（HS256，默认 2h 过期；请求头 `Authorization: Bearer <token>`） |
| role | int | 用户角色（1 管理员） |

> 用户名不存在 / 密码错误 / 账号停用统一返回 `4010`（不区分提示，防枚举）。密码存储用 BCrypt（`spring-security-crypto`），JWT 签发用 `jjwt` 0.12.x。

---

## 6. 设备注册

设备接入前先注册，获取 `device_key` + `device_secret`（MQTT 连接凭据）。支持单个与批量。

### 6.1 单个注册

| 项 | 值 |
|----|----|
| 方法 | `POST` |
| 路径 | `/api/v1/devices` |

**请求：**

```json
{
  "product_key": "p_demo_001",
  "device_key": "dev_kitchen_001",
  "device_name": "厨房温湿度计"
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| product_key | string | 是 | 所属产品（须存在，否则 `4004`） |
| device_key | string | 否 | 自定义标识；缺省自动生成 `dev_{prefix}_{hex}` |
| device_name | string | 否 | 显示名；缺省取 device_key；`product_key+device_name` 唯一 |
| name_prefix | string | 否 | 自动生成 device_key 时的前缀（缺省 `dev`） |

**响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "device_key": "dev_kitchen_001",
    "device_name": "厨房温湿度计",
    "device_secret": "a7e6e00afea86b2b2551ff5e91d4ded8"
  }
}
```

> `device_secret` 为 16 字节随机 hex（32 字符），**仅注册时返回一次**，后续不提供查询。`device_key` 重复返回 `4001`。
> **F3 起数据库只存 AES-256-GCM 密文**（`v1:` 前缀，主密钥 `app.device-secret.master-key`，生产经环境变量 `IOT_DEVICE_SECRET_KEY` 注入）；旧明文数据在首次认证通过后自动转密（懒迁移，零停机）。

### 6.2 批量注册

| 项 | 值 |
|----|----|
| 方法 | `POST` |
| 路径 | `/api/v1/devices/batch` |

**请求：**

```json
{
  "product_key": "p_demo_001",
  "name_prefix": "storm",
  "count": 100
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| product_key | string | 是 | 所属产品 |
| name_prefix | string | 否 | 设备名前缀（缺省 `dev`），生成 `{prefix}_1..{prefix}_N` |
| count | int | 否 | 数量 1~1000（缺省 1） |

**响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "total": 100,
    "items": [
      { "device_key": "dev_storm_20260905125818_1", "device_name": "storm_1", "device_secret": "..." }
    ]
  }
}
```

> 批量生成的 `device_key` 格式：`dev_{prefix}_{yyyyMMddHHmmss}_{i}`（时间戳保证跨批次不冲突）。用于压测/批量接入场景。

---

## 附：接口 ↔ 表字段对应关系（数据合同的推导）

| 接口 | 入参/出参字段 | 对应表字段 |
|------|--------------|-----------|
| 鉴权回调 | username / password | device.device_key / device.device_secret |
| 上报接收 | properties 的 key/value | device_telemetry.property_key / value |
| 上报接收 | ts（毫秒） | device_telemetry.ts（转 DATETIME(3)） |
| 设备列表 | device_key/name/product_key/online_status | device 表同名字段 |
| 物模型查询 | data（properties/services/events） | product.tsl（JSON） |
| 控制台登录 | username / password | user.username / user.password_hash |
| 控制台登录 | token | JWT（HS256，含 uid/role） |
| 控制下发 | request_id / status | command.request_id / command.status |
| 控制下发 | payload | command.payload（JSON） |
| 设备注册 | product_key / device_key / device_name | device.product_key / device_key / device_name |
| 设备注册 | device_secret | device.device_secret（16 字节随机 hex，F3 起库中存 AES-256-GCM 密文，明文仅注册响应一次） |

> 这张表就是「表结构 → 接口契约」的推导链，也是面试能讲的点：**先签数据合同，接口入参出参自动确定，不会出现前后端字段对不上的返工**。

---

## 7. 统计与告警（控制台仪表盘「物联概览」）

> **背景**：前端 `/dashboard` 已按「物联概览」版式重做（四统计卡 + 消息趋势 + 告警类型 + 告警消息表），当前数据**全部走 `src/mock/dashboard.js` 占位**（2026-09-13 按汤园决策「前端先用假数据看效果」）。本章接口落地后，前端**只需把那一行 mock import 换成 API 调用，组件代码零改动**。
>
> ⚠️ **依赖状态分两档**：
> - **无前置依赖**：7.1 的 `product` / `device` / `message` 三段 + 7.2 全部 —— 现有 `product` / `device` / `device_telemetry` 表即可支撑。
> - **依赖 E3**：7.1 的 `alarm` 段 + 7.3 —— 告警数据链路尚不存在，**E3 落地前恒返回 0 / 空列表**（前端按空态渲染，非错误）。

### 7.1 概览统计

| 项 | 值 |
|----|----|
| 方法 | `GET` |
| 路径 | `/api/v1/stats/overview` |
| 调用方 | 控制台仪表盘（进页面拉一次，可手动刷新） |

**响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "product": { "total": 33, "enabled": 15, "disabled": 18 },
    "device": { "total": 1014, "online": 1, "offline": 1013, "enabled": 12, "disabled": 11,
                "unavailable": 411, "blocked_by_product": 400 },
    "message": { "total": 12079200, "month": 592800, "today": 39900 },
    "alarm": { "total": 0, "month": 0, "today": 0 }
  }
}
```

| 字段 | 类型 | 数据口径（对应表字段） |
|------|------|----------------------|
| product.total | int | `product` 表 `deleted=0` 计数 |
| product.enabled / disabled | int | 同上再加 `status=1` / `status=0` |
| device.total | int | `device` 表 `deleted=0` 计数 |
| device.online / offline | int | `device.online_status=1` / `=0`（`deleted=0`） |
| device.enabled / disabled | int | `device.status=1` / `=0` —— **仅设备自身**配置态（保留原义，向后兼容） |
| device.unavailable | int | **真实不可用** = 自身停用 + 被产品停用牵连 = `total -` 可用数（见下） |
| device.blocked_by_product | int | 自身**启用**、但所属产品已停用的设备数（`device JOIN product`） |

> ⚠️ **`unavailable` 与 `disabled` 的区别（2026-09-15 新增，勿混用）**
>
> 设备可用性是**两级配置态的 AND**：`可用 = device.status=1 AND product.status=1`，与认证/遥测的判定同构。
> 只看 `device.disabled` 会漏掉「产品被停用 → 整条产品线设备全废」这半边：那批设备自身 `status` 仍是 1，
> 既不计入 `disabled`、也不在 `offline` 里，**在仪表盘上完全不可见**（已实测复现：3 台设备被产品牵连集体不可工作，
> 而 `device.disabled` 一个数都没变）。
>
> 三个字段满足恒等式：**`unavailable = disabled + blocked_by_product`**（且三者对 `deleted=0` 的设备构成完备互斥划分）。
>
> 控制台仪表盘设备卡片展示的是 **`unavailable`**（label「不可用」），悬停展开拆解为
> 「设备自身停用 N 台 · 因产品停用牵连 M 台」—— 保留「是谁拖的」这一处置线索
> （前者去设备页启用，后者去产品页启用）。

| message.total | int | `device_telemetry` 全表计数 |
| message.month | int | `ts >= 当月 1 号 00:00:00` 计数 |
| message.today | int | `ts >= 今日 00:00:00` 计数 |
| alarm.total / month / today | int | **E3 前恒为 0**；口径同 message，取自告警表 |

> ⚠️ **`message.total` 是本接口唯一的性能雷点**，实现三选一（按投入排序）：
> ① **Redis 计数器**（写入侧 `INCR`，读侧 O(1)）—— **推荐**；代价是重放/回滚时计数会漂移，需提供对账脚本；
> ② `information_schema.TABLES.TABLE_ROWS` 近似值 —— 零成本，千万行级误差通常 <10%，仪表盘场景可接受；
> ③ 直接 `COUNT(*)` + 服务端 5s 缓存 —— 最简单，但大表首次调用仍会打到秒级。
>
> `message.month` / `today` 带 `ts` 范围条件，**可走 `device_telemetry` 的按月分区裁剪**，代价可控。`product` / `device` 均为小表，直接 count 无压力。
>
> 📌 **面试点**：这个接口正是「为什么需要 TDengine」的现场证据——`COUNT(*)` 与 7.2 的按月聚合在 MySQL 上都是全表/大范围扫描，迁移前后可直接对比。

### 7.2 消息趋势（按月）

| 项 | 值 |
|----|----|
| 方法 | `GET` |
| 路径 | `/api/v1/stats/message-trend?months=12` |
| 调用方 | 控制台仪表盘「设备消息」折线图 |

| 参数 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| months | int | 否 | 回溯月数（缺省 12，最大 24） |

**响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "items": [
      { "month": "2026-08", "message": 620000, "alarm": 0 },
      { "month": "2026-09", "message": 1514209, "alarm": 0 }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| month | string | `yyyy-MM`（**升序**返回，前端直接喂 x 轴） |
| message | int | 该月 `device_telemetry` 计数 |
| alarm | int | 该月告警数（**E3 前恒为 0**，前端画虚线系列） |

> **实现**：`SELECT DATE_FORMAT(ts,'%Y-%m') AS m, COUNT(*) AS c FROM device_telemetry WHERE ts >= ? GROUP BY m ORDER BY m`。
> ⚠️ **只返回「有数据的月份」**，缺失月由前端补 0 —— 这样后端不必构造空月份列表，接口更dumb。
> 📌 **真实数据分布预警**：`device_telemetry` 的分区从 `2026-08` 起，实际数据集中在 `2026-09`。铺 12 个月会出现「左边 11 个月全 0、右边一根尖峰」的难看曲线。**演示前需决定**：造历史数据 / 把 `months` 收到有数据的区间 / 前端只渲染有数据的月份。

### 7.3 告警消息列表

| 项 | 值 |
|----|----|
| 方法 | `GET` |
| 路径 | `/api/v1/alerts?page=1&size=10&level=&status=` |
| 调用方 | 控制台仪表盘「告警消息」表 + 「告警类型」分布 |

| 参数 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| page / size | int | 否 | 分页（缺省 1 / 10） |
| level | string | 否 | 级别筛选：`严重` / `一般` / `提示` |
| status | string | 否 | 状态筛选：`未处理` / `已处理` |

**响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "total": 1,
    "types": [
      { "name": "规则告警", "count": 2 },
      { "name": "系统告警", "count": 1 }
    ],
    "items": [
      {
        "id": 1,
        "alarm_type": "规则告警",
        "level": "严重",
        "rule_name": "客厅温度超限告警",
        "product_key": "p_demo_001",
        "device_key": "dev_demo_001",
        "status": "已处理",
        "alarm_at": "2026-09-12 14:23:18"
      }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| total | int | 满足筛选条件的总数（分页用） |
| types | array | **告警类型分布**，供「告警类型」面板画饼图/条形；不受分页影响，受筛选条件影响 |
| items[].alarm_type | string | 告警类型（规则告警 / 系统告警） |
| items[].level | string | 级别（严重 / 一般 / 提示） |
| items[].rule_name | string | 触发规则名称（`系统告警` 可为空） |
| items[].product_key / device_key | string | 关联产品与设备标识（用于「详情」跳转 `/devices/{device_key}`） |
| items[].status | string | 处理状态（未处理 / 已处理） |
| items[].alarm_at | string | 告警时间 `yyyy-MM-dd HH:mm:ss` |

> ⚠️ **依赖 E3**：本接口需要一张告警表（建议 `device_alarm`：`id / device_id / alarm_type / level / rule_id / rule_name / status / content / created_at / handled_at`），由 E3 规则引擎命中阈值时写入。
> **不建议**复用 `device_log.log_type=5` —— 告警有独立的状态流转（未处理→已处理）与结构化字段，塞进 1024 字符的 `content` 会让筛选和统计都变成字符串解析。
> **详情页跳转**：前端「详情」按钮直接跳 `/devices/{device_key}`，无需额外接口。

---

## 8. 设备事件记录（设备详情「事件记录」tab）

> **背景**：`DeviceDetail.vue` 的「事件记录」tab 目前是占位空态。物模型里已有 `events` 定义（如 `high_temp_alarm` 高温告警），但**设备事件的上报 → 存储 → 查询整条链路都不存在**：MQTT topic `up/{device_key}/event` 已约定，`device_event` 表未建，EMQX 规则未配。

### 8.1 事件上报接收（EMQX → Webhook）

| 项 | 值 |
|----|----|
| 方法 | `POST` |
| 路径 | `/api/v1/events` |
| 调用方 | EMQX 规则引擎（新增规则，匹配 `up/+/event`） |

**请求：**

```json
{
  "device_key": "dev_demo_001",
  "ts": 1724997600000,
  "payload": {
    "event_key": "high_temp_alarm",
    "params": { "temperature": 58.2 }
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| device_key | string | 设备标识（规则取 MQTT `clientid`） |
| ts | long | 事件发生时间（毫秒时间戳，规则取 `timestamp`） |
| payload | object | **设备上报的事件原文**（EMQX 整体透传） |

> ⚠️ **2026-09-14 变更**：初版平铺 `event_key` / `params`，实测 EMQX body 模板在事件不带 `params` 时会把 `${params}` 渲染成字面 `undefined` 导致 JSON 解析失败；且规则 SQL 的 `payload` 字段存在「对象 / 转义字符串」两种渲染形态。故改为 **`payload` 整体透传**，`event_key` / `params` 由后端从 `payload` 解析（兼容两种形态）。EMQX 动作 body 为 `{"device_key":"${device_key}","ts":${ts},"payload":${payload}}`。

**响应：** `{ "code": 0, "message": "ok" }`

> 建议与遥测上报同构：**立即返回 200，异步落库**（D1 之后可复用 Kafka，topic `iot.event`）。需同步加进 JWT 拦截器放行清单（EMQX 无法携带 JWT）。

### 8.2 事件查询

> ✅ **已实现（2026-09-14）**：`device_event` 表 + EMQX 规则 `forward_event_rule`（`up/+/event`）+ `GET /devices/{key}/events` 全部落地。实测：MQTT 客户端（三元组认证）发布事件 → 落库 → `event_name` 按产品 TSL 回填（如 `high_temp_alarm` → 「高温告警」）。

| 项 | 值 |
|----|----|
| 方法 | `GET` |
| 路径 | `/api/v1/devices/{device_key}/events?page=1&size=20&event_key=` |

| 参数 | 类型 | 必填 | 说明 |
|------|------|:---:|------|
| page / size | int | 否 | 分页（缺省 1 / 20） |
| event_key | string | 否 | 按事件类型筛选（缺省全部） |

**响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "total": 3,
    "items": [
      {
        "id": 12,
        "event_key": "high_temp_alarm",
        "event_name": "高温告警",
        "type": "alert",
        "params": { "temperature": 58.2 },
        "ts": "2026-09-12 14:23:18"
      }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| event_key | string | 物模型事件标识 |
| event_name | string | 事件展示名（后端按产品 TSL 回填，前端不查 TSL） |
| type | string | 事件类型：`info` / `alert` / `fault`（来自物模型 `events[].type`） |
| params | object | 事件参数原文 |
| ts | string | 事件时间 |

---

## 9. 实时推送（WebSocket，E2）

> ✅ **已实现（2026-09-14）**：端点 `/ws/telemetry` 已上线。实测端到端延迟 **22ms**、打开详情页 12 秒内 `/telemetry/latest` 请求 **0 次**。改造点确实只收敛在 `DeviceDetail.vue` 一处；**5s 轮询保留为兜底**（WS 断开自动接管，避免"推送一挂页面就死"）。
> ⚠️ **部署约束**：会话表为实例内存态 → **当前实现仅适用单实例**；多副本需改 Redis Pub/Sub 或 STOMP broker（已记入 V2-G 前置条件）。

| 项 | 值 |
|----|----|
| 协议 | WebSocket |
| 路径 | `ws://localhost:8080/ws/telemetry?token=<JWT>&device_key=<device_key>` |
| 握手鉴权 | 复用 JWT（**查询参数传递**，浏览器 WS API 无法自定义 Header）；token 非法 → 服务端以 `4401` 关闭 |
| 订阅粒度 | 单设备（`device_key`）；缺省订阅全部该用户可见设备 |

**服务端 → 客户端（遥测推送）：**

```json
{
  "type": "telemetry",
  "device_key": "dev_demo_001",
  "ts": 1724997600000,
  "properties": { "temperature": 26.5, "humidity": 60.0 }
}
```

**服务端 → 客户端（设备上下线）：**

```json
{ "type": "status", "device_key": "dev_demo_001", "online_status": 1, "at": "2026-09-12 19:15:46" }
```

**客户端 → 服务端（心跳）：** `{ "type": "ping" }` → 服务端回 `{ "type": "pong" }`

| 字段 | 类型 | 说明 |
|------|------|------|
| type | string | `telemetry` / `status` / `pong` / `error` |
| properties | object | 同 §2 上报报文，属性键值对 |

> **数据来源**：消费者侧（`TelemetryKafkaListener`）落库成功后，向该 topic 广播 —— 复用 D1 已有的 Kafka 链路，不新增数据通路。
> **前端契约**：`src/api/ws.js` 负责连接、**心跳保活（约 30s）**、**断线重连（指数退避 + 上限）**；组件层只消费事件，不感知连接细节。
> **验收标志（对齐任务清单 E2）**：切换设备后实时数据延迟 ≤ 1s；模拟断线 30s 内自动恢复；与 5s 轮询基线数据一致。

---

## 10. 设备分组（E4）

> ✅ **已实现（2026-09-15）**。分组的定位是「**按运维维度批量组织设备**」，与「产品」（设备的能力定义）正交。

**设计决策**

| 决策 | 选择 | 理由 |
|---|---|---|
| 分组结构 | **平铺一级**（不做树形） | 设备分组的实际用法是"按位置/用途分几组"；层级带来的递归查询与前端树组件成本换不来对应价值。若日后确需层级，加 `parent_id` 可平滑扩展 |
| 设备归属 | `device.group_id`（一对多，**不用中间表**） | "一个设备同时属于多个分组"在运维语义上无意义；多维度标记交给 `tags` |
| 标签 | `device.tags`（逗号分隔字符串） | 字段早已预留，不单独建标签表 |
| 删除语义 | **删分组不删设备** | 组内设备 `group_id` 置空回到「未分组」，避免"手滑删分组把设备一起干掉" |

**接口一览**

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/api/v1/device-groups` | 分组列表（含各分组设备数与未分组数） |
| POST | `/api/v1/device-groups` | 新建 |
| PUT | `/api/v1/device-groups/{id}` | 编辑 |
| DELETE | `/api/v1/device-groups/{id}` | 删除（设备回到未分组） |
| PUT | `/api/v1/device-groups/{id}/devices` | **批量分配设备**（`id=0` = 移出分组） |

**GET `/api/v1/device-groups` 响应：**

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "ungrouped": 1016,
    "items": [
      { "id": 1, "name": "一楼温控", "description": "展厅", "sortOrder": 0, "deviceCount": 3 }
    ]
  }
}
```

> 设备数由 `LEFT JOIN device` 一次查出（`d.deleted = 0` 过滤软删除设备），避免 N+1。

**POST / PUT 请求体：**

```json
{ "name": "一楼温控", "description": "展厅", "sortOrder": 0 }
```

> `name` 必填且**唯一**（软删除的组不占用名称）；重复返回 `4001`。

**PUT `/api/v1/device-groups/{id}/devices` 请求体：**

```json
{ "device_keys": ["dev_a", "dev_b"] }
```

| 路径参数 | 说明 |
|---|---|
| `id > 0` | 移入该分组（分组不存在 → `4001`） |
| `id = 0` | **移出分组**（回到「未分组」） |

> 响应 `{ "affected": 2 }`。实现为**一条 `UPDATE ... WHERE device_key IN (...)`**，不做逐台更新。

**设备列表新增筛选参数**

| 参数 | 说明 |
|---|---|
| `group_id`（`GET /api/v1/devices`） | `>0` 按分组过滤；**`=0` 表示「未分组」**（`group_id IS NULL OR = 0`）；缺省不过滤 |

> 「未分组」用 `0` 而非 `null` 表达，是因为 `null`（不传）已有"不过滤"的含义，两者必须区分开。

---

## 11. 产品 / 设备启停（F5）

> ✅ **已实现并运行验证（2026-09-15）**。补的是「仪表盘展示了启用/停用统计，但后端无接口、前端无入口、连认证都不校验 `status`」这条断头路。

**停用 vs 删除（语义边界，必须分清）**

| 动作 | 数据 | 接入 | 可恢复 |
|---|---|---|---|
| **删除** | 级联清空（遥测/日志/指令/影子） | 拒绝 | ❌ 不可逆 |
| **停用** | **全部保留**（含影子与配置） | **拒绝** | ✅ 一键恢复 |

**拦三处，而不是一处**

| 位置 | 作用 | 备注 |
|---|---|---|
| **EMQX 认证回调** | 拦**新连接** | 拒绝时 CONNACK `rc=5 (Not authorized)` |
| **遥测入口** | 拦**存量长连接** | 认证管不到已建立的连接 —— 只在认证处拦，停用就形同虚设 |
| **指令下发** | 拒绝下发 | 返回 4006 |

**产品级联：放在判断层，不放数据层**

停用产品**不**批量改子设备的 `status`（否则产品恢复时分不清「本来就停用的设备」与「受产品停用影响的设备」，信息不可逆丢失）。
有效状态实时合成：`device.status == 1 AND product.status == 1`（产品状态走 Redis 缓存，认证是热路径）。

**接口**

| 方法 | 路径 | 说明 |
|---|---|---|
| PUT | `/api/v1/devices/status` | body `{"device_keys":[...], "status":0\|1}`；响应 `{"affected":n}` |
| PUT | `/api/v1/products/{product_key}/status` | body `{"status":0\|1}` |

> **生效时延**：状态变更接口会**立即清除**认证缓存（`auth:v2:*` 与 `prod:enabled:*`），无需等 TTL 过期。
> **缓存格式**：`auth:v2:{deviceKey}` = `<secret>|<deviceEnabled>|<productEnabled>`；前缀带 `v2` 是让旧格式缓存自然失效。

**错误码新增**

| 码 | 含义 |
|---|---|
| **4006** | 设备或所属产品已停用 —— 接入、上报、指令全部拒绝 |

**运行验证（2026-09-15，7 项全绿）**

| # | 验证 | 实测 |
|---|---|---|
| 1 | 停用 → MQTT 接入 | **被拒（CONNACK rc=5）** ✅ |
| 2 | 恢复 → MQTT 接入 | rc=0 成功 ✅ |
| 3 | 停用 → 遥测上报 | **不落库**（发 3 条，行数不变）✅ |
| 4 | 恢复 → 遥测上报 | 正常入库 ✅ |
| 5 | 停用 → 指令下发 | **4006** ✅ |
| 6 | 停用产品 → 子设备指令 | **4006**（级联生效）✅ |
| 7 | 产品停用后设备自身 `status` | **仍为 1**（未被批量改写）✅ |

---

## 12. 告警规则管理（E3 尾巴）

> 补齐 E3 留下的断头路：**规则引擎（评估）早已落地，但规则的增删改查一直只能进 MySQL 敲 SQL** —— 有告警、无配置入口。
> 规则存 `device_rule`（DDL 见 `db/create_all.sql`），由 `RuleEngineServiceImpl` 在**每条遥测落库后**评估（链路见 §2 末段）。

### 12.1 GET `/api/v1/rules` — 规则分页

| 参数 | 说明 |
|------|------|
| `page` / `size` | 默认 `1` / `20` |
| `product_key` | 按产品过滤（缺省全部） |
| `keyword` | 规则名模糊匹配 |
| `enabled` | `1` 启用 / `0` 停用；**缺省不过滤** |

> ⚠️ `enabled=0`（只看停用）是**有效值**，前端拼参时不能写 `enabled || undefined` —— 否则 `0` 被当假值丢掉，筛选静默失效。

响应 `{total, items[]}`，`items[]` 字段：

| 字段 | 说明 |
|------|------|
| `id` / `name` / `product_key` | — |
| `device_key` | **为空 = 作用于该产品下所有设备** |
| `property_key` / `operator` / `threshold` | 监测条件。`operator` ∈ `> >= < <= == !=` |
| `level` | `1` 严重 / `2` 一般 / `3` 提示 |
| `alarm_type` | 缺省 `规则告警` |
| `action_type` | `0` 仅告警 / `1` 告警并下发指令 |
| `action_payload` | `action_type=1` 时下发到设备的 **JSON 对象**（以字符串形式返回） |
| `cooldown_sec` | 抑制窗口：同规则 + 同设备在该窗口内只告警一次 |
| `enabled` | `1` 启用 / `0` 停用 |
| `alarm_count` | **派生字段**（非数据库列）：该规则累计触发的告警数。用于识别"启用中却从没触发过"的配错规则 |

### 12.2 GET `/api/v1/rules/{id}` — 详情
字段同列表单项（含 `alarm_count`）。

### 12.3 POST `/api/v1/rules` — 新建（响应 `{id}`）

```json
{
  "name": "高温自动关断",
  "product_key": "p_demo_001",
  "device_key": null,
  "property_key": "temperature",
  "operator": ">",
  "threshold": 50,
  "level": 1,
  "alarm_type": "规则告警",
  "action_type": 1,
  "action_payload": "{\"switch\": false}",
  "cooldown_sec": 60,
  "enabled": 1
}
```

**校验（违反一律 `4001`）：**

- `name` 非空且 ≤ 64 字符；
- `product_key` 必须存在；
- `device_key` 若给出，必须存在且**归属于该 `product_key`** —— 否则会配出一条"永远不会命中"的规则，且不报错，只能靠猜；
- `operator` 必须在 `> >= < <= == !=` 内；`level` ∈ 1/2/3；`action_type` ∈ 0/1；`cooldown_sec` ≥ 0；
- `action_payload`：`action_type=1` 时**必填且必须是 JSON 对象**（数组/裸值/非法 JSON 都拒）；`action_type=0` 时强制置 `NULL`。
  > ⚠️ `action_payload` 是 MySQL **JSON 列**：空串不是合法 JSON（MySQL 报 `3140 Invalid JSON text`），必须落 `NULL`。
  > **控制台侧不暴露这个 JSON**：下发内容由「物模型可写属性 → 值」行编辑生成（`PropertyValueInput` 按 TSL 的 `type` 渲染控件）。下行的 `payload` 本就是属性扁平赋值（固件 `device_property_handle(key, value_js)` 按 key 逐个处理），合法属性名 / 类型 / 取值范围早写在 TSL 里 —— 让用户手写 JSON 只会把已经签好的合同再口述一遍，打错 key 或写错类型会让规则静默地永不生效。

### 12.4 PUT `/api/v1/rules/{id}` — 编辑（全字段覆盖）

> 实现上**必须用显式 `set(...)` 而不是 `updateById`**：MyBatis-Plus 默认跳过 null 字段，而本表 `device_key` / `action_payload` 允许为 NULL —— 用 `updateById` 会导致"从『告警并下发』改回『仅告警』后，旧 payload 清不掉"。

### 12.5 PUT `/api/v1/rules/{id}/status` — 启用 / 停用
body `{"enabled": 0|1}`。

### 12.6 DELETE `/api/v1/rules/{id}` — 删除（逻辑删）
`deleted=1`；**历史告警保留** —— `device_alarm.rule_name` 已冗余规则名，删规则后旧告警仍可读（`rule_id` 成孤儿引用，无外键，物理无害）。

### 12.7 ⭐ 写操作一律清两类缓存

由 `RuleEngineService.evictRuleCache()` / `evictCooldown()` 承担：

| 缓存 | 键 | 不清的后果 |
|------|----|-----------|
| 规则列表缓存（TTL 60s） | `rules:{product_key}` | 「界面改了、上报还是老规则」的假故障。**改了 `product_key` 时新旧产品都要清** |
| 冷却窗口（TTL = `cooldown_sec`） | `rule:cd:{ruleId}:{deviceId}` | 改完规则立刻测会被上一次告警的抑制窗口挡住 → 看起来像"改动没生效" |

### 12.8 验证记录（2026-09-16 实测）

| # | 验证 | 实测 |
|---|------|------|
| 1 | 列表 | 2 条既有规则 + `alarm_count` 与 SQL 一致 |
| 2 | 入参校验 6 负例 | 全部 `4001`，文案明确（运算符非法 / 缺 payload / payload 非 JSON / payload 是数组 / 产品不存在 / 级别越界） |
| 3 | ⭐ 规则缓存失效 | 预热到 `EXISTS rules:p_demo_001 = 1` → 新建后 **= 0**；重新回填的缓存含 **3** 条（原 2 + 新 1） |
| 4 | 新规则立即生效 | 新建后 **562 ms** 内上报即命中并落 `device_alarm`（远小于 60s TTL，证明确实走了新规则集） |
| 5 | ⭐ 冷却失效 | 编辑后 `rule:cd:*` 键数 **0**；立刻再报，告警数 **1 → 2** |
| 6 | 启停 | `enabled` 落库 0/1 正确 |
| 7 | 逻辑删 | `deleted=1`；列表与详情均查不到；`total` 回到 2 |
| 8 | 前端浏览器冒烟 | 渲染 2 行、**属性下拉由该产品物模型 TSL 填充**、编辑弹窗回填条件、开关触发 `PUT /rules/2/status`、**运行时错误 0** |
| 9 | 联动下发内容编辑器（2026-09-17 改版） | 可选项**只列出物模型可写属性**（`switch` / `dqy`；readonly 的 temperature / humidity / co2 不出现）；bool→开关、float→数字控件；两属性载荷 `{"switch":false,"dqy":0}`；**编辑既有规则能反向还原成属性行**（`{"switch": false}` → 1 行、开关为关）；已用属性在下拉里 `is-disabled=true`（DOM 实测）；**运行时错误 0** |

---

## 附 · 已实现接口索引（v1.3 之后落地，本节为补录）

> 下列接口**已在代码中实现并运行**，v1.3 文档未收录。此处补录签名（报文细节以代码为准），恢复「本文档 = 接口单一事实源」。
> ⚠️ 若与代码不一致，**以代码为准并回填本文档**。

| 方法 | 路径 | 用途 | 引入 | 关键参数/响应 |
|------|------|------|:---:|------|
| GET | `/api/v1/devices` | 设备列表 | 3.3 | 新增 `online_status`（1 在线 / 2 离线，空或 0 不过滤） |
| DELETE | `/api/v1/devices` | 批量删除设备 | 3.3 | body `{"device_keys":["dev_x",...]}`；级联清理遥测/日志/指令 + 清影子与 Redis 缓存；响应 `{"deleted":n}` |
| GET | `/api/v1/devices/{device_key}/logs` | 设备操作日志（详情页「操作日志」tab） | 3.3 | `page`/`size`；`items[].log_type`（1 上线 2 下线 3 操作 4 指令 5 告警）+ `content` + `created_at` |
| GET | `/api/v1/products` | 产品列表 | E5 | 响应 `items[]`：`product_key` / `name` / `protocol` / `data_format` / `node_type` / `tsl` / `created_at` |
| POST | `/api/v1/products` | 新建产品 | E5 | body `{name, node_type?}`；自动生成 `product_key` |
| GET | `/api/v1/products/{product_key}/tsl` | 产品物模型查询 | E5 | 返回 `{properties[], events[], services[]}` |
| PUT | `/api/v1/products/{product_key}/tsl` | 保存物模型 | E5 | 校验：`type` ∈ int/float/bool/string/**enum**；`enum` 必须配 `elements[{value,key}]`；`access` ∈ readonly/readwrite；key 全局唯一且 `[A-Za-z0-9_]{1,64}`；**保存后失效影子 TSL 缓存** |
| GET | `/api/v1/devices/{device_key}/shadow` | 设备影子三栏 | E1 | 返回 `desired` / `reported` / `delta` |
| PUT | `/api/v1/devices/{device_key}/shadow/desired` | 设置期望值 | E1 | 离线时挂起，设备上线自动补发 |
| DELETE | `/api/v1/devices/{device_key}/shadow` | 清除影子 | E1 | — |
| GET | `/api/v1/devices/{device_key}/commands/pending` | 待下发指令队列 | E1 | `items[]` + `total` |
| POST | `/api/v1/commands/ack` | 设备指令 ACK 回调 | M4 | EMQX 调用；**JWT 放行**；状态机 1→2/3 |
| GET | `/api/v1/device-groups` | 分组列表 | E4 | 响应 `{ungrouped, items[]}`；`items[]` 含 `deviceCount` |
| POST / PUT / DELETE | `/api/v1/device-groups[/{id}]` | 分组增删改 | E4 | body `{name, description?, sortOrder?}`；**删组不删设备**（组内设备回到未分组） |
| PUT | `/api/v1/device-groups/{id}/devices` | 批量分配设备到分组 | E4 | body `{"device_keys":[...]}`；**`id=0` 表示移出分组**；响应 `{affected:n}` |
| GET | `/api/v1/devices` | 设备列表（**新增分组筛选**） | E4 | 新增 `group_id`：`>0` 按组过滤，**`=0` 表示未分组**，缺省不过滤 |
| PUT | `/api/v1/devices/status` | 批量启停设备 | F5 | body `{"device_keys":[],"status":0\|1}`；停用即拒绝 MQTT 接入（认证 + 入口双层），**数据全保留**；响应 `{affected:n}` |
| PUT | `/api/v1/products/{key}/status` | 启停产品 | F5 | body `{"status":0\|1}`；停用后其下**所有**设备拒绝接入（级联在判断层，不改子设备 `status`） |
| GET | `/api/v1/rules` | 告警规则列表（E3 尾巴） | §12 | `product_key`/`keyword`/`enabled`/`page`/`size`；`items[].alarm_count` 为派生字段 |
| GET | `/api/v1/rules/{id}` | 告警规则详情 | §12 | 含 `alarm_count` |
| POST / PUT / DELETE | `/api/v1/rules[/{id}]` | 规则增删改 | §12 | body 见 §12.3；删除为逻辑删、历史告警保留；**写操作清 `rules:` 与 `rule:cd:`** |
| PUT | `/api/v1/rules/{id}/status` | 启用 / 停用规则 | §12 | body `{"enabled":0\|1}` |
> ⚠️ **已移除接口（2026-09-13 汤园决策）**：`POST /api/v1/demo/reset`（G5 演示数据一键重置）及前端 `demoReset` 封装**已整体删除**——清库级接口不应长期留在代码库中（曾在设备页工具栏暴露，误点即全库归零）。
> 若将来演示确需复位能力，建议**在演示脚本里直接用 SQL**（`db/seed.sql` 即基线定义），不再恢复该 HTTP 端点。

> ⚠️ **语义提醒（`9cc6213` 决策）**：**命令**（`POST /devices/{key}/commands`）在设备离线时**直接失败 `4005`**，不挂起；**期望值**（影子 desired）离线**挂起并上线补发**。两者语义已分家，勿混用。

