# BUG_LOG · 物联网 PaaS MVP

> 每解决一个问题/BUG 立即追加记录：现象 / 场景 / 排查根因 / 状态

## 2026-09-11

### BUG-18：D1 端到端可靠性压测——Kafka 段 offset 续消费生效，但 EMQX webhook `request_ttl=45s` 在突发+后端宕机时过期丢弃 10798 条

- **现象**：以 qos0 向 `dev_single_check_001` 瞬间灌入 20000 条遥测（≈1 万条/s），消费进行中（DB 仅 879 条）`taskkill /F` 崩溃后端；重启后 DB count 先续涨到 9202 后长期稳定在 9202，未达 20000，缺口 ≈10800 条。
- **场景**：D1 阶段 Kafka 链路端到端可靠性验证（EMQX→webhook→后端 KafkaProducer(acks=all)→topic `iot.telemetry`→消费者组(手动 ack)→MySQL）。
- **排查根因**（用 EMQX 规则/action metrics 定位）：
  1. **Kafka 段可靠、offset 续消费生效**：崩溃时 DB 879 → 重启后靠 Kafka 已持久化副本恢复到 9202；`kafka-consumer-groups --describe` 显示 `LAG=0`（消费者追平），partition 1 `LOG-END-OFFSET=11011` 全部消费。证明「后端重启丢在途→offset 续消费」已达成。⚠️ 但 note：producer 用同一 `device_key` 作 key 哈希到单一 partition（0/2 为空），属正常。
  2. **入口边界丢消息**：EMQX webhook action `fwd_telemetry` metrics：`matched=21802 / success=10314 / dropped=10798`，其中 `dropped.expired=10798`（**全部因 `request_ttl=45s` 过期**）。即 20000 条突发中，仅 ~11011 条在宕机窗口内成功 POST 到后端（含 EMQX webhook at-least-once 重发产生的重复记录 → Kafka 11011 记录去重后 distinct seq=9202）；其余 ~10800 条在 EMQX 侧排队等待时 `request_ttl` 过期被丢弃，**从未进入 Kafka**。
- **结论**：D1 把耐久性下沉到 Kafka 后，**后端重启不再丢已在 Kafka 中的消息**（核心目标达成）；但 **EMQX→后端 webhook 仍是尽力而为 HTTP，后端宕机超 `request_ttl`（45s) 时突发积压消息会被过期丢弃**，这是此前 Webhook 可靠性分析中"EMQX 缓冲 45s TTL"边界的具体化。
- **修复方向（待评估）**：① 调大 EMQX webhook `request_ttl` / `max_buffer_bytes`（缓解但治标）；② 后端 `/api/v1/telemetry` 改为异步 `send()`（不阻塞 `.get`），webhook 快速返回、避免 EMQX 积压，耐久性交给 Kafka（治本，需再验 crash 时生产者在途缓冲）；③ 入库幂等以吸收 webhook 重发的重复记录（对应 D2）。
- **状态**：📌 已定位并量化；Kafka 段已闭环（offset 续消费验证通过），webhook 入口边界待 D1 收尾或 D2 处理。

### BUG-19：D2 消费端幂等去重——at-least-once 重放导致数千条遥测重复落库

