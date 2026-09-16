-- ============================================================
-- 物联网 PaaS 云平台 · 数据库表结构（MVP）
-- 数据库：iot_platform  版本：v1.2  日期：2026-08-30
-- 引擎：MySQL 5.7.8+（兼容 8.0）  InnoDB   字符集：utf8mb4
--
-- 本文件是「设备 → 平台 → 控制台」全链路的数据合同：
--   所有接口入参出参、EMQX 回调报文、Webhook 解析逻辑都以此为准。
-- 先签合同，再写代码。
--
-- ⚠️ 兼容性提示：本文件使用了 JSON 类型，需 MySQL ≥ 5.7.8。
--   若你的 5.7 版本低于 5.7.8（极少见），请把 tsl / payload 两个
--   JSON 字段改为 TEXT，其余不变。
--
-- v1.2 变更（依据《schema 审核报告》）：
--   [P0-1] 分区滚月说明改为 REORGANIZE 拆 pmax（ADD PARTITION 会报错）
--   [P0-2] product/device/user/project 四表补 deleted 软删字段
--   [P1-3] command 补 updated_at
--   [P1-4] command 补超时扫描索引 (status, sent_at)
--   [P1-5] product 补 project_id（隔离边界完整）
--   [P1-6] value_type 默认值 2 → 1（主场景为数值型温度）
-- ============================================================

CREATE DATABASE IF NOT EXISTS `iot_platform`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE `iot_platform`;

-- ============================================================
-- 1. product 产品表
--    物模型（TSL）的入口：一个产品 = 一类设备 = 一份物模型
-- ============================================================
CREATE TABLE `product` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `product_key` VARCHAR(32)     NOT NULL                COMMENT '产品唯一标识（对外，如 p_demo_001）',
  `name`        VARCHAR(64)     NOT NULL                COMMENT '产品名称',
  `project_id`  BIGINT UNSIGNED NULL                    COMMENT '所属项目（逻辑外键 → project.id）',
  `node_type`   TINYINT         NOT NULL DEFAULT 1      COMMENT '节点类型：1直连 2网关 3子设备',
  `protocol`    VARCHAR(16)     NOT NULL DEFAULT 'MQTT' COMMENT '接入协议（MVP 固定 MQTT）',
  `data_format` VARCHAR(16)     NOT NULL DEFAULT 'JSON' COMMENT '数据格式（MVP 固定 JSON）',
  `tsl`         JSON            NULL                    COMMENT '物模型定义：{properties:[],services:[],events:[]}',
  `status`      TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1启用 0停用',
  `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '软删：0未删 1已删（对齐 MyBatis-Plus @TableLogic）',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_key` (`product_key`),
  KEY `idx_project` (`project_id`)
) ENGINE=InnoDB COMMENT='产品表：物模型入口，一产品多设备';

