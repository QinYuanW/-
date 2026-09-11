# D2-2 消费端 ack 语义实验报告

> 日期：2026-09-11
> 目的：验证「落库失败不 ack」在 Spring Kafka `AckMode.MANUAL` 下的真实后果
> 环境：Kafka 3.9.0 单节点（KRaft，3 分区）· 消费者组 `iot-telemetry-consumers` · 后端 iot-platform:8080
> 结论一句话：**失败记录会被后一条成功消息的 ack 越过，永久跳过，无重试、无死信 → 静默丢失。**

---

## 1. 实验设计

必须让失败消息与成功消息落在**同一分区**，否则「越过」无从谈起。
`device_key=dev_demo_001` 经 murmur2 哈希固定落在 **partition 2**，故两条消息同用该设备。

| 消息 | 内容 | 预期消费行为 |
|---|---|---|
| A | `{"device_key":"dev_demo_001","ts":...}`（缺 `properties`） | `processSync` 在 `request.getProperties().size()` 处 NPE → 返回 `false` → 不 ack |
| B | `... + "properties":{"seq":"exp-final-1"}` | 正常落库 → 返回 `true` → ack |

判据：partition 2 的 `CURRENT-OFFSET`（已提交位点）、`LOG-END-OFFSET`（日志末端）、`LAG`，以及 `iot_telemetry_process_failures_total`。

投递方式：`POST /api/v1/telemetry`（真实 webhook 入口，非绕道直投 Kafka）。

---

## 2. 证据

| 阶段 | CURRENT-OFFSET | LOG-END-OFFSET | LAG | process_failures_total |
|---|:---:|:---:|:---:|:---:|
| 基线 | 2 | 2 | 0 | 未注册（0） |
| 投递 A 后（等待 8s） | **2** | **3** | **1** | **1.0** |
| 投递 B 后（等待 9s） | **4** | 4 | 0 | 1.0 |

采集命令：`kafka-consumer-groups.sh --describe --group iot-telemetry-consumers`

---

## 3. 结论

1. **「失败不 ack」成立**（第一相）：A 落在 offset 2，被消费，`processSync` 返回 false（失败计数 0→1），提交位点保持 2、`LAG=1`。失败确实没被提交。
2. **但 A 被 B 的 ack 越过**（第二相）：B（offset 3）成功 ack 后，提交位点从 2 **直接跳到 4** —— A 的 offset 2 被永久跨过。
   机制：`AckMode.MANUAL` 下 `acknowledge()` 提交的是**分区位点**（后一条的 offset+1），而非"撤回单条"。容器内待提交位点以 `TopicPartition` 为键，后被高 offset 覆盖。
3. **A 从未重试**：`process_failures_total` 全程停在 1.0，无第二次增量。
4. **A 不可追溯**：`kafka-topics --list` 仅有 `__consumer_offsets` / `iot.telemetry`，**不存在死信 topic**。

> 综合：失败记录 = **静默数据丢失**。`TelemetryKafkaListener` 注释所称"落库失败不 ack，交由后续（D2）重试策略处理"——该重试策略**并不存在**。
> 且此后任何 rebalance / 重启都只会从提交位点 4 续消费，A 永无重见天日的机会。

---

## 4. 影响的代码位置

| 文件 | 位置 | 问题 |
|---|---|---|
| `listener/TelemetryKafkaListener.java` | L31-39 `if (ok) ack; else log.warn` | 失败既不 ack 也不隔离，被后续 ack 越过 |
| `service/impl/TelemetryServiceImpl.java` | L91-96 `catch → return false` | 永久失败（unknown device）与瞬时失败（DB 异常）共用同一个 `false`，无法差异化处理 |

---

## 5. 严重性判定

🔴 **Blocker**。语义上，任何一次 DB 瞬断 / 超时 / 毒丸数据，其对应遥测会在**下一条成功消息到达时被永久跳过** —— 与 D1 刚建立的"后端重启不丢在途"目标直接冲突（D1 解决了"重启丢"，此处暴露了"失败丢"）。

---

## 6. 修复方案（D2-2 —— 已实施，见 §7）

1. **失败分类**：`unknown device_key` / 反序列化失败 → 永久；DB 异常 → 瞬时。
2. **非阻塞重试阶梯**：`@RetryableTopic` 生成 `-retry-0/1/2`，退避 1→2→4s；失败消息**移出主分区**，主分区只 ack 成功的 → 根除"越过"。
3. **死信**：`iot.telemetry-dlt` + 计数 `iot_telemetry_dlt_total`，可人工重放。
4. **真背压**：`max.poll.records` 与 `max.poll.interval.ms` 配比，避免 poll 超时触发 rebalance 风暴。
5. **与 D2-1 协同**：唯一键 `uk_device_prop_val_ts` 让重试天然幂等 →「至少一次投递 + 幂等消费 = 不丢不重」。

---

## 7. 实施记录（2026-09-11，方案 A：@RetryableTopic 非阻塞重试）

