-- ============================================================
-- 建表验证清单（schema.sql + seed.sql 执行后运行）
-- 用法：mysql -u iot -pIot@2026 < db/verify.sql
-- 逐段检查：表清单 / 分区 / 分区裁剪 / 软删 / 索引 / 演示数据
-- ============================================================

USE `iot_platform`;

-- 1. 表清单（应看到 7 张表）
SELECT '=== 1. 表清单（应 7 张）===' AS `step`;
SHOW TABLES;

-- 2. device_telemetry 分区（应 13 个：12 个月 + pmax）
SELECT '=== 2. device_telemetry 分区（应 13 个）===' AS `step`;
SELECT PARTITION_NAME, PARTITION_DESCRIPTION
FROM information_schema.PARTITIONS
WHERE TABLE_SCHEMA = 'iot_platform' AND TABLE_NAME = 'device_telemetry'
ORDER BY PARTITION_ORDINAL_POSITION;

-- 3. 分区裁剪验证（期望只命中 p202608 单分区）
SELECT '=== 3. 分区裁剪 EXPLAIN（期望 partitions=p202608）===' AS `step`;
EXPLAIN PARTITIONS
SELECT * FROM device_telemetry
WHERE device_id = 1 AND ts BETWEEN '2026-08-01' AND '2026-08-31';

-- 4. 软删字段核对（应 4 张业务表：product/device/user/project）
SELECT '=== 4. 软删字段核对（应 4 张表）===' AS `step`;
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'iot_platform' AND COLUMN_NAME = 'deleted'
ORDER BY TABLE_NAME;

-- 5. command 索引核对（应含 idx_status_sent）
SELECT '=== 5. command 索引核对（应含 idx_status_sent）===' AS `step`;
SHOW INDEX FROM `command`;

-- 6. 演示数据核对（应各 1 条）
SELECT '=== 6. 演示数据核对（应各 1 条）===' AS `step`;
SELECT
  (SELECT COUNT(*) FROM `project`) AS projects,
  (SELECT COUNT(*) FROM `product`) AS products,
  (SELECT COUNT(*) FROM `device`)  AS devices;
