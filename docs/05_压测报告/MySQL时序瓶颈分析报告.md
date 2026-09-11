# MySQL 时序数据写入瓶颈分析报告（V2-C）

> 阶段：V2-C · 规模化压测链路构建（第 1 轮）
> 日期：**2026-09-10**
> 结论先行：**当前瓶颈不在裸 MySQL，而在应用侧写路径**——稳态消化平台 ≈ **98~100 msg/s（≈ 300 行/s）**，在输入 50→825 msg/s 范围内近乎水平。被拒绝(队列溢出)的数据**静默丢失且未被失败计数**，观测存在缺口。

---

## 1. 测试环境与方法

| 项 | 值 |
|----|----|
| 压测脚本 | `tmp/test_storm_telemetry_v2.py`（aiomqtt + asyncio + 连接池模式） |
| 模拟设备 | `flood_devices.txt` 1000 台（`POST /api/v1/devices/batch` 注册，16 字节随机 secret） |
| 数据链路 | MQTT → EMQX(规则转发) → `POST /api/v1/telemetry` → `@Async` 异步入库 MySQL |
| 入库模式 | 每条消息 = 3 行 INSERT（temperature/humidity/switch）+ 1 次 device 表 UPDATE |
| 线程池 | `telemetryExecutor`：core=4 / max=8 / queue=5000 / 默认 AbortPolicy |
| Hikari | 默认池 10 连接，max-lifetime=180s，connection-timeout=30s |
| 监控 | Prometheus 采样：`iot_telemetry_process_duration_seconds`（耗时）、`iot_telemetry_queue_pending`（队列水位）、`iot_telemetry_process_failures_total`（失败数） |

**测试方式**：按"设备数 × 上报周期"分 3 档逐级加压，每档记录 DB 行数增量 + Prometheus 稳态速率。

> ⚠️ Windows 系统限制：`select()` 事件循环单次最多 ~64 socket，无法为 1000 台各开一条 TCP 连接，故 ≥500 台时用**连接池模式**（200 条连接承载 1000 个设备身份）。该模式聚合 msg/s 与每设备一连接一致，但会造成**数据归并到 ~200 个池首设备**（见 §3.4）。

---

## 2. 测试结果

### 2.1 分档结果表

| 档位 | 设备数 × 周期 | 连接模式 | 输入速率(msg/s) | 稳态消化(msg/s) | 发布数 | 落库消息数 | 交付率 |
|------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| T1 基线 | 100 × 2s | 每设备一连接 | 48.7 | 48.7 | 4500 | ≈ 4505 | **≈ 100%** |
| T2 中压 | 500 × 1s | 连接池 200 | 432 | **≈ 100** | 39965 | ≈ 14552 | **≈ 36%** |
| T3 高压 | 1000 × 0.5s | 连接池 200 | 825 | **≈ 98** | 51687 | ≈ 15274 | **≈ 30%** |

> 落库数含压测结束后"积压排空"的部分；**稳态消化速率**（Prometheus `rate(timer_count)`）才代表系统真实吞吐能力，是判瓶颈的关键指标。

### 2.2 关键发现：吞吐平台

```
输入速率  48.7       432        825        (msg/s)
          │           │          │
交付速率  ~48.7  →  ~100  →   ~98      (msg/s)   ★ 水平封顶
          └───────────┴──────────┴── 稳态消化被钉死在 ~100 msg/s
```

- **输入 50→432→825 msg/s 三倍拉升，稳态消化几乎纹丝不动（~98-100）**——这是典型的**吞吐平台(天花板)**，而非线性增长。
- 换算行写入：每条 3 属性 → 平台 ≈ **294~300 行/s**。
- 溢出部分：队列满 → enqueue 抛 `TaskRejectedException` → HTTP `5000` → EMQX Webhook 弃推。**数据丢失，交付率掉到 ~30%。**

### 2.3 链路耗时（来自 Timer 分布）

压测步入饱和后，`iot_telemetry_process_duration_seconds` 的 P95 从基线 ~0.06s 抬升至 **0.26s+**，且波动加剧——worker 在单行 INSERT + device 更新上排队。

---

## 3. 瓶颈根因定位（自高到低，附代码证据）

### 3.1 【最高】stdout 控制台 SQL 日志（`log-impl: StdOutImpl`）
`application.yml`：
```yaml
mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl  # 每条 SQL 打控制台
```
- 每条消息触发的 3 INSERT + 1 UPDATE = 4 条 SQL 全部序列化写控制台；倍率 48→825 msg/s 时即每秒上千行日志。
- **每一次字符串拼接 + 控制台 I/O 都在 worker 线程上串行执行**，是单条处理耗时被放大的首要原因，也是平台项的"隐形拉低器"。生产配置应改为 `org.apache.ibatis.logging.nologging.NoLoggingImpl` + 慢 SQL 监控。

### 3.2 【高】单行 INSERT、无批量批量写（`TelemetryServiceImpl.process()`）
```java
for (Map.Entry<String, Object> entry : request.getProperties().entrySet()) {
    telemetryMapper.insert(t);   // 每个属性单独一次 INSERT
}
```
- 每条消息 3 条独立 INSERT 往返，**无 batch**。批量（`ExecutoreBatch` 或一条 multi-values INSERT）可将往返压到 1/N 次。

### 3.3 【高】每条消息都写 device 表（`process()` 末尾）
```java
deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
    .set(Device::getOnlineStatus, 1).set(Device::getLastOnlineAt, now) ... );
```
- **每消息 1 次 UPDATE device**（且大多数落在同一批池首设备上）。hot row 在 `device` 表上造成 InnoDB 行锁竞争，进一步拖垮吞吐。
- 高并发下"每条遥测都刷 last_online_at"是典型反模式——应在 time-window(如 30s)内聚合并只写一次。

