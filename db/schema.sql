-- ============================================================
-- 物联网 PaaS 云平台 · 数据库表结构
-- 数据库：iot_platform   版本：v1.3   日期：2026-09-17
-- 引擎：MySQL 5.7.8+（兼容 8.0）   InnoDB   字符集：utf8mb4
--
-- 本文件是「设备 → 平台 → 控制台」全链路的数据合同：
--   所有接口入参出参、EMQX 回调报文、Webhook 解析逻辑都以此为准。
-- 先签合同，再写代码。
--
-- ★ 本文件是**唯一**的建表定义源（single source of truth）。
--   2026-09-17「DDL 归位」：原 `db/create_all.sql`、`db/run.sql`、`db/temp_device.sql`
--   都是 device 等表的残缺副本（漏列、漏表、device_secret 仍为 64），已全部删除，
--   内容合并至此，避免"多份 DDL 互相竞争事实源"。
--
-- ⚠️ 兼容性提示：本文件使用 JSON 类型与 RANGE COLUMNS 分区，需 MySQL ≥ 5.7.8。
--   若版本更低（极少见）：JSON 字段改 TEXT，并去掉 device_telemetry 的分区子句。
--
-- 变更历史
--   v1.0 → v1.2（依据《schema 审核报告》）
--     [P0-1] 分区滚月说明改为 REORGANIZE 拆 pmax（ADD PARTITION 会报错）
--     [P0-2] product/device/user/project 四表补 deleted 软删字段
--     [P1-3] command 补 updated_at
--     [P1-4] command 补超时扫描索引 (status, sent_at)
--     [P1-5] product 补 project_id（隔离边界完整）
--     [P1-6] device_telemetry.value_type 默认值 2 → 1（主场景为数值型温度）
--   v1.2 → v1.3（2026-09-17）
--     [BUG-20] device_telemetry 幂等唯一键 → uk_device_prop_val_ts（含 value，
--              否则 QoS0 突发共享同一毫秒 ts 会被误判为"同一上报"而折叠）
--     [BUG-28] device.device_secret VARCHAR(64) → VARCHAR(128)
--              （F3 起存 AES-256-GCM 密文，83 字符装不下 64）
--     [E3] 补 device_rule / device_alarm / device_event
--     [E4] 补 device_group + device.group_id / device.tags
--     [补录] 表注释与列注释（真实库因早期建表方式丢失了这些注释，见文末 §10）
--
-- 用法：mysql -u <user> -p < db/schema.sql
--   已存在的表会被跳过（IF NOT EXISTS），所以**重复执行不报错**——
--   也正因如此，它不能用来判断"现有库的结构对不对"，那件事交给 `db/verify.sql`。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `iot_platform`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE `iot_platform`;

-- ============================================================
-- 一、账号与资源边界
-- ============================================================

-- ============================================================
-- 1. project 项目表
--    「组织 → 项目 → 资源」里的中间层：product / device 各带 project_id 指向它，
--    构成 F4 多租户隔离的边界。MVP 只有单租户，但表先建好避免后期 ALTER。
-- ============================================================
CREATE TABLE IF NOT EXISTS `project` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(64)     NOT NULL                COMMENT '项目名称',
  `description` VARCHAR(255)    NULL                    COMMENT '项目描述',
  `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '软删：0未删 1已删（对齐 MyBatis-Plus @TableLogic）',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目表：单租户多项目，资源隔离边界';

-- ============================================================
-- 2. user 用户表
--    控制台账号（JWT 登录）。password_hash 存 BCrypt，禁止明文。
--    role 是三级 RBAC 的雏形；细粒度权限（F4 之后）留长期。
-- ============================================================
CREATE TABLE IF NOT EXISTS `user` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username`      VARCHAR(64)     NOT NULL                COMMENT '用户名',
  `password_hash` VARCHAR(100)    NOT NULL                COMMENT 'BCrypt 哈希（禁止明文）',
  `role`          TINYINT         NOT NULL DEFAULT 2      COMMENT '角色：1管理员 2开发者 3观察者',
  `status`        TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1启用 0禁用',
  `deleted`       TINYINT         NOT NULL DEFAULT 0      COMMENT '软删：0未删 1已删（对齐 MyBatis-Plus @TableLogic）',
  `created_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`    DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表：JWT 登录，三级 RBAC';