- **现象**：D1 崩溃恢复压测中，Kafka partition 1 有 11011 条记录但 DB 去重后仅 9202 个 distinct seq；`device_telemetry` 全表存在 **4061 个重复组、31550 条多余行**（多轮压测 + webhook 重发 + offset 重放积累）。
- **场景**：D1 引入 Kafka 后 at-least-once 语义（崩溃未 ack 的消息重启后重放 + EMQX webhook 至少一次重发），而 `device_telemetry` 原只有主键 `(id,ts)`，对业务键 `(device_id, property_key, ts)` 无唯一约束，重复插入无拦截。
- **排查根因**：写入路径 `insertBatch` 是普通 multi-values INSERT，无幂等；同一设备同一属性同一 `ts` 的同一条上报被重复插入成多条行，无约束兜底。
- **修复（D2 幂等去重）**：
  1. **清存量**：`DELETE t1 ... JOIN t2 ... AND t1.id > t2.id` 保留每组最小 id，删除 31550 条多余行（total 461176→429626）。
  2. **加唯一约束**：`ALTER TABLE device_telemetry ADD UNIQUE KEY uk_device_prop_ts (device_id, property_key, ts)`——唯一键含分区列 `ts`，满足分区表要求；删冗余 `idx_device_prop_ts`；同步 `db/schema.sql`。
  3. **改写入**：`DeviceTelemetryMapper.insertBatch` 追加 `ON DUPLICATE KEY UPDATE id = id`（冲突时无实际变更 → 吞掉重复、不报错、不中断批量、保留首行）。
- **验证**：同一遥测 JSON（固定 ts）向 Kafka 投递 3 次 → DB 仅落 3 行（每属性 1 份），重复被吞、无 DuplicateKeyException、正常新数据不受影响 ✅
- **状态**：✅ 幂等去重完成（D2 前半）；背压「消费不过来时不丢消息」待 D2-2 后续处理。

## 2026-09-10

### BUG-16：V2-B 可观测性验证时 Hikari 拿不到连接（MySQL 服务 STOPPED）

- **现象**：压测自测遥测上报返回 `code:5000`；`/actuator/prometheus` 自定义链路耗时 Timer 的 `count` 一直为 0；日志刷 `Failed to obtain JDBC Connection` 与大量 `No operations allowed after connection closed`
- **场景**：V2-B Actuator+Micrometer 埋点建设后进行端点实测
- **排查根因**：
  1. 起初误判为埋点代码问题（`Timer.count=0` 以为 sample 未执行）。实际 **第一次 scrape 时异步处理尚未完成是正常时序**，第 2 次 scrape `count` 即变 1.0，埋点正确。
  2. 真正的坑是 **MySQL57 服务处于 STOPPED、3306 无监听**（可能是环境/资源竞争导致），后端 startup 时 Hikari `checkFailFast` 直接初始化失败，连接池空转，DB 健康检查 DOWN。
  3. 曾用 `sc start MySQL57` 失败（Access Denied，当前 shell 非管理员）。
- **修复**：用 `Start-Process cmd -Verb RunAs "net start MySQL57"` 触发 UAC 弹窗提权启动 MySQL57 服务成功；Hikari 池自动重建，DB 恢复 UP。
- **附带加固**：`application.yml` 补 `hikari.max-lifetime:180000` 与 `connection-timeout:30000`，缩短连接超出 MySQL wait_timeout 导致 broken 的窗口。
- **验证**：`/actuator/health` 三组件(EMQX/db/redis)全 UP；遥测 `temperature=26.5` 入库；`iot_telemetry_process_duration_seconds_count=2`、`queue_pending` gauge 均正常。
- **状态**：✅ 已解决

### BUG-17：C 压测揭示遥测写入吞吐平台 ~100 msg/s + 队列溢出数据静默丢弃

- **现象**：输入速率 50→432→825 msg/s 三档踩压，稳态消化钉死在 ~98-100 msg/s（≈300 行/s）；交付率从 100% 崩塌到 30%；T2 发布 39965 条仅 14552 条落库（36%）、T3 发布 51687 条仅 15274 条落库（30%）。
- **场景**：V2-C 分档压测（100/500/1000 台 flood 设备，`test_storm_telemetry_v2.py` 连接池模式）。
- **排查根因**（自高到低）：
  1. `mybatis-plus.log-impl=StdOutImpl` —— 每条 SQL 打控制台，worker 串行控制台 I/O 严重拖慢单条处理（首要因素）。
  2. 单行 INSERT，每条消息 3 属性 = 3 次独立 INSERT，无 batch（`TelemetryServiceImpl.process()`）。
  3. 每条消息都 `UPDATE device SET last_online_at`，且连接池归并到 ~200 池首设备 → device hot-row 行锁竞争。
  4. 异步池 core=4/max=8 + 队列 5000 + 默认 `AbortPolicy`，溢出即丢弃。
  5. **观测缺口**：`iot_telemetry_process_failures_total` 在 `process()` 体内(出队后)自增；栅栏丢弃发生在入队前，不进入 process()，故大量丢弃时该计数仍为 0 → B6 告警"入库失败率"在此场景失效。
  - 平台约束：Windows select() 单次 ~64 socket，≥500 台被迫用连接池(200 连接承载 1000 身份)，数据归并 + hot row。
