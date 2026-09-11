# 项目任务清单 · 物联网 PaaS MVP（单人冲刺）【已完成 · 存档】

> 状态图例：⬜ 未开始 ｜ 🔄 进行中 ｜ ✅ 完成
> 更新时间：2026-09-05（⑫ 压测完成 + ⑬ 论文初稿完成 + 全文合并 + 正式图表重绘 + JWT 拦截器落地 + 上下线方案决策为心跳扫描 + 真实 ESP32 LED 响应回补完成）
> 两条线并行：**主线程（开发 D1-D15）** + **副线程（毕业论文）**
> 📦 **本文件为 MVP 阶段存档**：13 项任务（①~⑬）全部完成，2026-09-05 封板，不再更新。
> 📎 当前进行中的 V2 任务清单见 **[项目任务清单.md](./项目任务清单.md)**。

---

## 一、主线程：开发（按依赖顺序推进）

### 阶段 0 · 地基（D1）

- [x] **① 编写 docker-compose.yml**（EMQX + MySQL + Redis 三容器）
  - 交付物：`docker-compose.yml` + `.env`
  - 端口：EMQX 1883/8883/18083，MySQL 3306，Redis 6379；含数据卷 + 健康检查
- [x] **② 三容器启动 + MQTTX 连通验证**（D1 通关标志）
  - 验收：`docker ps` 三容器 Up ｜ `localhost:18083` 打开 Dashboard ｜ MQTTX 连 `1883` 收发一条消息

### 阶段 1 · 设计蓝图（D2，当前重点）

- [x] **③ MySQL 表结构设计** → `db/schema.sql`
  - 7 张表：`product` / `device` / `device_telemetry` / `device_log` / `command` / `project` / `user`
  - 含索引、`device_telemetry` 时间分区、外键/软删约定；seed.sql + verify.sql 已通过
  - ⚠️ 这是整条链的「数据合同」，**最先做**
- [x] **④ SpringBoot 接口契约** → `docs/02_设计契约/API契约.md`
  - 4 接口：鉴权回调 / Webhook 上报 / 设备查询 / 控制下发
  - 只定签名（URL + JSON 报文），不写实现
- [x] **⑤ EMQX 配置方案**
  - `emqx_auth_http` 认证链 + Webhook 规则骨架，留 URL 占位符
  - 交付物：`docs/02_设计契约/EMQX配置方案.md`

### 阶段 2 · 后端骨架（D2）

- [x] **⑥ SpringBoot 单 module 骨架** + 建表
  - 依赖：Web / MyBatis-Plus / Redis / MySQL 驱动；4 层分包（controller/service/mapper/entity）
  - 38 源文件编译通过，7 实体 + 7 Mapper + 4 Service + 4 Controller + 配置类已就绪

### 阶段 3 · 鉴权（D3）

- [x] **⑦ 鉴权链路**：EMQX auth_http + 鉴权接口查 Redis
  - 验收：合法 key 连上、非法 key 被拒；开启认证结果缓存防连接风暴

### 阶段 4 · 上报入库（D4）

- [x] **⑧ 上报入库**：Webhook 接收 + TSL 解析 → 遥测写 `device_telemetry`、在线状态写 Redis
  - Java 侧异步接收 + 有界队列削峰
  - ✅ **2026-09-04 EMQX Webhook 全链路打通**：telemetry（`up/+/property`→`/api/v1/telemetry`）+ ack（`up/+/ack`→`/api/v1/commands/ack`）均走 EMQX 规则引擎，`mqtt_bridge.py` 退役
  - 修复 TelemetryRequest DTO 的 @JsonProperty 映射（device_key → deviceKey）

### 阶段 5 · 设备闭环（D5）

