-- ============================================================
-- 控制台管理员账号引导（仅容器**首次**建库时执行）
-- 账号：admin / admin123   角色：1（管理员）
-- 用法：docker compose up -d 时由 MySQL 入口脚本自动执行
--       （挂载到 /docker-entrypoint-initdb.d/04-admin.sql）
--
-- 为什么必须预置：seed.sql 明确不预置 user（BCrypt 哈希无法离线造值），
--   而全新的容器库里若没有 user 行，控制台登录接口必然返回 4010，
--   于是"一键拉起"会拉起来却登不进去 —— 这正是 BUG-5 当初踩的坑。
--
-- 哈希来源：用后端同款的 BCryptPasswordEncoder（spring-security-crypto 6.2.4）
--   以 cost=10 现场生成，并用 matches("admin123") = true / matches("wrong") = false
--   双向校验过（见构建记录）。非明文、非猜测值。
--   ⚠️ 生产务必改密：控制台右上角「修改密码」，或 UPDATE user SET password_hash=…
--
-- 幂等：ON DUPLICATE KEY UPDATE `id` = `id`（空操作）—— 已存在则跳过，
--   不会把用户自己改过的密码回滚（与 seed.sql 同一取舍）。
-- ============================================================

SET NAMES utf8mb4;

USE `iot_platform`;

INSERT INTO `user` (`id`, `username`, `password_hash`, `role`, `status`)
VALUES (1, 'admin', '$2a$10$OWXuTPxbkRC1ZgyKbkcMcOZHnfpoS3qoY6Sy9bkHGZnx.JwDmPf3O', 1, 1)
ON DUPLICATE KEY UPDATE `id` = `id`;