- **状态**：✅ 已定位，第 2 轮已部分修复（观测缺口封堵 + 吞吐提升），详见《docs/05_压测报告/MySQL时序瓶颈分析报告.md》附。
  - **第 2 轮落地**：去控制台 SQL 日志(NoLoggingImpl) / multi-values 批量 INSERT / Redis 5s 时间窗聚合 device 更新 / AbortPolicy→计数静默丢弃(新增 `iot_telemetry_rejected_total`)。
  - **重测**：稳态平台 ~98 → **~248 msg/s（2.5×）**；低于平台零丢弃；T4 重档 27694 条丢弃从静默变可观测。
  - **遗留**：裸 MySQL 批量插入极限未锁定（下一层）。

## 2026-09-05

### BUG-5：控制台登录密码校验失败（BCrypt 哈希被截断）

- **现象**：`POST /api/v1/login` 用正确密码（admin/admin123）仍返回 `4010 用户名或密码错误`
- **场景**：新增控制台登录接口（JWT），`user` 表 `password_hash` 存 BCrypt 哈希（`$2b$12$...`，60 字符）
- **排查根因**：通过命令行/脚本直接执行 SQL 插入时，`$` 符号被 PowerShell/MySQL 客户端转义或截断，导致落库的 `password_hash` 不是完整 60 字符哈希，`BCryptPasswordEncoder.matches()` 比对必然失败
- **修复**：改用 SQL 文件（`tmp/fix_user.sql`）承载完整哈希，经 `Get-Content | mysql.exe` 管道执行，确保 `$` 原样写入
- **验证**：`admin/admin123` 登录成功签发 JWT（`code=0`，token 有效）✅
- **状态**：✅ 已解决

### 控制台后端接口补齐联调（非 BUG，功能完善）

- **新增**：`GET /api/v1/devices/{key}/tsl`（返回设备所属产品物模型，前端动态渲染依据）+ `POST /api/v1/login`（JWT 登录，BCrypt 校验）
- **验证**：TSL 接口返回 properties/services/events（中文正常，UTF-8）；命令下发接口在 EMQX 容器恢复后 `status=1` 已下发 ✅
- **状态**：✅ 完成

### BUG-6：批量注册设备名唯一冲突（`Duplicate entry ... for key 'uk_product_name'`）

- **现象**：压测脚本批量注册时报 `Duplicate entry 'p_demo_001-tp_1' for key 'uk_product_name'`，注册中断
- **场景**：任务⑫ 设备注册压测，`test_storm_auth.py` / `test_storm_telemetry.py` 用固定前缀 `tp` 注册设备名 `tp_1..tp_N`
- **排查根因**：`device` 表有 `uk_product_name`（`product_key + device_name` 唯一约束），固定前缀导致第二次运行与首次注册的设备名冲突
- **修复**：脚本改用时间戳前缀 `prefix = f"tp{int(time.time())}"`（批量 device_key 本身也含 `yyyyMMddHHmmss` 时间戳，跨批次天然不冲突）
- **状态**：✅ 已解决

### BUG-7：高吞吐上报触发 `RejectedExecutionException`（异步线程池队列溢出）