- [x] **⑨ 真实设备端到端**：ESP32/STM32 esp-mqtt 跑通 连接→鉴权→上报→入库
  - ✅ 设备端固件已改造完成：`firmware/esp32_iot`（基于 OneNET 示例，对接自研平台）
    - 协议层替换：EMQX 连接（device_key/secret 认证）+ `up/{key}/property` 上报 + `down/{key}/command` 下行 + `up/{key}/ack` 回报
    - 保留硬件逻辑：PWM LED（GPIO15 switch/brightness）+ WS2812（GPIO18 rgb）
    - ✅ **编译验证通过**（2026-09-04）：ESP-IDF v5.4.4 / esp32s3，产物 `build/iot_device.bin`（46% 空间空闲）
  - ✅ **2026-09-04 真实设备打通**：ESP32-S3 烧录后连手机热点（test/18888888）→ EMQX（172.25.85.62:1883）→ 稳定连接 + 每 5s 上报 → MySQL `device_telemetry` 持续入库（temperature/humidity/switch/brightness）
  - ⚠️ 踩坑：遗留 `sim_device.py`（同 client_id=dev_demo_001）与 ESP32 抢占会话导致周期性 EOF 断开 → 停掉遗留进程后稳定（见 BUG_LOG BUG-4）

### 阶段 6 · 查询下发（D6-D7）

- [x] **⑩ 设备查询 + 控制下发**：查询接口 + 下发接口（EMQX `/api/v5/publish`），指令状态写 `command` 表
  - 查询 3 接口验证通过：设备列表 / 最新遥测 / 历史区间（走 `idx_device_prop_ts`）
  - 下发闭环验证通过（模拟设备 sim_device.py 代替真实设备）：curl 下发 → EMQX publish（API Key 认证 + HTTP/1.1）→ 设备收指令（LED ON）→ ACK 回报（`up/+/ack` 经 bridge 转发）→ `command.status` 0→1→2 全流转
  - 新增 `POST /api/v1/commands/ack`（设备回报接口）；bridge 扩展订阅 `up/+/ack`
  - 指令状态机补全（2026-09-04）：**超时扫描**（`@Scheduled` 30s 扫 `status=1` 超 60s 置 4，走 `idx_status_sent`）+ **ack 幂等/防重**（仅 `status=1` 可确认，重复 ack / 超时后 ack 均忽略）
  - 踩坑记录见 `BUG_LOG.md`（多行 Map 映射 / snake_case 映射 / EMQX API Key / HTTP2-EOF）

### 阶段 7 · 前端（D8-D10）

- [x] **⑪ 控制台前端**：Vue3 + Element Plus 极简页（登录 / 数据展示 HTTP 轮询 / 下发按钮）
  - ✅ 2026-09-05 线框图完成：`docs/03_原型设计/原型线框图.html`（登录页 + 设备数据页）
  - ⚠️ **物模型驱动（用户自定义传感器/控制）**：前端不写死温湿度，按 `product.tsl` 动态渲染——
    `properties[readonly]`→数据卡片、`properties[readwrite]`→数据卡片+控件（bool→开关/int→滑块/string→输入）、`services`→服务按钮
  - ✅ **2026-09-05 后端接口补齐并联调通过**：`GET /api/v1/devices/{key}/tsl`（返回设备所属产品物模型）+ `POST /api/v1/login`（JWT 登录，BCrypt 校验，admin/admin123 验证通过）；命令下发接口（`POST /api/v1/devices/{key}/commands`）EMQX 恢复后验证 status=1 已下发
  - ✅ **2026-09-05 前端开发完成**：`iot-console/`（Vite + Vue3 + Element Plus + ECharts）
    - 登录页（JWT + 路由守卫，token 存 localStorage）
    - 控制台页：设备列表（在线状态）｜ TSL 动态数据卡片（只读/可写标签）｜ ECharts 历史曲线（属性 Tab + 1h/6h/24h）｜ TSL 动态下发控件（bool→开关 / int·float→滑块 / string→输入，指令状态轮询）
    - 5s 轮询最新遥测；Vite 代理 `/api` → `localhost:8080`
  - ✅ **2026-09-05 浏览器联调通过**：登录 → 控制台渲染（温度 28.9℃/湿度 61.8%/开关/亮度）→ 无 console 报错；命令下发经代理 status=1 已下发
  - ✅ **2026-09-05 列表搜索 + 分页 + 在线优先**：后端 `GET /api/v1/devices` 新增 `keyword` 模糊搜索 + 排序改为 `online_status DESC, last_online_at DESC`；前端设备列表加搜索框（device_key/名称）+ `el-pagination` 分页（每页 20 条）——解决 249 台设备时最早设备不可见问题（BUG-9~13）
  - ✅ **2026-09-05 前端设备注册入口**：新增 `DeviceRegister.vue`（侧边栏"注册设备"按钮 + 单个/批量切换弹窗 + 结果弹窗展示 device_key/device_secret 并警示仅显示一次）；注册成功自动刷新设备列表；`api/index.js` 新增 `registerDevice`/`registerBatch`
  - ✅ **2026-09-05 设备清理**：压测批量设备清理至 10 台（保留真实 ESP32 `dev_demo_001` + 测试设备A + 压测_1~5 + 3 台注册测试设备），连带删除遥测 5013 条 / 日志 80 条

