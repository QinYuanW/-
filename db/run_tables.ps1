$mysql = "C:\Program Files\MySQL\MySQL Server 5.7\bin\mysql.exe"
$sql = Get-Content "d:\Demo\YunPingTai\db\create_all.sql" -Raw
$sql | & $mysql -u root -p123456 --default-character-set=utf8mb4 iot_platform
Write-Host "DONE"