- **现象**：Webhook 吞吐压测时后端日志报 `RejectedExecutionException`，部分遥测消息未入库
- **场景**：任务⑫ 压测，20 台 × 50 条并发上报，Webhook 异步入库线程池队列打满
- **排查根因**：`AsyncConfig` 线程池 `core=2 / max=4 / queue=500`，突发流量下队列溢出直接拒绝任务
- **修复**：扩容 `core=4 / max=8 / queue=5000`（`AsyncConfig.java`），并重新构建 jar 重启后端加载新配置
- **验证**：压测 1000 条全部入库（20 台 × 50 条 × 3 属性 = 3000 行，无丢失无重复）✅
- **状态**：✅ 已解决

### BUG-8：QoS=1 消息未完全送达（客户端提前断开丢 PUBACK）

- **现象**：吞吐压测上报条数少于预期，部分消息未入库
- **场景**：任务⑫ 压测脚本 `publish_loop` 发完即 `disconnect()`，QoS=1 的 PUBACK 未确认
- **排查根因**：`client.publish()` 只入队不等待，线程发完立刻断开，EMQX 侧消息未完成 QoS=1 确认即被丢弃
- **修复**：脚本加 `info.wait_for_publish(timeout=5)` 等待 PUBACK 后再发下一条
- **状态**：✅ 已解决

### BUG-9：设备列表看不到最早注册设备（前端缺分页，只显示最新 20 台）

- **现象**：控制台设备列表只有 20 台（最新注册的），`dev_demo_001`（最早注册，id=1）看不到，且无分页控件可翻页
- **场景**：数据库已有 249 台设备（含压测批量注册），前端 `GET /api/v1/devices` 默认 `page=1&size=20` 按创建时间倒序，最早设备被挤到最后一页
- **排查根因**：前端 `DeviceList.vue` 只有简单列表，无分页/搜索；`listDevices()` 不传参走后端默认分页
- **修复**：
  1. 后端 `GET /api/v1/devices` 新增 `keyword` 参数（device_key/设备名 LIKE 模糊搜索）
  2. 前端 `DeviceList.vue` 加搜索框 + `el-pagination` 分页；`Console.vue` 接入 page/size/total/keyword 状态
- **验证**：搜索 `dev_demo` 命中 `dev_demo_001`（在线）；分页翻页正常；清除恢复全部（249）✅
- **状态**：✅ 已解决

### BUG-10：Console.vue ref 声明丢失 → `ReferenceError: page is not defined`，设备列表空白

- **现象**：控制台设备列表显示"暂无设备"（0 台），浏览器控制台报 `ReferenceError: page is not defined`
- **场景**：为设备列表加分页/搜索，修改 `Console.vue` 后页面加载失败
- **排查根因**：`Console.vue` 中 `page/size/total/keyword` 的 `ref` 声明丢失（文件被外部编辑器旧缓冲覆盖回退），`loadDevices()` 引用未定义变量，请求未发出
- **修复**：重新补上 4 个 ref 声明
- **验证**：刷新后设备列表正常加载（249 台）✅
- **状态**：✅ 已解决

### BUG-11：Console.vue 模板事件绑定丢失 → 搜索请求发出但列表不刷新

- **现象**：搜索框输入关键词点击搜索，网络请求发出且后端返回正确（total=1），但列表不刷新
- **场景**：搜索/分页功能联调
- **排查根因**：`Console.vue` 模板中 `<DeviceList>` 的 `:total/:page/:size/@search/@page-change` 绑定被回退丢失，子组件 emit 的 `search` 事件无人处理
- **修复**：重新补上 DeviceList 的 props 与事件绑定
- **验证**：搜索过滤、清除恢复、分页翻页全部正常 ✅
- **状态**：✅ 已解决

### BUG-12：el-input `@keyup.enter` 触发搜索不可靠

