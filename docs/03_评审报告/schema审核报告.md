# db/schema.sql 审核报告

| 项目 | 内容 |
|------|------|
| 审核对象 | `db/schema.sql`（v1.0，2026-08-30，7 张表） |
| 审核依据 | 《物联网PaaS云平台需求分析文档》V1.1、《项目任务清单》任务③、本机实际环境 |
| 审核日期 | 2026-08-30 |
| 审核结论 | **整体结构合格，可签「数据合同」；发现 2 个 P0、4 个 P1、5 个 P2 记录项** |

---

## 一、总评

schema.sql 的整体设计质量良好：7 张表覆盖了「设备接入 → 遥测存储 → 指令追踪 → 账号权限」完整链路，鉴权三元组、分区策略、幂等设计等关键决策与需求文档 V1.1 一致。存在的主要问题是**1 处会误导运维的事实性错误**、**任务清单明确要求的软删约定缺失**，以及若干一致性问题。均为小改动，不影响表结构骨架。

## 二、通过项（审核确认无问题）

1. **分区表主键合规**：`device_telemetry` 主键 `(id, ts)` 含分区键 ts，满足 MySQL 分区表硬性要求；分区覆盖 2026-08 ~ 2027-07 共 12 个月 + pmax，当前月起始正确。
2. **鉴权三元组设计正确**：product_key + device_name + device_secret 一机一密，MQTT clientId=username=device_key、password=device_secret，与 EMQX HTTP 认证链路（查 Redis 比对）对齐。
3. **command 幂等与状态机**：`request_id` 唯一键防重复下发，状态机 0待下发/1已下发/2已执行/3失败/4超时 覆盖下发全生命周期。
4. **逻辑外键策略合理**：高频上报场景下避免物理外键行锁竞争，用「逻辑外键 + 索引 + 应用层保证」替代，性能优先，符合 IoT 场景惯例。
5. **索引覆盖主查询模式**：遥测 `idx_device_ts`（最新数据）、`idx_device_prop_ts`（属性曲线）、日志 `idx_device_time`、指令 `idx_device_status_time`，与典型查询路径匹配。
6. **字段语义正确**：BCrypt VARCHAR(100)（实际 60 字符）足够；utf8mb4 下各唯一索引长度均未超限；DATETIME(3) 毫秒精度 + ON UPDATE 语义正确；JSON 类型用于 tsl/payload 合理。

## 三、P0 · 必须修（不修会直接出错或违反任务要求）

### P0-1 分区滚月说明错误（设计说明第 3 条）

- **现象**：注释写「每月需 ALTER TABLE ADD PARTITION 或配置事件自动加分区」。
- **问题**：表已存在 `pmax VALUES LESS THAN (MAXVALUE)` 封顶分区，此时 `ADD PARTITION` 会因边界值必须严格递增而**直接报错**。照注释运维必踩坑。
- **正确做法**：滚月必须用 REORGANIZE 拆分 pmax：
  ```sql
  ALTER TABLE device_telemetry REORGANIZE PARTITION pmax INTO (
    PARTITION p202708 VALUES LESS THAN ('2027-09-01'),
    PARTITION pmax    VALUES LESS THAN (MAXVALUE));
  ```
- **建议**：更正设计说明第 3 条，附上述模板语句，并注明建议用 MySQL EVENT 或后端定时任务每月 1 号自动执行。

### P0-2 软删约定全库缺失

- **现象**：7 张表均无 `deleted` 字段，设计说明也未提及软删策略。
- **问题**：任务清单③明确要求 schema「含索引、device_telemetry 时间分区、**外键/软删约定**」——外键策略有（逻辑外键），软删约定完全没有。后期用 DELETE 物理删除会破坏数据可追溯性，且 MyBatis-Plus 需要该约定才能配 @TableLogic。
- **建议**：
  - 业务实体表（product / device / user / project）加 `deleted TINYINT NOT NULL DEFAULT 0`（0 未删 1 已删，对齐 MyBatis-Plus @TableLogic）；
  - 事件流表（device_telemetry / device_log / command）在设计说明写明「只增不改不删，靠分区/归档清理」。