### 阶段 8 · 收尾（D11-D15）

- [x] **⑫ 设备注册 + 上下线 + 压测**：批量注册、上下线状态、Webhook 吞吐 + 鉴权抗风暴压测
  - ✅ **2026-09-05 设备注册 API**：`POST /api/v1/devices`（单个）+ `POST /api/v1/devices/batch`（批量，1~1000 台）
    - 自动生成 `device_key`（`dev_{prefix}_{ts}_{i}`）+ `device_secret`（16 字节随机 hex）；`device_key` 唯一、`product_key+device_name` 唯一校验
  - ✅ **2026-09-05 上下线检测**：`@Scheduled` 30s 心跳超时扫描（`online=1` 且 `last_online_at` 超 60s → 置离线 + 写 `device_log` type=2）；上报时置在线 + 写 `device_log` type=1
  - ✅ **2026-09-05 鉴权抗风暴压测**（`tmp/test_storm_auth.py`）：100 台并发连接 EMQX，**100/100 成功，32 conn/s，平均连接 1343ms**
  - ✅ **2026-09-05 Webhook 吞吐压测**（`tmp/test_storm_telemetry.py`）：20 台 × 50 条 = 1000 条 QoS1 上报，**3.09s / 324 msg/s**，入库 3000 行（50×3 属性）无丢失无重复
  - ⚠️ 压测调优：异步线程池扩容（core 2→4 / max 4→8 / 队列 500→5000）防 `RejectedExecutionException`；脚本加 `wait_for_publish` 保证 QoS1 送达

---

## 二、副线程：毕业论文（与开发并行）