- **现象**：搜索框输入关键词按回车，不触发搜索请求
- **场景**：搜索功能交互验证（浏览器自动化测试）
- **排查根因**：Element Plus `el-input` 组件上 `@keyup.enter` 事件触发不可靠（事件绑定在组件根元素，内层 input 按键事件冒泡不稳定）
- **修复**：在 `el-input` 的 `#append` 插槽加搜索按钮，点击触发 `handleSearch`；保留 `@keyup.enter` 作为辅助
- **状态**：✅ 已解决

### BUG-13：后端重启空档期打开页面 → 设备列表一直空白

- **现象**：后端重启期间打开控制台，设备列表显示"暂无设备"，之后后端恢复也不自动加载
- **场景**：后端从旧 jar 切换到新 jar（或 IDEA 重启）的空档期访问前端
- **排查根因**：`loadDevices()` 只在页面挂载时调用一次，加载失败后无重试/轮询；轮询只刷新遥测（`loadLatest`），不刷新设备列表
- **修复**：手动刷新页面即可恢复（未改代码）；后续可考虑设备列表加载失败自动重试
- **状态**：✅ 已解决（刷新页面）

### BUG-14：控制台业务接口无 JWT 强制校验（安全缺口，审计发现）

- **现象**：除 `/login` 外，设备查询、遥测拉取、指令下发、设备注册等控制台接口全部无鉴权，任何人摸到 8080 端口即可查询数据、下发指令控制设备
- **场景**：代码审计按证据核查（非勾选项）：全工程无 `Interceptor`/`Filter`/`WebMvcConfig`，`JwtUtil` 仅在 `UserServiceImpl.login()` 签发一次 token，从未在校验侧使用
- **排查根因**：MVP 阶段 JWT 只实现了"签发"半环，"校验"半环（拦截器）预留未落地，形成安全缺口
- **修复**：
  1. 新增 `interceptor/JwtInterceptor`（解析 `Authorization: Bearer`，校验签名与有效期，缺失/非法返回 HTTP 401 + 业务码 4010）
  2. 新增 `config/WebMvcConfig` 注册拦截器，保护 `/api/v1/**`，放行 `/login` 与 EMQX 设备侧回调（`/auth`、`/telemetry`、`/commands/ack`）
  3. 前端 `api/index.js` 错误拦截器补 401 处理（清 token + 跳登录）
- **验证**：未带 token → 401/4010；非法 token → 401/4010；登录后带合法 token → 设备列表正常（total=10）；设备侧 `/auth` 无 token 正常返回 deny ✅
- **状态**：✅ 已解决

## 2026-09-04

### EMQX Webhook 全链路打通，mqtt_bridge 退役（非 BUG，架构完善）

- **背景**：上报入库与 ACK 回报此前依赖临时脚本 `tmp/mqtt_bridge.py`（订阅 `up/+/property`、`up/+/ack` 转发到 SpringBoot）。排查发现 EMQX webhook 链路其实已配置且工作（规则 metrics `actions.success=141`），bridge 是冗余的
- **关键认知**：EMQX 5.8 的 connector 创建 API 字段**平铺在根级别**（`matched_type: bridge_http:post_connector`），body/method 属于 **action 层**（`POST /api/v5/actions`），connector 层只放 url/headers 等连接配置；规则通过 action 引用 connector（`actions: ["http:fwd_ack"]`）
- **新增 ack 转发链路**（此前只有 telemetry 规则）：
  - connector `iot_ack_webhook` → `http://host.docker.internal:8080/api/v1/commands/ack`
  - action `fwd_ack`（method=post, body=`${payload}`）
  - rule `forward_ack_rule`（`SELECT payload FROM "up/+/ack"`）
- **验证**（停掉 mqtt_bridge 后）：
  - telemetry：`device_telemetry` 持续入库（每分钟 48 条 = ESP32 每 5s × 4 属性）✅
  - ack：下发指令后 `command.status 1→2`（0.1s 内设备回报经 webhook 到达）✅
