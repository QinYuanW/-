
CREATE TABLE IF NOT EXISTS device (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  device_key VARCHAR(64) NOT NULL,
  product_key VARCHAR(32) NOT NULL,
  device_name VARCHAR(64) NOT NULL,
  device_secret VARCHAR(64) NOT NULL,
  project_id BIGINT UNSIGNED NULL,
  online_status TINYINT NOT NULL DEFAULT 0,
  last_online_at DATETIME(3) NULL,
  last_offline_at DATETIME(3) NULL,
  last_ip VARCHAR(45) NULL,
  group_id BIGINT UNSIGNED NULL,
  tags VARCHAR(255) NULL,
  status TINYINT NOT NULL DEFAULT 1,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_device_key (device_key),
  UNIQUE KEY uk_product_name (product_key, device_name),
  KEY idx_project (project_id),
  KEY idx_online (online_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS device_log (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  device_id BIGINT UNSIGNED NOT NULL,
  log_type TINYINT NOT NULL,
  content VARCHAR(1024) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_device_time (device_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS command (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  device_id BIGINT UNSIGNED NOT NULL,
  command_type TINYINT NOT NULL,
  payload JSON NOT NULL,
  status TINYINT NOT NULL DEFAULT 0,
  request_id VARCHAR(64) NOT NULL,
  sent_at DATETIME(3) NULL,
  ack_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_request_id (request_id),
  KEY idx_device_status_time (device_id, status, created_at),
  KEY idx_status_sent (status, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS project (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  description VARCHAR(255) NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  role TINYINT NOT NULL DEFAULT 2,
  status TINYINT NOT NULL DEFAULT 1,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- E3 规则引擎与告警（2026-09-14）
-- ============================================================

-- 阈值规则定义。命中后生成告警，可选联动下发指令。
CREATE TABLE IF NOT EXISTS device_rule (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  product_key VARCHAR(32) NOT NULL,
  device_key VARCHAR(64) NULL COMMENT 'NULL = 作用于该产品下所有设备',
  property_key VARCHAR(64) NOT NULL,
  operator VARCHAR(4) NOT NULL COMMENT '> >= < <= == !=',
  threshold DECIMAL(18,4) NOT NULL,
  level TINYINT NOT NULL DEFAULT 2 COMMENT '1 严重 / 2 一般 / 3 提示',
  alarm_type VARCHAR(32) NOT NULL DEFAULT '规则告警',
  action_type TINYINT NOT NULL DEFAULT 0 COMMENT '0 仅告警 / 1 告警并下发指令',
  action_payload JSON NULL COMMENT 'action_type=1 时下发到设备的 payload',
  cooldown_sec INT NOT NULL DEFAULT 60 COMMENT '抑制窗口：同规则同设备 N 秒内只告警一次',
  enabled TINYINT NOT NULL DEFAULT 1,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_product_enabled (product_key, enabled, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 告警记录。独立成表而不复用 device_log：告警有结构化字段与「未处理→已处理」状态流转，
-- 塞进 device_log 的 1024 字符自由文本会让筛选与统计退化成字符串解析。
CREATE TABLE IF NOT EXISTS device_alarm (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  device_id BIGINT UNSIGNED NOT NULL,
  rule_id BIGINT UNSIGNED NULL,
  alarm_type VARCHAR(32) NOT NULL DEFAULT '规则告警',
  level TINYINT NOT NULL DEFAULT 2 COMMENT '1 严重 / 2 一般 / 3 提示',
  rule_name VARCHAR(64) NULL,
  content VARCHAR(512) NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0 未处理 / 1 已处理',
  handled_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_device_time (device_id, created_at),
  KEY idx_status_time (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- E3 事件通道：设备主动上报的事件（MQTT up/{device_key}/event → EMQX 规则 → POST /api/v1/events）。
-- event_key 对应物模型 events[].key；展示名与类型不入库，查询时按产品 TSL 回填（避免 TSL 改动导致历史数据不一致）。
CREATE TABLE IF NOT EXISTS device_event (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  device_id BIGINT UNSIGNED NOT NULL,
  event_key VARCHAR(64) NOT NULL,
  params JSON NULL COMMENT '事件参数原文',
  ts DATETIME(3) NOT NULL COMMENT '事件发生时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_device_ts (device_id, ts),
  KEY idx_device_event_ts (device_id, event_key, ts)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
