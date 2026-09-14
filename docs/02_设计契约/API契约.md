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

- **接口 1（设备鉴权回调）**：不走统一 code 格式，遵循 **EMQX 5.x 认证协议**——`204` = 通过；`200` + body `result` 字段（`allow`/`deny`）判定。⚠️ 这是 5.x 协议，与 4.x 的「200=通过 / 403=拒绝」不兼容。
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
| 认证拒绝 | `200` | `{"result": "deny"}` |
| 服务异常（交由认证链） | `4xx/5xx` | 忽略 body |

> ⚠️ EMQX 4.x 用「200=允许 / 403=拒绝」，5.x 已改为 body 的 `result` 字段 + 204 语义，两者不兼容。若返回 200 但 body 缺 `result` 字段，EMQX 判为 `ignore`（继续认证链，单认证器链尾默认拒绝），合法设备会被拒之门外。

**业务逻辑（D3 实现）：**
1. 取 `username`（即 `device_key`）查 Redis `auth:{device_key}` → `device_secret`（缓存未命中则查 `device` 表回填）。
2. 比对 `password == device_secret` → 通过返回 `204`，否则返回 `200` + `{"result":"deny"}`。
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
| 设备注册 | device_secret | device.device_secret（16 字节随机 hex） |

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
    "device": { "total": 1014, "online": 1, "offline": 1013, "enabled": 12, "disabled": 11 },
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
| device.enabled / disabled | int | `device.status=1` / `=0` |
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
> ⚠️ **已移除接口（2026-09-13 汤园决策）**：`POST /api/v1/demo/reset`（G5 演示数据一键重置）及前端 `demoReset` 封装**已整体删除**——清库级接口不应长期留在代码库中（曾在设备页工具栏暴露，误点即全库归零）。
> 若将来演示确需复位能力，建议**在演示脚本里直接用 SQL**（`db/seed.sql` 即基线定义），不再恢复该 HTTP 端点。

> ⚠️ **语义提醒（`9cc6213` 决策）**：**命令**（`POST /devices/{key}/commands`）在设备离线时**直接失败 `4005`**，不挂起；**期望值**（影子 desired）离线**挂起并上线补发**。两者语义已分家，勿混用。

