-- ============================================================
-- 专用账号初始化（最小权限原则）
-- 账号：iot / Iot@2026   （生产环境请务必改密码）
-- 用法：mysql -u root -p < db/init-user.sql
-- 兼容：MySQL 5.7（默认 mysql_native_password）；8.0 默认 caching_sha2_password，
--       若 8.0 下 JDBC 认证报错，改用 IDENTIFIED WITH mysql_native_password BY 显式指定。
-- ============================================================

-- 创建专用账号（% 允许任意 host；localhost 单独建，规避 Windows 命名管道下
--   '%' 通配符匹配不到 localhost 连接的问题，见 BUG_LOG）
CREATE USER IF NOT EXISTS 'iot'@'%' IDENTIFIED BY 'Iot@2026';
CREATE USER IF NOT EXISTS 'iot'@'localhost' IDENTIFIED BY 'Iot@2026';

-- 授权：仅限 iot_platform 库（不碰系统库，权限最小化）
GRANT ALL PRIVILEGES ON `iot_platform`.* TO 'iot'@'%';
GRANT ALL PRIVILEGES ON `iot_platform`.* TO 'iot'@'localhost';

FLUSH PRIVILEGES;

-- 验证：SELECT user, host FROM mysql.user WHERE user = 'iot';