- [x] **⑬ 论文第 1-3 章初稿正文**
  - ✅ **2026-09-05 第 1-2 章完成**：`docs/04_毕业论文/毕业论文初稿_第1-2章正文.md`（约 9000 字）
    - 第 1 章 绪论（约 3000 字）：1.1 研究背景与意义 / 1.2 国内外研究现状 / 1.3 本文主要工作与组织结构
    - 第 2 章 相关技术与总体设计（约 6000 字）：2.1 相关技术基础（MQTT/EMQX/物模型TSL/后端技术栈）/ 2.2 系统需求分析（功能+非功能）/ 2.3 系统总体设计（三层架构图+六步数据流时序图+技术选型对比表+三阶段部署方案）
    - 已按大纲"产品语言→论文语言"翻译对照表改写，含 Mermaid 架构图/时序图
  - ✅ **2026-09-05 第 3 章完成**：`docs/04_毕业论文/毕业论文初稿_第3章正文.md`（约 9000 字）
    - 3.1 系统实现总体框架（工程三端结构图）/ 3.2 数据模型设计（7表+分区+软删）/ 3.3 设备注册与物模型管理
    - 3.4 设备接入与鉴权模块（鉴权时序图）/ 3.5 数据上报与存储模块（上报链路图+有界队列）/ 3.6 设备管理与在线状态模块
    - 3.7 设备控制与指令下发模块（状态机图+时序图）/ 3.8 前端控制台实现（物模型驱动结构图）/ 3.9 小结
    - 素材全部取自真实实现（schema.sql + iot-platform/src + iot-console/src），含 Mermaid 图 6 张、表 1 张
  - ✅ **2026-09-05 第 3 章审核修复**：对照真实实现逐条核实，修复 3 处事实错误（§3.8 JWT 拦截校验改为"预留后续增强"、§3.3 secret 明文存储表述、§3.6 last_online_at 索引表述）+ 3 处用词（"api 表设计"→"数据模型设计"、"上报告警"→"属性上报"、"设备侧时间戳"→"平台侧时间戳"）+ 2 处图表（图 3-1 箭头 B3→B1、图 3-2 拒绝响应标注 200+deny）
  - ✅ **2026-09-05 第 4 章完成**：`docs/04_毕业论文/毕业论文初稿_第4章正文.md`（约 7000 字）
    - 4.1 测试环境（硬件/软件/工具）/ 4.2 功能测试（14 用例表 + 六大模块验证）/ 4.3 性能测试（鉴权抗风暴 32 conn/s + Webhook 吞吐 324 msg/s + 线程池削峰调优 + 指标对比）/ 4.4 测试结论
    - 测试数据全部取自真实压测与功能验证记录，与第 2 章非功能指标逐项对比
  - ✅ **2026-09-05 第 5 章完成**：`docs/04_毕业论文/毕业论文初稿_第5章正文.md`（约 3000 字）
    - 5.1 工作总结（总体设计/业务闭环/控制台/测试验证）/ 5.2 主要成果与创新点（TSL 全链路/有界队列削峰/指令状态机/轻量私有化）/ 5.3 不足与展望（协议扩展/时序存储/平台能力/安全多租户/规模化部署）
  - ✅ **2026-09-05 全文合并版完成**：`docs/04_毕业论文/毕业论文初稿_全文.md`（摘要 + 目录 + 第 1-5 章 + 参考文献 13 篇）
  - ✅ **2026-09-05 正式图表重绘**：8 张 Mermaid 草稿图全部渲染为正式 PNG（`docs/04_毕业论文/figures/`，mermaid-cli 2x 缩放），三个论文文件（第1-2章/第3章/全文）均已用图片引用替换 Mermaid 代码块
  - ✅ **2026-09-05 JWT 业务接口拦截器落地**：新增 `JwtInterceptor` + `WebMvcConfig`，保护 `/api/v1/**` 控制台接口（设备列表/注册/遥测/TSL/指令下发/状态查询），放行 `/login` 与 EMQX 设备侧回调（`/auth`、`/telemetry`、`/commands/ack`）；缺失/非法 token 返回 HTTP 401 + 业务码 4010；前端响应拦截器捕获 401 清 token 跳登录；已验证（未带/非法 token 拒绝、合法 token 通过、设备侧接口放行）；论文 §3.8 表述已同步改回"已实现"

---

## 三、依赖关系（关键路径）

```
① docker-compose ──► ② 启动验证
③ 表结构 ──► ④ 接口契约 ──► ⑤ EMQX 配置     （设计蓝图，可与①②并行）
② + ③ ──► ⑥ 后端骨架 ──► ⑦ 鉴权 ──► ⑧ 上报入库 ──► ⑨ 设备闭环 ──► ⑩ 查询下发 ──► ⑪ 前端 ──► ⑫ 收尾
⑬ 论文 ────────────────────────────────（全程并行）
```

---

## 四、里程碑 checklist

- [x] **M1**：D1 通关（三容器 + MQTTX 连通）
- [x] **M2**：设计蓝图落地（schema.sql + API契约 + EMQX 配置）
- [x] **M3**：鉴权 + 上报入库闭环（无真实设备也能用 MQTTX 验证）
- [x] **M4**：真实设备端到端 + 控制下发（LED 响应）——真实设备上报入库已通（2026-09-04）；✅ **2026-09-05 真实 ESP32 LED 响应回补完成**：下发 switch=true（LED 点亮）与 switch=false（LED 熄灭）两条指令，均经 EMQX 下行 → ESP32 执行 → ACK 回报 → `command.status 0→1→2` 全流转，用户目视确认 LED 点亮/熄灭
- [x] **M5**：控制台前端可用 + 收尾压测（⑪ 前端联调通过 + ⑫ 压测通过）
- [x] **M6**：论文第 1-3 章初稿完成并审核修复（⑬ 第1-2章约 9000 字 + 第3章约 9000 字 + 审核修复 8 处）
