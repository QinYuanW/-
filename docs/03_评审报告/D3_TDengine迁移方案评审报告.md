# D3 · TDengine 迁移方案评审报告

| 项目 | 内容 |
|------|------|
| 审核对象 | D3「TDengine 迁移 · 超级表/子表设计落地，历史数据双写灰度」**设计方案**（尚未实现） |
| 审核依据 | 《物联网PaaS云平台需求分析文档》§7.3 / §6 性能指标；《项目任务清单》D3-D5；C4《MySQL 时序瓶颈分析报告》；D1/D2 现状代码 |
| 审核日期 | 2026-09-11 |
| 目标版本 | TDengine TSDB **3.3.8.0**（需求 §技术选型定 3.3.x；2025-10 稳定版，镜像 `tdengine/tdengine:3.3.8.0`） |
| 审核结论 | **方案可行，可进入实施；但有 1 个 P0 语义冲突必须先拍板** —— MySQL `uk_device_prop_val_ts` 的「同 ts 不同 value 共存」在 TDengine 侧**物理上无法表达**（同子表 ts 唯一 + v3 upsert 覆盖）。另有 3 个 P1、4 个 P2 记录项 |

---

## 一、总评

TDengine 建模与本项目的读写模式**高度契合**：现有两条读路径（取属性最新值、按属性查区间曲线）恰好是 TDengine 的 `LAST()` 与主键区间扫描两大强项；D1 已把写入收敛为「单 topic 顺序消费」，D3 只需在消费侧挂一条旁路写。

真正的风险**不在性能**（TDengine 写吞吐远超 C4 实测的 971 行/s 天花板），而在**语义**：D2 为了修 BUG-20 给 MySQL 唯一键加了 `value` 列，这个设计在 TDengine 的「(子表, ts) 主键唯一」模型下**没有对应物**。这是本次评审必须让汤园拍板的唯一 P0。

其余为工程细节（双写隔离、子表创建、时区/精度、保留策略），均有成熟做法。

---

## 二、现状取证（设计的事实基础）

### 2.1 写路径（唯一入口，已收敛）

```
EMQX 规则 → POST /api/v1/telemetry → Kafka(iot.telemetry, key=device_key)
   → 消费者组 iot-telemetry-consumers → TelemetryServiceImpl.processSync
        ├─ resolveDeviceId(device_key)      # Redis 缓存 did:{key} → device_id
        ├─ insertBatch(telemList)           # 一条 multi-values INSERT + ON DUPLICATE KEY UPDATE id=id
        └─ refreshOnline(deviceId)          # 5s 窗口聚合刷 device 表（**不属遥测，留 MySQL**）
```

- 一次上报（同一 ts、多个属性）→ 拼成 **1 条 multi-values INSERT，N 行**（N = 属性数）。
- 幂等靠 `uk_device_prop_val_ts (device_id, property_key, value, ts)`。
- D2-2 起失败走 `-retry-0/1/2` 阶梯 → `iot.telemetry-dlt`。

### 2.2 读路径（仅两处，切换面很小）

| # | 方法 | MySQL 实现 | 调用方 |
|---|---|---|---|
| R1 | `DeviceServiceImpl.getLatestTelemetry` | `selectLatestByDevice`：`INNER JOIN (SELECT property_key, MAX(ts) ... GROUP BY property_key)` | 前端 TSL 最新值 |
| R2 | `DeviceServiceImpl.getTelemetryHistory` | `selectList`：`device_id=? AND property_key=? AND ts BETWEEN ? AND ? ORDER BY ts` | 前端历史曲线 |

> 全部遥测读仅这两个方法，且都由 `DeviceServiceImpl` 调用 —— **读切换的爆炸半径可控**。

### 2.3 表结构与数据量（2026-09-11 实测）

| 项 | 值 |
|---|---|
| 表 | `device_telemetry(id, device_id, property_key, value VARCHAR(255), value_type TINYINT, ts DATETIME(3))` |
| 主键 / 分区 | `(id, ts)` + 按月 RANGE COLUMNS 分区（12 月 + pmax） |
| 唯一键 | `uk_device_prop_val_ts (device_id, property_key, value, ts)` |
| 索引 | `idx_device_ts`、`idx_device_prop_ts` |
| **当前行数** | **446,398** |
| 设备数 | ~1,250（含压测 flood 设备） |
| C4 实测天花板 | 稳态消化 **≈ 98~100 msg/s（≈ 300 行/s）**（应用侧修复前） |
| 应用侧修复后 | **≈ 971 行/s**（毕业论文 §5 引用值） |
| 需求目标 | ≥ **1000 条/秒**；压缩比 ~10:1（需求文档）／厂商宣称最高 30:1（须实测，D5 产出） |