编译已通过（`mvn -o -DskipTests compile` → BUILD SUCCESS）。改动 6 改 1 增：

| 文件 | 改动 |
|---|---|
| `listener/TelemetryKafkaListener` | 加 `@RetryableTopic(attempts=4, backoff=1→2→4s, exclude=PermanentTelemetryException, dltTopicSuffix="-dlt")` + `@DltHandler`；失败改为抛异常；新增 `iot_telemetry_dlt_total` |
| `common/PermanentTelemetryException`（新增） | 永久失败标记，被 `exclude` → 跳过重试直达 DLT |
| `service/TelemetryService` | `boolean processSync` → `void processSync`（失败即抛） |
| `service/impl/TelemetryServiceImpl` | 永久失败（未知设备/空属性）→ `throwFatal`；瞬时失败 → 包成 `IllegalStateException` 走重试 |
| `config/KafkaConfig` | **`AckMode.MANUAL` → `AckMode.RECORD`** |
| `resources/application.yml` | `max.poll.records=50`、`max.poll.interval.ms=300000`、`spring.kafka.retry.topic.enabled=true` |
| `pom.xml` | 显式声明 `spring-retry` |

### ⚠️ 一个必须记下的架构后果：D1 的「手动 ack」被撤掉了

`@RetryableTopic` **不支持** `AckMode.MANUAL`（重试/DLT 容器依赖容器托管提交）。而且手动 ack 本身就是本 bug 的成因。
因此「消费成功才确认」这一 D1 的设计承诺，**改由三件套共同兑现**：
① 容器托管提交（失败记录被转交重试 topic，主分区照常推进，不再"卡住"也不"越过"）；
② 非阻塞重试阶梯 + 死信隔离（不丢）；
③ D2-1 唯一键 `uk_device_prop_val_ts`（重试幂等，不重）。

> 面试口径：**「手动 ack 保证不丢」是个常见误解** —— 它只保证"不提前提交"，但对"提交位点会越过失败记录"无解。
> 真正的解法是让失败消息**离开主分区**，这正是 retry-topic 模式的设计意图。

### 预期运行后可见的新 topic

`iot.telemetry-retry-0/1/2` + `iot.telemetry-dlt`（改动前仅有 `__consumer_offsets` / `iot.telemetry`）。

---

## 8. 运行验证（2026-09-11 19:48–19:51，后端已按 §7 重建重启）

§7 只到"编译通过"。本节补上**运行时验收**——三项判据全部通过。工具链：
`kafka-consumer-groups.sh --all-groups --describe`（位点）+ `/actuator/prometheus`（计数器）+ `kafka-console-consumer.sh`（报文取证）。

### 8.1 前置：新增 topic 与消费组已就位

| topic | 分区 | 来源 |
|---|:---:|---|
| `iot.telemetry` | 3 | 主链路 |
| `iot.telemetry-retry-0` / `-1` / `-2` | 1 each | `@RetryableTopic` 自动创建 |
| `iot.telemetry-dlt` | 1 | 同上（`dltTopicSuffix="-dlt"`） |

消费组 5 个：`iot-telemetry-consumers` + `-retry-0/1/2` + `-dlt`。
**基线（19:48:32）**：retry/dlt 三 topic 的 `LOG-END-OFFSET` 全为 **0**（空置），自定义计数器 `iot_telemetry_process_failures_total` / `iot_telemetry_dlt_total` **尚未注册** —— 干净对照零点。

### 8.2 验收①：永久失败 → 跳级直达 DLT（不进重试阶梯）

样本：未注册设备 `ghost_perm_001`（`POST /api/v1/telemetry` 返回 `{"code":0}`）。

| 观测项 | 基线 | 注入后（+6s） | 判定 |
|---|:---:|:---:|---|
| `iot.telemetry` p1 位点 | 21347 | 21348 | 消息被消费 |
| `iot.telemetry-retry-0/1/2` LOG-END | 0 / 0 / 0 | **0 / 0 / 0** | ✅ 未进阶梯 |
| `iot.telemetry-dlt` LOG-END | 0 | **1** | ✅ 直达死信 |
| `failures{result="permanent"}` | — | **1.0** | ✅ 判定为永久 |
| `dlt_total` | — | **1.0** | ✅ 入账 |

**结论**：`exclude = PermanentTelemetryException` 生效，永久失败被从重试阶梯中摘除。

### 8.3 验收②：瞬时失败 → 走完非阻塞重试阶梯

样本：`dev_demo_001`（哈希落 **partition 2**）+ `probe` 属性值 300 字符 → 触发 MySQL `STRICT_TRANS_TABLES` 的 error 1406（`value VARCHAR(255)`）。

