-- ============================================================
-- 演示数据（鉴权/演示联调即需）
-- 用法：mysql -u <user> -p < db/seed.sql
--
-- 设计原则：**只补缺，不覆盖**
--   三个 INSERT 都用 `ON DUPLICATE KEY UPDATE \`id\` = \`id\``（空操作）——
--   这是"存在就跳过"的惯用写法；比 `INSERT IGNORE` 好，因为后者会连真错误
--   （字段超长、类型不符）一起吞掉。
--
--   ⚠️ 为什么刻意不覆盖：这两个字段曾用 `= VALUES(...)` 做 upsert，都会静默回退 ——
--     ① `product.tsl`：本文件记的是**建库时**的物模型，而物模型会随 E5 演进。
--        覆盖会把已更新的物模型回滚（例如告警规则联动引用的 `dqy` 属性会凭空消失，
--        规则 UI 里那条规则就变成引用不存在的属性）。
--     ② `device.device_secret`：F3 起库里存 AES-GCM 密文（`v1:` 前缀），
--        而本文件写的是明文 `demo_secret_123`；覆盖会把已加密的密钥退回明文（安全回退）。
--          （当前该设备尚未认证过，仍是明文，所以覆盖暂时"看不出问题"—— 正是隐患的典型形态。）
--
--   要重置演示基线，请 `DROP DATABASE` 后重跑 schema.sql + seed.sql：
--   那是刻意的动作，不该是"灌个种子数据"的副作用。
--
--   user 不预置：BCrypt 哈希无法离线造出有效值，管理员请走接口/控制台创建。
-- ============================================================

USE `iot_platform`;

-- 1. 演示项目
INSERT INTO `project` (`id`, `name`, `description`) VALUES
  (1, '演示项目', 'MVP 联调默认项目')
ON DUPLICATE KEY UPDATE `id` = `id`;

-- 2. 演示产品（物模型与当前演示基线一致：温湿度只读 + 开关可写 + CO2 只读 + 大气压可写）
INSERT INTO `product`
  (`id`, `product_key`, `name`, `project_id`, `node_type`, `tsl`)
VALUES
  (1, 'p_demo_001', '温湿度传感器', 1, 1,
   '{"properties":[{"key":"temperature","name":"温度","type":"float","unit":"℃","access":"readonly"},{"key":"humidity","name":"湿度","type":"float","unit":"%","access":"readonly"},{"key":"switch","name":"开关","type":"bool","access":"readwrite"},{"key":"co2","name":"CO2浓度","type":"float","unit":"ppm","access":"readonly"},{"key":"dqy","name":"大气压","type":"float","unit":"pa","access":"readwrite","min":0,"max":1000}],"services":[],"events":[{"key":"high_temp_alarm","name":"高温告警","type":"info"}]}')
ON DUPLICATE KEY UPDATE `id` = `id`;

-- 3. 演示设备（MQTT 接入凭据：device_key / device_secret）
INSERT INTO `device`
  (`id`, `device_key`, `product_key`, `device_name`, `device_secret`, `project_id`)
VALUES
  (1, 'dev_demo_001', 'p_demo_001', '客厅温湿度计', 'demo_secret_123', 1)
ON DUPLICATE KEY UPDATE `id` = `id`;

-- 鉴权联调时，MQTTX 用以下凭据连接 EMQX：
--   clientId  = dev_demo_001
--   username  = dev_demo_001
--   password  = demo_secret_123
-- 注：库中该 secret 目前是明文（新注册设备自 F3 起存 AES-GCM 密文）；
--     首次认证通过后会被懒迁移为密文，属预期行为。