### 2.4 立项依据已齐（D3 不是拍脑袋）

- **C4《MySQL 时序瓶颈分析报告》**：证明「单节点 MySQL 单行插入先于裸 MySQL 极限到来」→ 支撑迁移 TDengine。
- **C5《Webhook 直推可靠性边界分析》**：支撑 D1 Kafka。
- 需求 §7.3：明确 MVP MySQL → V2+ TDengine，含「超级表/子表 + TTL + 降采样」。

---

## 三、关键设计决策（Decision Points）

### DP-1 🔴 建模粒度：子表怎么切？（**本方案的地基**）

TDengine 的硬约束：**同一张子表内 `ts` 必须唯一**（v3 写重复 ts = upsert 覆盖，见 §附·事实核验）。

| 方案 | 结构 | 致命问题 |
|---|---|---|
| A. 一设备一子表 | tag=device_id，value 做成**多列**（每属性一列） | 属性是 TSL 动态定义的，**每加一个属性就要 ALTER 加列** → 通用 PaaS 不可行；且同 ts 多属性写一行，属性集变化即列爆炸 |
| **B. 一设备一属性一子表** ✅ | tag=`(device_id, property_key)`，列=`(ts, value, value_type)` | 无 —— 子表数 = 设备数 × 属性数（1,250 × ~5 ≈ 6,000，TDengine 撑百万级子表无压力） |
| C. 一设备一属性一普通表（不用超级表） | 无 tag | 丧失「超级表按 tag 聚合/跨设备查询」能力，且元数据膨胀 |

> **推荐 B**。它同时让两条读路径都退化为**单子表内的主键扫描**：
> R1 → `SELECT property_key, LAST(value) FROM stb WHERE device_id=? GROUP BY property_key`
> R2 → `SELECT ts, value FROM stb WHERE device_id=? AND property_key=? AND ts BETWEEN ? AND ? ORDER BY ts`

### DP-2 🟡 `value` 存成字符串还是数值列？

现有 `value VARCHAR(255)` 是 MySQL 设计的产物（配合 `value_type` 保留原始类型）。直接照搬到 TDengine：

| 方案 | 优点 | 缺点 |
|---|---|---|
| B1. 单列 `value VARCHAR(255)` | 与 MySQL 1:1 对齐，映射简单，任意类型都能存 | **数值被当字符串存 → 压缩比与聚合性能双输**（遥测 90% 是数值） |
| B2. `value_num DOUBLE + value_str VARCHAR(255) + value_type` | 数值走列式压缩（压缩比主要靠它），聚合直接可用 | 多一列；写入时按 `value_type` 分流 |

> **推荐 B2**。D5 的核心指标之一就是压缩比，用 B1 等于自废武功。B2 保留 `value_type` 做语义标识，与 MySQL 侧仍可无损比对（D4）。

### DP-3 🔴 双写方式：同一消费者内旁路 vs 独立消费者组扇出

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. 同消费者内旁路写** ✅ | `processSync` 内 MySQL 写成功后，`try{ tdWriter.write() }catch{ 只计数 }` | 与 MySQL **1:1 对应**，D4 比对干净；无额外 consumer group | 双写在同一线程，TDengine 慢会拖慢 MySQL 路径（需异步/批量缓冲） |
| B. 独立消费者组扇出 | 新增 `iot-telemetry-tdengine-consumers` 专写 TDengine | 完全解耦，可独立扩缩 | 双倍 Kafka 消费；两组 at-least-once 进度不同 → D4 比对需容忍时间窗；运维面变大 |

> **推荐 A（灰度期）**，并强制两条纪律：
> 1. **旁路写异常绝不外抛**（否则 TDengine 抖动 → 遥测被 retry/DLT，**侧库污染主链路**，违反 D2-2 刚立的止损原则）；
> 2. 旁路写**只计失败数不阻塞**（`iot_tdengine_shadow_write_failures_total`），必要时降级为「异步缓冲 + 批量 flush」。
>
> B 作为 V2-G 规模化部署阶段的终态备选（写入侧独立伸缩）。