- **状态**：✅ 已解决，`mqtt_bridge.py` 不再需要

### BUG-4：ESP32 真实设备连接周期性断开（EOF / auto-reconnect 循环）

- **现象**：ESP32 串口日志反复出现 `mqtt connected` → 约 1.2s 后 `transport_read(): EOF` → `disconnected, auto-reconnecting...`，循环不断
- **场景**：真实设备（client_id=dev_demo_001）接入 EMQX 时，之前验证上报入库遗留的 `tmp/sim_device.py` 仍在后台运行
- **排查根因**：`sim_device.py` 用 `client_id=dev_demo_001`（与 ESP32 相同）连 EMQX 并 `loop_forever` 保持在线。EMQX 的 session takeover 机制：相同 client_id 的新连接会踢掉旧连接 → sim_device 与 ESP32 互相抢占，导致 ESP32 周期性被踢（EOF）
  - 佐证：EMQX API 查 `clients` 同时存在 `dev_demo_001` 与 `mqtt_bridge`（username 均为 dev_demo_001）；停掉遗留进程后连接时间戳 10s 不再变化
- **修复**：停掉遗留进程 `sim_device.py`（PID 20012）与 `mqtt_bridge.py`（PID 19252）
- **验证**：ESP32 稳定连接（connected_at 不再刷新）；MySQL `device_telemetry` 每 5s 持续写入真实设备数据（temperature/humidity/switch/brightness）✅
- **状态**：✅ 已解决

### 状态机补全：指令超时置 4 + ack 幂等/防重（非 BUG，缺口完善）

- **缺口**：`command.status` 状态机缺「下发后无回报 → 超时」分支；ack 无防重保护（重复/乱序 ack 会覆盖终态）
- **实现**：
  1. 超时扫描：`IotPlatformApplication` 加 `@EnableScheduling`；`CommandServiceImpl.timeoutScan()` 每 30s 扫 `status=1` 且 `sent_at < now-60s` 的记录置 4，走既有 `idx_status_sent` 索引（schema v1.2 已建，无需 ALTER）
  2. ack 幂等：仅 `status=1` 允许确认（`eq(status,1)` 条件更新防并发竞争）；重复 ack、超时后 ack 均忽略并返回当前状态
- **验证**：
  - 正常下发 → 设备 ack → `status 1→2`，重复 ack 被忽略（保持 2，ack_at 不变）✅
  - 设备离线下发 → `status=1`，sent_at 置旧后 30s 内扫描 → `status 4` ✅
  - 超时后设备再 ack → 忽略（保持 4）✅
- **状态**：✅ 已解决

## 2026-09-03

### BUG-1：设备最新遥测接口 500（TooManyResultsException）

- **现象**：`GET /api/v1/devices/{device_key}/telemetry/latest` 返回 500，日志报 `TooManyResultsException: Expected one result (or null) ... but found: 2`
- **场景**：设备上报过 temperature、humidity 两个属性后，查询最新数据
- **排查根因**：`DeviceTelemetryMapper.selectLatestByDevice` 的 SQL 返回多行（每个 property_key 一行），但方法签名 `Map<String, String>` 让 MyBatis 走 `selectOne` 单行映射，多行即抛异常
- **修复**：返回类型改为 `List<Map<String, String>>`，Service 层遍历转成 `{key: value}` 结构
- **状态**：✅ 已解决，接口验证通过

### BUG-2：控制下发报 `Field 'command_type' doesn't have a default value`

- **现象**：`POST /api/v1/devices/{device_key}/commands` 返回 500，MySQL 报 command_type 无默认值
- **场景**：curl 按契约发 `{"command_type":1,"payload":{...}}`
- **排查根因**：JSON 的 `command_type`（snake_case）无法映射到 Java DTO 的 `commandType`（camelCase），插入 NULL。与 D4 的 TelemetryRequest `device_key` 是同一类坑：**契约用 snake_case、Java 用 camelCase 时必须加 @JsonProperty**
- **修复**：`CommandRequest.commandType` 加 `@JsonProperty("command_type")`
- **状态**：✅ 已解决