-- ============================================================
-- 二、物模型与设备
-- ============================================================

-- ============================================================
-- 3. product 产品表
--    物模型（TSL）的入口：一个产品 = 一类设备 = 一份物模型。
--    tsl 里的 properties[].access 决定该属性可否被下发（readwrite），
--    「告警规则」页的联动下发属性下拉、调试面板的可写属性列表都源自这里。
-- ============================================================
CREATE TABLE IF NOT EXISTS `product` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `product_key` VARCHAR(32)     NOT NULL                COMMENT '产品唯一标识（对外，如 p_demo_001）',
  `name`        VARCHAR(64)     NOT NULL                COMMENT '产品名称',
  `project_id`  BIGINT UNSIGNED NULL                    COMMENT '所属项目（逻辑外键 → project.id）',
  `node_type`   TINYINT         NOT NULL DEFAULT 1      COMMENT '节点类型：1直连 2网关 3子设备',
  `protocol`    VARCHAR(16)     NOT NULL DEFAULT 'MQTT' COMMENT '接入协议（MVP 固定 MQTT）',
  `data_format` VARCHAR(16)     NOT NULL DEFAULT 'JSON' COMMENT '数据格式（MVP 固定 JSON）',
  `tsl`         JSON            NULL                    COMMENT '物模型定义：{properties:[],services:[],events:[]}',
  `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1启用 0停用（F5 停用后其下设备拒绝接入）',
  `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '软删：0未删 1已删（对齐 MyBatis-Plus @TableLogic）',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_key` (`product_key`),
  KEY `idx_project` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='产品表：物模型入口，一产品多设备';

-- ============================================================
-- 4. device 设备表
--    一机一密三元组：product_key + device_name + device_secret
--    device_key 是设备接入唯一标识（MQTT 的 clientId / username）
--    status（F5 启停）与「所属产品 status」是**两层独立**的开关：
--    接口层实时合成 device.status && product.status，不批量改写子设备，
--    这样产品恢复后各设备原状态能准确复现。
-- ============================================================
CREATE TABLE IF NOT EXISTS `device` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_key`      VARCHAR(64)     NOT NULL                COMMENT '设备唯一标识（MQTT clientId/username）',
  `product_key`     VARCHAR(32)     NOT NULL                COMMENT '所属产品标识',
  `device_name`     VARCHAR(64)     NOT NULL                COMMENT '设备名称（产品内友好名）',
  `device_secret`   VARCHAR(128)    NOT NULL                COMMENT '设备密钥（MQTT password）。F3 起为 AES-256-GCM 密文（v1: 前缀），明文仅注册响应一次性返回',
  `project_id`      BIGINT UNSIGNED NULL                    COMMENT '所属项目（逻辑外键 → project.id）',
  `online_status`   TINYINT         NOT NULL DEFAULT 0      COMMENT '在线状态：0离线 1在线',
  `last_online_at`  DATETIME(3)     NULL                    COMMENT '最近上线时间',
  `last_offline_at` DATETIME(3)     NULL                    COMMENT '最近离线时间',
  `last_ip`         VARCHAR(45)     NULL                    COMMENT '最近接入 IP',
  `group_id`        BIGINT UNSIGNED NULL                    COMMENT '所属设备分组（E4，逻辑外键 → device_group.id；NULL=未分组）',
  `tags`            VARCHAR(255)    NULL                    COMMENT '标签（JSON 数组）',
  `status`          TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1启用 0停用（F5 停用即拒绝 MQTT 接入，数据全保留）',
  `deleted`         TINYINT         NOT NULL DEFAULT 0      COMMENT '软删：0未删 1已删（对齐 MyBatis-Plus @TableLogic）',
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_key` (`device_key`),
  UNIQUE KEY `uk_product_name` (`product_key`, `device_name`),
  KEY `idx_project` (`project_id`),
  KEY `idx_online` (`online_status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备表：一机一密，device_key 为接入标识';