### DP-4 子表创建与命名

- **命名**：`property_key` 可能含非法标识符字符 → **不做可读命名**，用确定性哈希 `t_{deviceId}_{md5(propertyKey)[0:8]}`；反正**查询一律走超级表 + tag 过滤**，子表名无需人读。
- **创建**：设备/属性首次出现时**惰性创建**：
  `CREATE TABLE IF NOT EXISTS <sub> USING <stb> TAGS(<deviceId>, '<propertyKey>')`
  然后写入。禁止依赖「写入自动建子表」的隐式行为（版本间语义有差异，显式更可控）。
- 需新增一张轻量登记表（`device_property_index`：device_id + property_key + sub_table_name）**或**用 Redis 缓存该映射 —— 属 P2，可后置。

### DP-5 保留策略与降采样

| 项 | 建议 |
|---|---|
| 保留 | `KEEP 365`（对齐产品「历史数据保留 1 年」）。**用 KEEP 而非周期 DELETE** —— KEEP 在文件级删除、无 Tombstone 堆积；DELETE 必须带 ts 时间窗且长期堆积拖慢查询 |
| 降采样 | 3.3.8 自带 `SMA/ROLLUP` 预计算与流计算。**V2-D 阶段先不做**，仅在 D5 报告中列为「后续可优化点」（避免范围蔓延，降采样属 E/G 阶段能力） |
| 精度 | `PRECISION 'ms'`（与 MySQL `DATETIME(3)` 对齐，D4 才可比） |

### DP-6 读切换策略

- **不切换**：D3 只建影子写，**读全部仍走 MySQL**（R1/R2 不动）。
- D4 完成一致性校验后，**只切 R2（历史区间）**，R1（最新值）**继续走 MySQL** —— 因为前端 5s 轮询依赖最新值，MySQL + Redis 的延迟确定性更高；且最新值查询在 TDengine 上收益有限。
- 每一步切换都保留 **feature flag 一键回退**。

---

## 四、目标模型草案（DDL 提案，待审）

```sql
-- 库：ms 精度对齐 MySQL DATETIME(3)；保留 1 年；3 副本单机退化为 1
CREATE DATABASE IF NOT EXISTS iot_tsdb
  PRECISION 'ms'
  KEEP 365
  DURATION 10
  VGROUPS 4
  WAL_LEVEL 1;

-- 超级表：标签 (device_id, property_key)；列 (ts, value_num, value_str, value_type) —— 对应 DP-2/B2
CREATE STABLE IF NOT EXISTS iot_tsdb.telemetry (
  ts         TIMESTAMP,
  value_num  DOUBLE,
  value_str  VARCHAR(255),
  value_type TINYINT
) TAGS (
  device_id    BIGINT,
  property_key VARCHAR(64)
);

-- 子表（示例）：一设备一属性一子表，惰性创建
CREATE TABLE IF NOT EXISTS iot_tsdb.t_1_9f3a2c10
  USING iot_tsdb.telemetry
  TAGS (1, 'temperature');
```

**写入（当前消费者内旁路）**

```sql
-- 一条上报 N 个属性 → 一条 SQL 写 N 个子表（TDengine 支持多表同语句写入）
INSERT INTO iot_tsdb.t_1_9f3a2c10 (ts, value_num, value_type) VALUES (1757500000000, 26.5, 1)
           iot_tsdb.t_1_4b2e77d1 (ts, value_str, value_type) VALUES (1757500000000, 'on', 2);
```

**读取（与 R1/R2 对应）**

```sql
-- R1 最新值（按属性分组取最后一行）
SELECT property_key, LAST(value_num) AS v, LAST(value_str) AS s, LAST(value_type) AS t
FROM iot_tsdb.telemetry WHERE device_id = 1 GROUP BY property_key;

-- R2 历史区间（tag 过滤 → 裁剪到单子表，主键区间扫描）
SELECT ts, value_num, value_str, value_type
FROM iot_tsdb.telemetry
WHERE device_id = 1 AND property_key = 'temperature' AND ts >= ? AND ts <= ?
ORDER BY ts;
```

---

## 五、双写灰度路线（四阶段，每阶段可独立回退）