-- ============================================================
-- 2. device 设备表
--    一机一密三元组：product_key + device_name + device_secret
--    device_key 是设备接入唯一标识（MQTT 的 clientId / username）
-- ============================================================
CREATE TABLE `device` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_key`      VARCHAR(64)     NOT NULL                COMMENT '设备唯一标识（MQTT clientId/username）',
  `product_key`     VARCHAR(32)     NOT NULL                COMMENT '所属产品标识',
  `device_name`     VARCHAR(64)     NOT NULL                COMMENT '设备名称（产品内友好名）',
  `device_secret`   VARCHAR(128)    NOT NULL                COMMENT '设备密钥（MQTT password）。F3 起 AES-256-GCM 密文（v1: 前缀），明文仅注册响应一次性返回',
  `project_id`      BIGINT UNSIGNED NULL                    COMMENT '所属项目（逻辑外键 → project.id）',
  `online_status`   TINYINT         NOT NULL DEFAULT 0      COMMENT '在线状态：0离线 1在线',
  `last_online_at`  DATETIME(3)     NULL                    COMMENT '最近上线时间',
  `last_offline_at` DATETIME(3)     NULL                    COMMENT '最近离线时间',
  `last_ip`         VARCHAR(45)     NULL                    COMMENT '最近接入 IP',
  `group_id`        BIGINT UNSIGNED NULL                    COMMENT '分组（V2 扩展，MVP 可空）',
  `tags`            VARCHAR(255)    NULL                    COMMENT '标签（JSON 数组）',
  `status`          TINYINT         NOT NULL DEFAULT 1      COMMENT '状态：1启用 0禁用',
  `deleted`         TINYINT         NOT NULL DEFAULT 0      COMMENT '软删：0未删 1已删（对齐 MyBatis-Plus @TableLogic）',
  `created_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_key` (`device_key`),
  UNIQUE KEY `uk_product_name` (`product_key`, `device_name`),
  KEY `idx_project` (`project_id`),
  KEY `idx_online` (`online_status`)
) ENGINE=InnoDB COMMENT='设备表：一机一密，device_key 为接入标识';

-- ============================================================
-- 3. device_telemetry 设备遥测表（时序数据 · 按月分区）
--    物模型属性上报的落点。MVP 用 MySQL，V2 迁 TDengine。
--    注意：分区键 ts 必须在主键中，故主键为 (id, ts)。
--    事件流表：只增不改不删，靠分区/归档清理，无 deleted 字段。
-- ============================================================
CREATE TABLE `device_telemetry` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_id`    BIGINT UNSIGNED NOT NULL                COMMENT '设备主键（逻辑外键 → device.id）',
  `property_key` VARCHAR(64)     NOT NULL                COMMENT '物模型属性标识（如 temperature）',
  `value`        VARCHAR(255)    NOT NULL                COMMENT '属性值（字符串化存储）',
  `value_type`   TINYINT         NOT NULL DEFAULT 1      COMMENT '值类型：1数值 2字符串 3布尔',
  `ts`           DATETIME(3)     NOT NULL                COMMENT '上报时间（设备侧时间）',
  PRIMARY KEY (`id`, `ts`),
  KEY `idx_device_ts` (`device_id`, `ts`),
  -- D2 幂等去重（BUG-20 修正）：唯一键=设备+属性+值+ts（value 必须入键，见下）。
  --   · BUG-20：仅旧键 (device_id,property_key,ts) 时，QoS0 突发多报文共享同一毫秒 EMQX 时间戳 ts，
  --     不同上报被唯一键误判为"同一上报"折叠，ON DUPLICATE 静默丢 ~90% 数据。
  --   · 加入 value 后：并发不同上报 value 不同→不误删；真正的 offset 重放/webhook 重发 value+ts 全同→仍正确去重。
  UNIQUE KEY `uk_device_prop_val_ts` (`device_id`, `property_key`, `value`, `ts`),
  -- 查询索引（补回）：唯一键含 value（为幂等去重），value 插在中间，无法直接支撑
  -- (device_id, property_key)+ts 范围查询的索引级裁剪；补普通索引专供「按属性查区间/取最新」。
  KEY `idx_device_prop_ts` (`device_id`, `property_key`, `ts`)
) ENGINE=InnoDB COMMENT='设备遥测表：物模型属性时序数据，按月分区'
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
-- 4. device_log 设备日志表
--    上下线、操作、指令、告警等事件，用于问题追溯
--    事件流表：只增不改不删，无 deleted 字段。
-- ============================================================
CREATE TABLE `device_log` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_id`  BIGINT UNSIGNED NOT NULL                COMMENT '设备主键',
  `log_type`   TINYINT         NOT NULL                COMMENT '类型：1上线 2下线 3操作 4指令 5告警',
  `content`    VARCHAR(1024)   NULL                    COMMENT '日志内容',
  `created_at` DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间',
  PRIMARY KEY (`id`),
  KEY `idx_device_time` (`device_id`, `created_at`)
) ENGINE=InnoDB COMMENT='设备日志表：上下线/操作/指令/告警事件';

-- ============================================================
-- 5. command 指令表（控制下发状态追踪）
--    下发链路：控制台/API → 本表落一条 → EMQX REST API → 设备 → 回报更新状态
--    事件流表：只增不改不删（状态机变更走 updated_at 审计），无 deleted。
-- ============================================================
CREATE TABLE `command` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `device_id`    BIGINT UNSIGNED NOT NULL                COMMENT '目标设备主键',
  `command_type` TINYINT         NOT NULL                COMMENT '类型：1属性下发 2服务调用',
  `payload`      JSON            NOT NULL                COMMENT '指令内容（如 {"property_key":"switch","value":"on"}）',
  `status`       TINYINT         NOT NULL DEFAULT 0      COMMENT '状态：0待下发 1已下发 2已执行 3失败 4超时',
  `request_id`   VARCHAR(64)     NOT NULL                COMMENT '幂等键（每次下发唯一，防重复）',
  `sent_at`      DATETIME(3)     NULL                    COMMENT '下发时间',
  `ack_at`       DATETIME(3)     NULL                    COMMENT '设备回报时间',
  `created_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`   DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '状态机变更时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_request_id` (`request_id`),
  KEY `idx_device_status_time` (`device_id`, `status`, `created_at`),
  KEY `idx_status_sent` (`status`, `sent_at`)
) ENGINE=InnoDB COMMENT='指令表：控制下发状态追踪（幂等/超时/状态机）';

-- ============================================================
-- 6. project 项目表 + 7. user 用户表
--    账号权限模块（D8 前端前启用，MVP 一起建表避免后期 ALTER）
-- ============================================================
CREATE TABLE `project` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `name`        VARCHAR(64)     NOT NULL                COMMENT '项目名称',
  `description` VARCHAR(255)    NULL                    COMMENT '项目描述',
  `deleted`     TINYINT         NOT NULL DEFAULT 0      COMMENT '软删：0未删 1已删（对齐 MyBatis-Plus @TableLogic）',
  `created_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at`  DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB COMMENT='项目表：单租户多项目，资源隔离边界';

CREATE TABLE `user` (
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
) ENGINE=InnoDB COMMENT='用户表：JWT 登录，三级 RBAC';

-- ============================================================
-- 设计说明（面试/论文可讲点）
-- ============================================================
-- 1. 鉴权约定（一机一密，对齐 OneNET 三元组简化版）：
--    MQTT 连接时 clientId = device_key，username = device_key，
--    password = device_secret。EMQX auth_http 回调 SpringBoot，
--    SpringBoot 查 Redis「auth:{device_key} → device_secret」比对。
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
--    数据量超千万级/单设备秒级上报时，V2 迁 TDengine（超级表/子表）。
--
-- 4. 软删约定：
--    业务实体表（product / device / user / project）用 deleted 软删
--    （0 未删 1 已删，对齐 MyBatis-Plus @TableLogic）；
--    事件流表（device_telemetry / device_log / command）只增不改不删，
--    靠分区/归档清理，无 deleted 字段。
--
-- 5. 上报链路 device_key → device_id 映射：Webhook 报文带 device_key，
--    SpringBoot 用 Redis 缓存映射关系，避免每次上报都查 device 表。
--
-- 6. command 表 request_id 幂等：同一指令重复下发不会产生重复执行。
-- ============================================================
