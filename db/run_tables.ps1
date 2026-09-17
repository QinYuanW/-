# ============================================================
# 一键建表：执行 db/schema.sql（唯一建表定义源）
#
# 为什么用 mysql 客户端的 SOURCE，而不是把文件内容管道进来：
#   PowerShell 管道的输出编码会按控制台代码页转码，而 schema.sql 里含中文默认值
#   （如 device_rule.alarm_type DEFAULT '规则告警'），一旦转码就会把默认值写坏。
#   SOURCE 由 mysql 客户端自己按 --default-character-set 读文件，字节级保真。
#
# 幂等：schema.sql 用 CREATE TABLE IF NOT EXISTS，重复执行会跳过已存在的表。
#   也正因如此，它**不能**用来判断"现有库的结构对不对"—— 那件事交给 db/verify.sql。
# ============================================================
$mysql  = "C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
$schema = "d:/Demo/YunPingTai/db/schema.sql"

& $mysql -uroot -p123456 --default-character-set=utf8mb4 -e "SOURCE $schema"
if ($LASTEXITCODE -ne 0) { Write-Host "FAILED (exit=$LASTEXITCODE)"; exit 1 }
Write-Host "DONE"
