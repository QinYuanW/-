-- ============================================================
-- 演示数据（D3 鉴权联调即需）
-- 用法：mysql -u iot -pIot@2026 < db/seed.sql
-- 说明：预置 1 项目 + 1 产品 + 1 设备，可重复执行（幂等）。
--       user 不预置（BCrypt 哈希需接口生成，无法离线造有效值）。
-- ============================================================

USE `iot_platform`;

-- 1. 演示项目
INSERT INTO `project` (`id`, `name`, `description`) VALUES
  (1, '演示项目', 'MVP 联调默认项目')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- 2. 演示产品（物模型：温度/湿度只读 + 开关读写）
INSERT INTO `product`
  (`id`, `product_key`, `name`, `project_id`, `node_type`, `tsl`)
VALUES
  (1, 'p_demo_001', '温湿度传感器', 1, 1,
   '{"properties":[{"key":"temperature","name":"温度","type":"float","access":"readonly","unit":"℃"},{"key":"humidity","name":"湿度","type":"float","access":"readonly","unit":"%"},{"key":"switch","name":"开关","type":"bool","access":"readwrite"}],"services":[],"events":[{"key":"high_temp_alarm","name":"高温告警"}]}')
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `tsl` = VALUES(`tsl`);

-- 3. 演示设备（MQTT 接入凭据：device_key / device_secret）
INSERT INTO `device`
  (`id`, `device_key`, `product_key`, `device_name`, `device_secret`, `project_id`)
VALUES
  (1, 'dev_demo_001', 'p_demo_001', '客厅温湿度计', 'demo_secret_123', 1)
ON DUPLICATE KEY UPDATE `device_secret` = VALUES(`device_secret`);

-- D3 鉴权联调时，MQTTX 用以下凭据连接 EMQX：
--   clientId  = dev_demo_001
--   username  = dev_demo_001
--   password  = demo_secret_123