## 四、P1 · 建议修（一致性与可运维性）

### P1-3 command 表缺 `updated_at`

其余业务表均有 updated_at，command 表状态机 0→1→2 变更却无审计时间。建议补 `updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)`。

### P1-4 command 表超时扫描无索引

超时任务查询模式是 `status=1 AND sent_at < now`（不带 device_id），现有 `idx_device_status_time` 以 device_id 为前缀，该查询用不上，会全表扫。建议补 `KEY idx_status_sent (status, sent_at)`。

### P1-5 项目归属不一致（隔离边界断链）

device 有 `project_id` 而 product 没有——「单租户多项目」的资源隔离在产品层断链：能查「项目下的设备」，却查不了「项目下的产品」。建议 product 补 `project_id`（可空）+ 普通索引，应用层保证 device 与其所属 product 的 project_id 一致。（反向方案：MVP 不做隔离，把 device.project_id 一并去掉——二选一。）

### P1-6 value_type 默认值不当

`device_telemetry.value_type` 默认 2（字符串），但主场景温度上报是数值（类型 1）。建议默认值改 1。

## 五、P2 · 记录不修（避免后期反复讨论）

1. **版本声明与环境不符**：文件头部声明 MySQL 8.0，本机实跑 MySQL 5.7（MySQL57 服务运行中，数据目录已迁 D:\AppData\MySQLData），Docker 未安装。当前 DDL 双兼容（JSON 类型需 5.7.8+），建议头部注明兼容范围，环境选型（本地 5.7 vs Docker 8.0）留给任务①定稿。
2. **PK (id, ts) 的已知权衡**：仅按 id 查询无分区裁剪、id 唯一性仅靠自增保证——但遥测查询模式均带 device_id+ts，可接受。
3. **value VARCHAR(255) 上限**：超长属性值（Base64/长 JSON）会插入失败，MVP 属性（温度/开关）够用。
4. **tags 用 VARCHAR 存 JSON 数组**，与 tsl/payload 的 JSON 类型不一致——MVP 可接受，V2 统一为 JSON 类型。
5. **遥测 ts 取设备侧时间**：设备时钟漂移会落错分区；MVP 设备走 SNTP 可接受，暂不加 received_at（不过早加列）。

## 六、待决策菜单（拍板后另行执行）

- [ ] P0-1 分区说明更正（事实性错误，建议必做）
- [ ] P0-2 软删约定：四表全加 / 仅 product+device / 不做
- [ ] P1-3 command 补 updated_at
- [ ] P1-4 command 补超时扫描索引
- [ ] P1-5 project_id：product 补列 / 去掉 device 侧 / 维持现状
- [ ] P1-6 value_type 默认值改 1
- [ ] 配套：seed.sql（演示产品+设备，D3 鉴权联调即需）
- [ ] 配套：verify.sql（执行验证清单：SHOW TABLES / 分区裁剪 EXPLAIN / 索引核对）
- [ ] 落盘：BUG_LOG.md 记录、任务清单③状态更新

## 七、后续建议

1. **修复后执行验证**：schema.sql 在目标库执行后，用 `EXPLAIN PARTITIONS SELECT ... WHERE device_id=? AND ts BETWEEN ...` 验证分区裁剪（期望命中单分区）。
2. **seed 数据**：建议建 `db/seed.sql` 预置 1 条演示产品（含 temperature/switch 物模型）+ 1 条演示设备（device_key/device_secret），D3 用 MQTTX 调鉴权时即需；user 管理员账号不预置（BCrypt 哈希无法离线生成有效值，首次走接口创建）。
3. **分区滚月自动化**：修复 P0-1 后，建议顺手在 D11 收尾阶段配 MySQL EVENT 或后端定时任务，避免忘记滚月导致数据全进 pmax。
4. **版本定调**：本机 5.7 与 Docker 8.0 二选一尽快定（影响 docker-compose.yml 任务①），定稿后统一 schema 头部声明。