```
阶段 0  建库/超级表 + taos-jdbcdriver(REST) 接入 + 影子写开关(默认关)
   │     验收：健康检查含 TDengine；开关打开时本地能写通
   ▼
阶段 1  影子写（Shadow Write）—— 主链路仍只认 MySQL
   │     · 旁路写 TDengine，异常只计数、绝不上抛（DP-3 纪律①）
   │     · 观测：shadow_write_failures_total / shadow_write_latency
   │     · 验收：连续 24h 影子写失败率 ≈ 0；MySQL 路径 P95 无回归
   ▼
阶段 2  一致性校验（= D4）
   │     · 按 (device_id, property_key, ts) 抽样比对行数与值
   │     · 明确**已知差异白名单**：同 ts 不同 value 的覆盖（见 P0-1）
   │     · 验收：抽检差异率 0（白名单除外）
   ▼
阶段 3  读切换（灰度）
   │     · 先切 R2 历史区间（flag: tdengine.read.history.enabled）
   │     · R1 最新值保持 MySQL
   │     · 验收：曲线接口 RT 与数据一致性无劣化
   ▼
阶段 4  MySQL 遥测停写（可选，答辩非必须）
         · 保留表结构与数据做归档，不建议物理删除
```

**开关清单（全部默认 false，配置在 `application.yml`）**

| 开关 | 作用 | 默认 |
|---|---|---|
| `tdengine.shadow.enabled` | 启用旁路影子写 | false |
| `tdengine.read.history.enabled` | R2 历史查询走 TDengine | false |
| `tdengine.read.latest.enabled` | R1 最新值走 TDengine（暂不开放） | false |

---

## 六、风险与问题清单

### 🔴 P0（必须先拍板，否则返工）

**P0-1 同 ts 不同 value 的语义冲突（本方案唯一阻塞项）**

- **现象**：MySQL 唯一键含 `value`，允许「同 device+property+同 ms ts、不同 value」**共存两行**（BUG-20 的修复前提）。TDengine 同一子表 **ts 唯一**，v3 重复 ts 写入 = **upsert 覆盖**。
- **后果**：QoS0 突发下同毫秒不同值的两笔上报，在 TDengine 侧**只留最后一笔** → **BUG-20 的保证在 TDengine 侧不成立**；D4 比对会出现差异。
- **两个选项**：
  - **选项 ①（推荐）接受覆盖语义，显式降级并记录**：物理上「同设备同属性同毫秒出现两个不同值」本就是设备端时间戳分辨率不足的退化情形；TDengine 采用 last-wins。**要求**：在 D4 白名单中显式登记该差异类别，并在论文/答辩口径中说明「迁移后该边界的语义变化」。
  - **选项 ② 提高精度消歧**：库精度设为 `us`/`ns`，ts 由 `设备ms × 1000 + 序号` 生成 → 彻底避免碰撞。代价：需引入每设备的单调序号（或 ms 内计数器），且与 MySQL 的 `DATETIME(3)` 比对需还原，D4 复杂度上升。
- **➡️ 待汤园决策**。

**P0-2 旁路写必须隔离（设计纪律，不是可选项）**

- TDengine 任何异常**不得**让遥测进入 retry/DLT。若双写耦合，TDengine 抖动会污染主链路 —— 这是 D2-2 刚止血的同类错误。
- **要求**：`try/catch` 包裹 + 独立计数器 + 严禁 rethrow；且旁路写超时上限（建议 ≤ 20ms）不得拖累 MySQL 路径。

### 🟡 P1

| # | 问题 | 建议 |
|---|---|---|
| P1-1 | **时区/精度错位** | MySQL 侧 `LocalDateTime`(Asia/Shanghai)，TDengine 用 epoch。**统一以 epoch millis 传参**；JDBC 显式设时区；D4 比对前先对齐 |
| P1-2 | **实体复用风险** | `DeviceTelemetry` 实体含自增 `id`（分区表产物），TDengine 无此列。**新建独立 TDengine 写入模型，勿复用实体**，避免 MyBatis-Plus 误映射 |
| P1-3 | **compression 基线未测得** | D5 需要「MySQL 行/s → TDengine 行/s + 压缩比」。要求先跑一次**分轮独立压测**（先只写 MySQL 取基线、再只写 TDengine），**不可同时双写取数**（互相干扰） |

### 🟢 P2