| 采样点 | `retry-0` | `retry-1` | `retry-2` | `dlt` | 解读 |
|---|:---:|:---:|:---:|:---:|---|
| 基线 | 0 | 0 | 0 | 0 | 空 |
| T+2.0s | 1/1 | 1（待消费） | 0 | 1 | 消息已爬到 retry-1 |
| T+7.1s | 1/1 | 1/1 | 1/1 | **2** | 阶梯走完，坠入 DLT |
| T+13.0s / +22.7s | 1/1 | 1/1 | 1/1 | 2 | 稳定收敛 |

计数器：`failures{result="retryable"} = ` **4.0**（= `attempts=4`：1 次初始 + 3 次重试），`dlt_total = 2.0`。

**结论**：瞬时失败进入退避阶梯（1→2→4s），4 次尝试后落 DLT —— 与设计完全一致。

### 8.4 验收③：失败记录不再被「越过」（复刻 BUG-21 案发现场）

同 key（`dev_demo_001` / partition 2）**背靠背**注入「先失败（超长 value）→ 后成功（正常 value）」，正是 §2 中 A 被 B 越过的场景。

| 观测项 | 注入前 | 注入后（+14s） | 判定 |
|---|:---:|:---:|---|
| 主 topic p2 位点 | 5 | **7** | 两条均被消费 |
| **MySQL 成功入库行数** | 0 | **1** | ✅ 成功消息**未被失败的阻挡** |
| `iot.telemetry-dlt` LOG-END | 2 | **3** | ✅ 失败消息**完整留存** |
| `retry-0/1/2` | 1/1/1 | 2/2/2 | 失败消息走完阶梯 |
| `failures{retryable}` | 4.0 | **8.0** | 两次瞬时失败 × 4 次 |
| `dlt_total` | 2.0 | **3.0** | 三笔失败全部对账 |

DLT 内三条原始报文（逐字节完整，可人工重放）：

```
{"ts":null,"properties":{"probe":"PERM_V1"},"device_key":"ghost_perm_001"}
{"ts":null,"properties":{"probe":"RETRY_V1_RRR…(300字符)"},"device_key":"dev_demo_001"}
{"ts":null,"properties":{"probe":"SKIPFAIL_V1_FFF…(300字符)"},"device_key":"dev_demo_001"}
```

**结论**：成功消息正常入库、失败消息安稳落 DLT —— **两者都在，零丢失**。BUG-21 的"越过即蒸发"病象不复现。

### 8.5 判定

| 验收项 | 结果 |
|---|:---:|
| ① 瞬时失败进重试阶梯 | ✅ |
| ② 永久失败跳级直达 DLT | ✅ |
| ③ 失败记录不再被静默越过 | ✅ |

> **BUG-21（静默丢数据，Blocker）判定为结构性闭合。**
> 修复前：失败记录被后一条成功消息的 ack 越过 → 永不重试、无死信、不可追溯（实测 partition 2 位点 2→4 直接跳过）。
> 修复后：失败消息**离开主分区**（retry topic），主分区只提交已妥善转交/成功的记录 → 既不卡住也不越过；配合 D2-1 唯一键幂等，「至少一次投递 + 幂等消费 = 不丢不重」闭环成立。
>
> 附：本轮验证中 `iot_telemetry_rejected_total = 0`、`iot_telemetry_queue_pending = 0` —— 注入量远未触及背压阈值，背压路径本身待 V2-C 规模化压测再压。

---

## 附：测试工装教训（重要）

用 PowerShell 5.1 向 `curl.exe` 传含双引号的 JSON 实参时，**引号会被吞掉** → 请求体损坏 →
`GlobalExceptionHandler` 的 `@ExceptionHandler(Exception.class)` 兜底把它包成
`{"code":5000,"message":"服务内部错误"}`（全项目统一 5000），一度制造"后端有 bug"的**假象**，浪费两轮排查。

✅ 正确姿势：把 JSON 写入文件，用 `curl.exe --data-binary "@file.json"` 传文件体。
（同一 payload 立即返回 `{"code":0,"message":"ok"}`，且遥测正常落库。）

### 第二个坑：环境代理劫持 localhost 请求

宿主机 shell 里挂着 `HTTP_PROXY=http://127.0.0.1:51792`（`http_proxy` 同值），curl 默认会走它，
于是访问 `localhost:8080` 也绕一圈 —— 后端已停时返回 **502 Bad Gateway**，而不是"连接被拒"，
极易误判为"服务有异常"。

✅ 正确姿势：`curl.exe --noproxy "*" http://localhost:8080/...`（直连，服务停时返回 `000`）。

### 第三个坑：杀进程前先看命令行

排查端口占用时发现两个 `java.exe`，差点当占用者处理。查 `Win32_Process.CommandLine` 才知：
一个是 IDEA 的 Maven embedder、一个是 IDEA 的编译进程 —— **都是 IDE 自身组件，杀掉会破坏 IDE 状态**。
占用 8080 的真凶另有其人（后端实例本身）。**端口排障口诀：先 `Get-NetTCPConnection` 拿 OwningProcess，再看 CommandLine 确认身份，最后才动手。**

