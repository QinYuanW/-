#!/bin/sh
# ============================================================
# F2 · MQTT over TLS(8883) 自签证书生成脚本
# ------------------------------------------------------------
# 为什么在容器里跑：EMQX 镜像自带 openssl 3.x，宿主不必装 OpenSSL。
# 产物写到 /opt/emqx/etc/certs，而 compose 已把宿主 ./certs 挂到该路径，
# 所以生成的文件会直接出现在仓库的 certs/ 目录下（随仓库版本化）。
#
# 用法（宿主 PowerShell / bash）：
#   docker exec -e SAN_IP=10.254.217.62 emqx sh /opt/emqx/etc/certs/gen-certs.sh
#   docker restart emqx          # 让 listener 重新加载证书
#
# 参数（环境变量，均可选）：
#   SAN_IP  设备/浏览器实际访问的宿主局域网 IP（默认 127.0.0.1）
#   CN      服务器证书 Common Name（默认 localhost）
#   DAYS    有效期天数（默认 3650 = 10 年）
#
# 产物：
#   cacert.pem   CA 证书（公钥）—— 设备端/客户端用它校验服务器，可公开放进固件
#   cert.pem     服务器证书 —— EMQX 用
#   key.pem      服务器私钥 —— EMQX 用（★ 禁止入库，.gitignore 已排除 *.pem）
#   ca.key       CA 私钥     —— 仅在需要签发新证书时用（★ 禁止入库）
# ============================================================
set -e

OUT=${OUT:-/opt/emqx/etc/certs}
SAN_IP=${SAN_IP:-127.0.0.1}
CN=${CN:-localhost}
DAYS=${DAYS:-3650}

cd "$OUT"
echo "[gen] out=$OUT  cn=$CN  san_ip=$SAN_IP  days=$DAYS"

# 1) CA 私钥 + 自签 CA 证书
openssl genrsa -out ca.key 2048
openssl req -x509 -new -nodes -key ca.key -sha256 -days "$DAYS" \
    -subj "/C=CN/O=YunPingTai/CN=YunPingTai-Test-CA" \
    -out cacert.pem

# 2) 服务器私钥 + CSR
openssl genrsa -out key.pem 2048
openssl req -new -key key.pem \
    -subj "/C=CN/O=YunPingTai/CN=$CN" \
    -out server.csr

# 3) SAN 扩展
#    ★ 关键：现代 TLS 栈（ESP32 esp-tls / 浏览器 / paho）只认 SAN，不认 CN。
#    EMQX 自带证书 CN=Server 且无 SAN，设备连 localhost/IP 必然校验失败 —— 这正是
#    F2 必须换自签证书的原因。把设备实际访问用到的所有名字/IP 都列进来。
cat > san.cnf <<EOF
subjectAltName=DNS:localhost,DNS:$CN,IP:127.0.0.1,IP:$SAN_IP
extendedKeyUsage=serverAuth
EOF

# 4) 用 CA 签发服务器证书
openssl x509 -req -in server.csr -CA cacert.pem -CAkey ca.key -CAcreateserial \
    -out cert.pem -days "$DAYS" -sha256 -extfile san.cnf

# 5) 权限（EMQX 以 emqx 用户读；若 chown 因容器能力受限失败，755/644 仍可读）
chmod 644 cert.pem cacert.pem 2>/dev/null || true
chmod 600 key.pem 2>/dev/null || true

echo "[gen] done. SAN:"
openssl x509 -in cert.pem -noout -ext subjectAltName