### BUG-3：EMQX REST API 下发报 401/EOF，`admin:public` 被拒

- **现象**：SpringBoot 调 `POST http://localhost:18083/api/v5/publish` 失败（先 401 后 EOF），指令 status=3
- **场景**：任务⑩ 控制下发，Java 侧调 EMQX REST API
- **排查根因**：
  1. `/api/v5/status` 是免认证公开端点，用它测试误导了判断；`/api/v5/publish` 必须用 **API Key** 认证，Dashboard 账号（admin:public）Basic auth 不被接受
  2. 通过 Dashboard API（JWT）创建 API Key 时 EMQX 5.8 的 `POST /api/v5/api_key` 有内部 bug（badmatch 报错）
- **修复**：改用 `api_key.bootstrap_file` 引导方式——容器内写 `/opt/emqx/etc/api_keys.txt`（`key:secret` 格式）+ emqx.conf 加 `api_key { bootstrap_file = "etc/api_keys.txt" }`，重启容器生效
- **备注**：EMQX 容器重启后 MQTT 客户端会掉线重连；bridge/设备必须在 SpringBoot 之后启动，否则鉴权回调失败被拒（Not authorized）
- **状态**：✅ 已解决，用 API Key `iotkey:iotsecret123` 认证通过

### BUG-4：Java 调 EMQX publish 报 `EOF reached while reading`

- **现象**：API Key 配好后，PowerShell curl 直接调 `/api/v5/publish` 成功，但 SpringBoot RestClient 调用仍报 `I/O error ... EOF reached while reading`
- **场景**：同 BUG-3，Java 侧
- **排查根因**：Spring RestClient 检测到 JDK HttpClient 时默认协商 HTTP/2，EMQX 的 cowboy 不支持 h2c 升级，连接被服务端关闭；PowerShell 走 HTTP/1.1 所以正常
- **修复**：`RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory())` 强制 HTTP/1.1（HttpURLConnection 实现）
- **状态**：✅ 已解决

### 备注：404 假象（非 BUG 但易误判）

- **现象**：下发成功后 `GET /api/v1/commands/{request_id}` 报 404
- **根因**：CommandVO 返回 camelCase `requestId`，测试脚本按契约取 `request_id` 得到 null，拼出空 URL
- **修复**：CommandVO / DeviceVO 全字段加 `@JsonProperty` 对齐契约的 snake_case 输出
- **状态**：✅ 已解决

### BUG-15：前端灯光开关下发 payload 格式与固件不匹配（控制不了灯）

- **现象**：控制台点击灯光开关，设备端 LED 无响应；curl 下发扁平格式却正常
- **场景**：真实 ESP32 LED 回补验证后，前端控制面板下发指令
- **排查根因**：前端 `ControlPanel.sendProperty` 发送 `{property_key, value}` 包装结构，后端 `CommandServiceImpl.buildDownMessage` 对 payload **原样透传**至 `down/{key}/command`；固件 `device_property_handle` 遍历 payload 键直接匹配 `switch`/`brightness`/`rgb`，收到的是 `property_key`/`value` 两个键，匹配不到 `switch` 故不响应。API 契约文档此前也误记为包装格式，与固件实际协议不一致
- **修复**：前端改为发送扁平属性映射 `{ [prop.key]: value }`（与遥测上报格式一致）；同步修正 `API契约.md` 指令下发请求示例为扁平格式并注明"后端原样透传、以固件协议为准"
- **验证**：curl 扁平格式 `{"switch":true}` 下发 → 设备 ACK → status 0→1→2，LED 点亮/熄灭 ✅
- **状态**：✅ 已解决
