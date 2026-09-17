-- ============================================================
-- 建表验证清单（在 db/schema.sql 执行后运行）
-- 用法：mysql -u <user> -p < db/verify.sql
-- 逐段检查：表清单 / 分区 / 分区裁剪 / 软删 / 索引 / E3+E4 表 / 演示数据
--
-- 定位：schema.sql 用的是 CREATE TABLE IF NOT EXISTS，**重复执行不会报错**，
--       因此它无法告诉你"现有库的结构对不对"。判断结构正确性靠本文件。
--       第 7 段「演示数据」只在**刚建库 + 灌过 seed.sql 的干净环境**上成立，
--       在跑了很久的工作库上数字必然不同，那不是错误 —— 别照抄当验收标准。
-- ============================================================

USE `iot_platform`;

-- 1. 表清单（应 11 张）
SELECT '=== 1. 表清单（应 11 张）===' AS `step`;
SELECT COUNT(*) AS `table_count` FROM information_schema.tables
WHERE table_schema = 'iot_platform' AND table_type = 'BASE TABLE';
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

-- 4. 软删字段核对
--    业务实体表 5 张（product / device / user / project / device_rule）为 TINYINT 0/1；
--    device_group 是**特例**：deleted 为 BIGINT，墓碑写自身 id（保证分组名可反复复用）。
SELECT '=== 4. 软删字段核对（应 6 张表；其中 device_group 为 bigint 特例）===' AS `step`;
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'iot_platform' AND COLUMN_NAME = 'deleted'
ORDER BY TABLE_NAME;

-- 5. command 索引核对（应含 idx_status_sent，供超时扫描）
SELECT '=== 5. command 索引核对（应含 idx_status_sent）===' AS `step`;
SHOW INDEX FROM `command`;

-- 6. E3 / E4 新增表与关键索引核对
--    device_telemetry 的幂等键必须是 uk_device_prop_val_ts（含 value，BUG-20）
SELECT '=== 6a. E3/E4 四张表是否齐备 ===' AS `step`;
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'iot_platform'
  AND table_name IN ('device_rule','device_alarm','device_event','device_group')
ORDER BY table_name;

SELECT '=== 6b. device_telemetry 幂等键（应含 value 列）===' AS `step`;
SELECT index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = 'iot_platform' AND table_name = 'device_telemetry'
  AND index_name = 'uk_device_prop_val_ts'
ORDER BY seq_in_index;

SELECT '=== 6c. device_group 唯一键（应为 name + deleted）===' AS `step`;
SELECT index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = 'iot_platform' AND table_name = 'device_group'
  AND index_name = 'uk_group_name'
ORDER BY seq_in_index;

-- 7. 演示数据核对（仅适用于「刚建库 + 执行过 seed.sql」的干净环境：应各 1 条）
SELECT '=== 7. 演示数据核对（仅干净建库环境；工作库数字必然不同）===' AS `step`;
SELECT
  (SELECT COUNT(*) FROM `project`) AS projects,
  (SELECT COUNT(*) FROM `product`) AS products,
  (SELECT COUNT(*) FROM `device`)  AS devices;
