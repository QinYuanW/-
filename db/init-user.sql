-- ============================================================
-- 专用账号初始化（最小权限原则）
-- 账号：iot / 123456
-- 用法：mysql -u root -p < db/init-user.sql
--
-- ⚠️ 密码必须与后端一致，否则后端启动即连不上库：
--      iot-platform/src/main/resources/application.yml
--        spring.datasource.username = iot
--        spring.datasource.password = 123456
--    本文件原先写的是 `Iot@2026`，与实际使用的 123456 不符 —— 已更正
--    （见 BUG_LOG 2026-09-17）。生产环境请**同时**改本文件与 application.yml，
--    或改用环境变量注入、不要把口令写进仓库。
--
-- 兼容：MySQL 5.7（默认 mysql_native_password）；8.0 默认 caching_sha2_password，
--       若 8.0 下 JDBC 认证报错，改用 IDENTIFIED WITH mysql_native_password BY 显式指定。
-- ============================================================

-- 创建专用账号。
--   '%'       允许任意 host（容器 / 宿主机走 IP 连接都命中它）
--   'localhost' 单独再建一条：Windows 命名管道下 '%' 通配符匹配不到 localhost 连接（见 BUG_LOG）
CREATE USER IF NOT EXISTS 'iot'@'%' IDENTIFIED BY '123456';
CREATE USER IF NOT EXISTS 'iot'@'localhost' IDENTIFIED BY '123456';

-- 强制对齐密码：`CREATE USER IF NOT EXISTS` 对**已存在**的账号不会改密码，
-- 所以上面两条在"账号已存在但密码不对"的场景下是空转。这里显式重设，让脚本真正收敛
-- （重设为文件里记录的、与 application.yml 一致的那个值）。
ALTER USER 'iot'@'%' IDENTIFIED BY '123456';
ALTER USER 'iot'@'localhost' IDENTIFIED BY '123456';

-- 授权：仅限 iot_platform 库（不碰系统库，权限最小化）
GRANT ALL PRIVILEGES ON `iot_platform`.* TO 'iot'@'%';
GRANT ALL PRIVILEGES ON `iot_platform`.* TO 'iot'@'localhost';
FLUSH PRIVILEGES;

-- 验证：
--   SELECT user, host FROM mysql.user WHERE user = 'iot';
--   SHOW GRANTS FOR 'iot'@'localhost';