### 3.4 【高】连接池模式 → 数据归并 → hot row
- `clientid AS device_key`（EMQX 规则 SQL）：连接池里整组消息都记到**池首设备**名下。
- 实测 `top writers`：设备#1 落 4627 行、设备#250 落 3141 行，其余设备仅 ~430 行 → **~200 个池首设备成为写入热点**，device 行锁与 telemetry 分区写都集中在这几十~几百行。
- 这是 Windows fd 限制下的平台约束，不影响聚合 msg/s，但会放大行锁竞争，报告中须如实注明。

### 3.5 【中】异步池偏小 + AbortPolicy 硬丢
`AsyncConfig`：
```java
executor.setCorePoolSize(4); executor.setMaxPoolSize(8);
executor.setQueueCapacity(5000);
```
- 并发写 worker 只有 4~8 个，过少。
- 默认 `AbortPolicy`：队列满即抛异常 → **丢弃**。且 @Async 直调无未来对象，异常直接在 HTTP 请求线程抛 → `5000`。

### 3.6 【中】观测缺口 —— 丢弃未被失败计数
```java
meterRegistry.counter("iot_telemetry_process_failures_total", "result","failed").increment();
```
该计数器在 `process()` **体内**（出队后）才自增；而**栅栏丢弃发生在入队前**，根本不会进入 `process()`。实测 T3 大量丢弃，`failures_total` 仍为 **0 / no data**。→ **告警"入库失败率>1%"(B6) 在此场景失效**，需在 Controller 入队处补计数。

---

## 4. 对架构演进的启示（供 Kafka 立项依据）

1. **交付率随输入增大崩塌 + 静默丢弃**：当前"EMQX Webhook 直推 + 有界异步队列 + Abort"在突发/高压下丢数据且不可见。→ Kafka 作为**削峰缓冲 + 可回溯**的中间层（生产 → Kafka → 消费批量落地）可根治"打满即丢"。
2. **单节点 MySQL 单行插入的天花板**：即便先做应用侧优化（去控制台日志 / 批量 INSERT / 合并 device 更新 / 扩 worker），单张 `device_telemetry` 分区的单行插入仍会先于裸 MySQL 极限到来 → 需要 **RewriteBatchedStatements / 未来时序库(TDengine) 列式批写** 的有力证据。
3. 本报告的"平台曲线 + 交付率崩塌 + 观测缺口"三张证据，正是 §0.2 三条铁律要求的"**先造数据打到真瓶颈，再上架构**"。

---

## 5. 下一步建议

| # | 动作 | 预期 |
|---|------|------|
| 1 | 关闭 `StdOutImpl`，改慢 SQL 监控 | 端到端吞吐显著抬升，暴露下一层瓶颈 |
| 2 | INSERT 改批量（同设备多消息合并 / multi-values） | 拉高 MySQL 使用率 |
| 3 | device 在线状态改为时间窗聚合写 | 消除 device hot-row 锁 |
| 4 | 入队前补丢弃计数 + Webhook 改异步缓冲区 | 封堵观测缺口 |
| 5 | 应用侧修复后**重测**，定位"裸 MySQL 时序插入拐点" | Kafka/TDengine 立项的硬数据 |

> 关联原则：先可观测 → 再压测打到真瓶颈 → 每个架构决策留对比报告（项目任务清单 §0.2）。

---

## 附：V2-C 第 2 轮 · 应用侧写路径优化后重测

> 日期：2026-09-10 夜 · 落地 4 项修复（见下），重建 jar 重启后端后重测。

### 落地修复

| # | 修复 | 文件 |
|---|------|------|
| 1 | 去控制台 SQL 日志 → `NoLoggingImpl` | `application.yml` |
| 2 | 单行 INSERT → 同报文 **multi-values 批量 INSERT**（1 条 SQL/消息） | `DeviceTelemetryMapper.insertBatch()` + `TelemetryServiceImpl` |
| 3 | device 在线状态 → **Redis SETNX 5s 时间窗聚合**，每设备窗口内只写一次 device 表 | `TelemetryServiceImpl.refreshOnline()` |
| 4 | 入队丢弃计数（观测缺口）：`AbortPolicy` → 自定义 **计数+日志静默丢弃**，新增 `iot_telemetry_rejected_total` | `AsyncConfig` |

### 优化后重测结果

| 指标 | 优化前 | 优化后 | 提升 |
|------|:---:|:---:|:---:|
| 稳态消化平台 | ~98 msg/s（≈300 行/s） | **~248 msg/s（≈744 行/s）** | **2.5×** |
| 队列满时的丢弃 | 不可见（`failures_total` 仍 0） | `rejected_total` 计数（T4 实测 27694） | 观测缺口封堵 |
| 平台以下输入 | 打满即丢 | **零丢弃**（T 档 13500 条 rejected 无新增） | ✅ |

- T4 重档（1000 台×0.2s，实际 954 msg/s）：仍过载，`rejected_total=27694` 可见——**「打满即丢」从静默变为可观测**，这正是 Kafka 立项要解决的场景，现在有了实锤数据。
- 确认档（120 台×0.5s，217 msg/s < 平台）：零丢弃，稳态正常。
- 新平台 ~248 msg/s 的限速主体已从「日志+单行写」转移到 **MySQL 分区批量插入 / 接收链路**（worker 不再被写日志拖死），为下一层压「裸 MySQL 拐点」创造了条件。

### 待确认（下一层）

- 当前 ~248 msg/s 是否已触底 **MySQL 批量插入**极限？仍需：① 证明 worker 空闲（thread dump）、② 必要时转向磁盘 IO / InnoDB 参数 / 分区策略排查，再锁定"裸 MySQL 时序插入拐点"精确值，回填 C2「出现明确拐点」。