-- ============================================================
-- 5. device_group 设备分组表（E4）
--    刻意保持「平铺一级」而非树形：设备分组的实际用法是"按位置/用途分几组"，
--    层级带来的递归查询与前端树组件成本换不来对应价值；日后确需层级加 parent_id 即可平滑扩展。
--    设备归属用 device.group_id（一对多）而非中间表：一个设备同时属于多个分组在运维语义上
--    无意义；"多维度标记"的需求交给 device.tags 覆盖。
-- ============================================================
CREATE TABLE IF NOT EXISTS `device_group` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(64)     NOT NULL                COMMENT '分组名称（软删后可复用）',
  `description` VARCHAR(255)    NULL                    COMMENT '分组描述',
  `sort_order`  INT             NOT NULL DEFAULT 0       COMMENT '展示排序（越小越前）',
  -- ⚠️ 墓碑标记「存 id 而非 1」：配合唯一键 (name, deleted)，保证「删除后名称可复用」这件事
  -- 对**任意多次**删除都成立。若用 0/1，第二次删除同名分组就会撞上 (name, 1) 的唯一键（实测 1062）。
  -- 故此处类型为 BIGINT 而非其他表那样的 TINYINT —— 是有意为之，不是笔误。
  `deleted`     BIGINT UNSIGNED NOT NULL DEFAULT 0      COMMENT '软删墓碑：0未删；已删则写自身 id（保证名称可反复复用）',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_group_name` (`name`, `deleted`),
  KEY `idx_sort` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备分组表（E4）：平铺一级，设备归属走 device.group_id';

-- ============================================================
-- 三、时序与事件流（只增不改不删，无 deleted 字段）
-- ============================================================

-- ============================================================
-- 6. device_telemetry 设备遥测表（时序数据 · 按月分区）
--    物模型属性上报的落点。MVP 用 MySQL，V2 迁 TDengine。
--    注意：分区键 ts 必须在主键中，故主键为 (id, ts)。
-- ============================================================
CREATE TABLE IF NOT EXISTS `device_telemetry` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_id`    BIGINT UNSIGNED NOT NULL                COMMENT '设备主键（逻辑外键 → device.id）',
  `property_key` VARCHAR(64)     NOT NULL                COMMENT '物模型属性标识（如 temperature）',
  `value`        VARCHAR(255)    NOT NULL                COMMENT '属性值（字符串化存储）',
  `value_type`   TINYINT         NOT NULL DEFAULT 1      COMMENT '值类型：1数值 2字符串 3布尔',
  `ts`           DATETIME(3)     NOT NULL                COMMENT '上报时间（设备侧时间）',
  PRIMARY KEY (`id`, `ts`),
  -- D2 幂等去重（BUG-20 修正）：唯一键 = 设备 + 属性 + **值** + ts。value 必须入键：
  --   · BUG-20：仅 (device_id, property_key, ts) 时，QoS0 突发多报文共享同一毫秒的 EMQX 时间戳，
  --     不同上报被唯一键误判为"同一上报"，ON DUPLICATE 静默丢掉约 90% 数据；
  --   · 加入 value 后：并发不同上报 value 不同 → 不误删；真正的 offset 重放 / webhook 重发
  --     value + ts 全同 → 仍能正确去重。
  UNIQUE KEY `uk_device_prop_val_ts` (`device_id`, `property_key`, `value`, `ts`),
  KEY `idx_device_ts` (`device_id`, `ts`),
  -- 查询索引（补回）：唯一键把 value 插在中间，无法直接支撑 (device_id, property_key)+ts
  -- 范围查询的索引级裁剪；补这条普通索引专供「按属性查区间 / 取最新」。
  KEY `idx_device_prop_ts` (`device_id`, `property_key`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备遥测表：物模型属性时序数据，按月分区'
PARTITION BY RANGE COLUMNS (`ts`) (
  PARTITION p202608 VALUES LESS THAN ('2026-09-01'),
  PARTITION p202609 VALUES LESS THAN ('2026-10-01'),
  PARTITION p202610 VALUES LESS THAN ('2026-11-01'),
  PARTITION p202611 VALUES LESS THAN ('2026-12-01'),
  PARTITION p202612 VALUES LESS THAN ('2027-01-01'),
  PARTITION p202701 VALUES LESS THAN ('2027-02-01'),
  PARTITION p202702 VALUES LESS THAN ('2027-03-01'),
  PARTITION p202703 VALUES LESS THAN ('2027-04-01'),
  PARTITION p202704 VALUES LESS THAN ('2027-05-01'),
  PARTITION p202705 VALUES LESS THAN ('2027-06-01'),
  PARTITION p202706 VALUES LESS THAN ('2027-07-01'),
  PARTITION p202707 VALUES LESS THAN ('2027-08-01'),
  PARTITION pmax    VALUES LESS THAN (MAXVALUE)
);

-- ============================================================
-- 7. device_log 设备日志表
--    上下线、操作、指令、告警等事件，用于问题追溯
-- ============================================================
CREATE TABLE IF NOT EXISTS `device_log` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_id`  BIGINT UNSIGNED NOT NULL                COMMENT '设备主键',
  `log_type`   TINYINT         NOT NULL                COMMENT '类型：1上线 2下线 3操作 4指令 5告警',
  `content`    VARCHAR(1024)   NULL                    COMMENT '日志内容',
  `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_device_time` (`device_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备日志表：上下线/操作/指令/告警事件';

-- ============================================================
-- 8. command 指令表（控制下发状态追踪）
--    下发链路：控制台/API → 本表落一条 → EMQX REST API → 设备 → 回报更新状态
--    状态机变更走 updated_at 审计，不做软删。
--    ⚠️ 状态同时被两处驱动：设备 ACK 置 2/3；超时扫描任务置 4（idx_status_sent 专供该扫描）。
-- ============================================================
CREATE TABLE IF NOT EXISTS `command` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_id`    BIGINT UNSIGNED NOT NULL                COMMENT '目标设备主键',
  `command_type` TINYINT         NOT NULL                COMMENT '类型：1属性下发 2服务调用',
  `payload`      JSON            NOT NULL                COMMENT '指令内容（如 {"switch":false}）',
  `status`       TINYINT         NOT NULL DEFAULT 0      COMMENT '状态：0待下发 1已下发 2已执行 3失败 4超时',
  `request_id`   VARCHAR(64)     NOT NULL                COMMENT '幂等键（每次下发唯一，防重复执行）',
  `sent_at`      DATETIME(3)     NULL                    COMMENT '下发时间（离线挂起时为空）',
  `ack_at`       DATETIME(3)     NULL                    COMMENT '设备回报时间',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '状态机变更时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_request_id` (`request_id`),
  KEY `idx_device_status_time` (`device_id`, `status`, `created_at`),
  KEY `idx_status_sent` (`status`, `sent_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指令表：控制下发状态追踪（幂等/超时/状态机）';

-- ============================================================
-- 四、规则与告警（E3）
-- ============================================================

-- ============================================================
-- 9. device_rule 阈值规则表（E3）
--    命中后生成告警；action_type=1 时还会联动下发指令（如「温度 > 50 自动关断」）。
--    cooldown_sec 是抑制窗口 —— 设备每秒上报，温度持续超标时若不抑制，一分钟能刷出 60 条重复告警。
--    规则由后端 RuleEngineServiceImpl 在**每条遥测落库后**评估（读 Redis 缓存 rules:{product_key}，TTL 60s）。
--    ⚠️ 因此规则的增删改必须清该缓存，否则「界面改了、上报还是老规则」（规则管理接口已处理）。
-- ============================================================
CREATE TABLE IF NOT EXISTS `device_rule` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`           VARCHAR(64)     NOT NULL                COMMENT '规则名称',
  `product_key`    VARCHAR(32)     NOT NULL                COMMENT '所属产品（规则按产品生效）',
  `device_key`     VARCHAR(64)     NULL                    COMMENT '限定单台设备；NULL / 空 = 作用于该产品下所有设备',
  `property_key`   VARCHAR(64)     NOT NULL                COMMENT '监测的属性标识（应对应产品物模型）',
  `operator`       VARCHAR(4)      NOT NULL                COMMENT '比较运算符：> >= < <= == !=',
  `threshold`      DECIMAL(18,4)   NOT NULL                COMMENT '阈值',
  `level`          TINYINT         NOT NULL DEFAULT 2      COMMENT '告警级别：1严重 2一般 3提示',
  `alarm_type`     VARCHAR(32)     NOT NULL DEFAULT '规则告警' COMMENT '告警类型（展示分组用）',
  `action_type`    TINYINT         NOT NULL DEFAULT 0      COMMENT '命中动作：0仅告警 1告警并下发指令',
  `action_payload` JSON            NULL                    COMMENT 'action_type=1 时下发到设备的 payload（属性名→值；NULL 而非空串，JSON 列不接受 ""）',
  `cooldown_sec`   INT             NOT NULL DEFAULT 60     COMMENT '抑制窗口：同规则同设备 N 秒内只告警一次',
  `enabled`        TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1启用 0停用',
  `deleted`        TINYINT         NOT NULL DEFAULT 0      COMMENT '软删：0未删 1已删（对齐 MyBatis-Plus @TableLogic）',
  `created_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`     DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_product_enabled` (`product_key`, `enabled`, `deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='阈值规则表（E3）：命中生成告警，可选联动下发指令';

-- ============================================================
-- 10. device_alarm 告警记录表（E3）
--     独立成表而不复用 device_log：告警有结构化字段与「未处理 → 已处理」状态流转，
--     塞进 device_log 的 1024 字符自由文本会让筛选与统计退化成字符串解析。
--     rule_name 冗余存档：规则被删后，历史告警仍可读（rule_id 成孤儿引用，无外键，物理无害）。
-- ============================================================
CREATE TABLE IF NOT EXISTS `device_alarm` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_id`  BIGINT UNSIGNED NOT NULL                COMMENT '设备主键',
  `rule_id`    BIGINT UNSIGNED NULL                    COMMENT '命中的规则（规则删除后为孤儿引用，可空）',
  `alarm_type` VARCHAR(32)     NOT NULL DEFAULT '规则告警' COMMENT '告警类型',
  `level`      TINYINT         NOT NULL DEFAULT 2      COMMENT '告警级别：1严重 2一般 3提示',
  `rule_name`  VARCHAR(64)     NULL                    COMMENT '规则名快照（冗余，规则删除后仍可读）',
  `content`    VARCHAR(512)     NULL                   COMMENT '告警正文（含命中值与阈值，便于事后追溯）',
  `status`     TINYINT         NOT NULL DEFAULT 0      COMMENT '处理状态：0未处理 1已处理',
  `handled_at` DATETIME(3)     NULL                    COMMENT '处理时间',
  `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '产生时间',
  PRIMARY KEY (`id`),
  KEY `idx_device_time` (`device_id`, `created_at`),
  KEY `idx_status_time` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警记录表（E3）：含未处理/已处理状态流转';

-- ============================================================
-- 11. device_event 设备事件表（E3）
--     设备主动上报的事件（MQTT up/{device_key}/event → EMQX 规则 → POST /api/v1/events）。
--     event_key 对应物模型 events[].key；**展示名与类型不入库**，查询时按产品 TSL 回填
--     —— 这样改物模型文案不会让历史数据前后不一致。
-- ============================================================
CREATE TABLE IF NOT EXISTS `device_event` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_id`  BIGINT UNSIGNED NOT NULL                COMMENT '设备主键',
  `event_key`  VARCHAR(64)     NOT NULL                COMMENT '事件标识（对应产品物模型 events[].key）',
  `params`     JSON            NULL                    COMMENT '事件参数原文',
  `ts`         DATETIME(3)     NOT NULL                COMMENT '事件发生时间（设备侧时间）',
  `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入库时间',
  PRIMARY KEY (`id`),
  KEY `idx_device_ts` (`device_id`, `ts`),
  KEY `idx_device_event_ts` (`device_id`, `event_key`, `ts`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='设备事件表（E3）：事件上报落点，展示名按 TSL 回填';

-- ============================================================
-- 设计说明（面试 / 论文可讲点）
-- ============================================================
-- 1. 鉴权约定（一机一密，对齐 OneNET 三元组简化版）：
--    MQTT 连接时 clientId = device_key，username = device_key，
--    password = device_secret。EMQX auth_http 回调 SpringBoot，
--    SpringBoot 查 Redis「auth:{device_key} → secret 密文|启用位|product_key」比对。
--
-- 2. 为什么不用物理外键：设备高频上报场景，外键约束带来行锁竞争，
--    用「逻辑外键 + 索引 + 应用层保证」替代，性能优先。
--
-- 3. device_telemetry 分区策略：按月 RANGE COLUMNS 分区，共 12 个月 + pmax。
--    ⚠️ 滚月必须用 REORGANIZE 拆分 pmax（不能 ADD PARTITION，pmax 已封顶，
--    直接 ADD 会因「分区边界必须严格递增」报错）：
--      ALTER TABLE device_telemetry REORGANIZE PARTITION pmax INTO (
--        PARTITION p202708 VALUES LESS THAN ('2027-09-01'),
--        PARTITION pmax    VALUES LESS THAN (MAXVALUE));
--    建议用 MySQL EVENT 或后端定时任务每月 1 号自动执行；
--    数据量超千万级 / 单设备秒级上报时，V2 迁 TDengine（超级表/子表）。
--
-- 4. 软删约定：
--    业务实体表（product / device / user / project / device_rule）用 deleted 软删
--    （0 未删 1 已删，对齐 MyBatis-Plus @TableLogic）；
--    device_group 是**特例**：deleted 为 BIGINT，墓碑写自身 id（配合 uk_group_name(name, deleted)
--    让分组名可以反复复用，详见该表上方注释）；
--    事件流表（device_telemetry / device_log / command / device_alarm / device_event）
--    只增不改不删，靠分区 / 归档清理，无 deleted 字段。
--
-- 5. 上报链路 device_key → device_id 映射：Webhook 报文带 device_key，
--    SpringBoot 用 Redis 缓存映射关系，避免每次上报都查 device 表。
--
-- 6. command 表 request_id 幂等：同一指令重复下发不会产生重复执行。
--
-- 7. 「两层启停」的合成口径（F5）：设备能不能接入 = device.status && product.status，
--    在判断层实时合成，**不**把产品停用批量写进子设备 —— 否则产品恢复后无法还原各设备原状态。
--
-- 8. 告警/事件的「快照 vs 回填」两种流派（有意混用，各取所长）：
--    · device_alarm.rule_name 存**快照** —— 告警是"当时发生了什么"，规则后来改名不该改写历史；
--    · device_event 不存展示名，查询时按 TSL **回填** —— 事件是"设备说了什么"，
--      展示文案属于产品定义，改文案应当全局一致。
--
-- 9. E3/E4 四张表（device_rule / device_alarm / device_event / device_group）曾在 v1.2 之后
--    只出现在 db/create_all.sql 里，未回填本文件 —— 2026-09-17 已补齐，create_all.sql 删除。
--
-- 10. ⚠️ 与真实库的已知差异（仅注释，结构完全一致）：
--     真实库的表注释为空、且只有 E3 三张表带列注释 —— 因为它们最初是分开建表的，
--     注释在建表时未落库。本文件保留了完整注释（文档价值更高）。
--     如需让真实库也带上注释，可用 Navicat 的「反向生成 SQL」再执行一次，
--     或执行本文件对每张表生成的 ALTER（纯元数据操作，不影响数据）。
-- ============================================================