| # | 问题 | 建议 |
|---|---|---|
| P2-1 | 子表名映射登记 | 可后置；先用「查询一律走超级表」规避 |
| P2-2 | 降采样/SMA | V2-D 不做，记入 D5「后续优化点」 |
| P2-3 | 批量积压与顺序 | 旁路写若引入异步缓冲，注意多属性同 ts 的批内顺序（同子表 ts 递增才高效） |
| P2-4 | TDengine 纳入 docker-compose 后的资源开销 | 单机 3 节点全栈内存占用需观测；建议单独 limit |

---

## 七、验收标准（D3 / D4 / D5）

| 任务 | 完成标志（可取证） |
|---|---|
| **D3** | ① `/actuator/health` 含 TDengine UP；② 影子写开关打开后，随机 100 台设备 5 分钟上报，TDengine 侧按 (device_id, property_key, ts) 计数与 MySQL **一致**；③ 关闭开关后 MySQL 路径 P95 **无回归**（对比开启前） |
| **D4** | 抽样（≥1,000 行 / ≥10 台设备）逐行比对，差异率为 0（P0-1 白名单除外）；输出《一致性校验报告》 |
| **D5** | **本阶段最核心产出**：同一数据集下三组对比 —— **写入吞吐（行/s）**、**压缩比（x:1）**、**查询 RT（P50/P95，R1/R2 两条路径）**，成文《MySQL vs TDengine 性能对比报告》 |

---

## 八、依赖、排期与待确认问题

**依赖顺序**：D2 ✅（已闭合）→ **D3** → D4 → D5。D3 与 E/F 阶段正交，可并行。

**建议拆解（4 步）**：T1 基础设施（compose + 驱动 + 健康检查）→ T2 模型落地（DDL + 写入模型）→ T3 影子写 + 观测 → T4 灰度开关 + 一致性抽样脚本。

### ❓ 待汤园拍板（开工前必须回答）

1. **P0-1 选哪个选项？**（① 接受覆盖 + D4 白名单登记 ／ ② 提 us 精度 + 序号消歧）
2. **DP-2 的 `value` 方案**：B1 单字符串列（简单、压缩差）／ **B2 数值列+字符串列（推荐）**？
3. **DP-3 双写方式**：**A 同消费者旁路（推荐，灰度期）** ／ B 独立消费者组扇出？
4. **目标镜像版本**：`tdengine/tdengine:3.3.8.0` 是否确认？

---

## 附 · 关键事实核验（本报告技术断言来源）

| 断言 | 来源 |
|---|---|
| 同一子表内 ts 必须唯一；v3 起 `CREATE DATABASE UPDATE 0/1/2` 选项**已废除**，v3 统一支持同 ts 部分列覆盖（Upsert） | TDengine 官方文档《数据写入、更新与删除》(3.4.0)、社区深度文《TDengine 数据更新与删除》(v3.3.x/3.4.x) |
| 2.x 的「UPDATE=0 直接抛弃 / UPDATE=1 覆盖」语义 | TDengine 官方文档 2.4《Insert Using SQL》 |
| `KEEP` 文件级过期优于手工 DELETE（免 Tombstone 堆积）；DELETE 必须带 ts 时间窗 | 同上社区文 §8 / §1 |
| 支持 `INSERT INTO stb (tbname, ts, ...) VALUES(...)` 一次多表写入 | TDengine 官方文档 3.4.0 / 2.4 |
| 单条 ≤ 48K、单条 SQL ≤ 1M；批量写入显著提效 | TDengine 官方文档 |
| JDBC REST 连接串 `jdbc:TAOS-RS://localhost:6041?user=root&password=taosdata` | TDengine 官方文档 2.4 Java 示例 |
| 最新稳定版 3.3.8.0（2025-10）；镜像 `tdengine/tdengine:3.3.8.0`；端口 6030(原生)/6041(REST)/6043(metrics) | 涛思官方发布说明 3.3.8.0 |
| 厂商宣称压缩比最高 30:1（**本项目以实测为准**，需求文档按 ~10:1 估算） | 涛思公开材料（2025-11） |

---

> **一句话结论**：D3 的**建模骨架（DP-1 一设备一属性一子表）已可定稿**，双写灰度路线清晰且每一步可回退；
> 唯一必须先解决的是 **P0-1 的 ts 语义降级决策** —— 它决定 D4 的比对口径，晚定会返工。
