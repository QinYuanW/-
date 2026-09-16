# certs/ · MQTT over TLS(8883) 自签证书

> F2 产物 ｜ 对象：EMQX 5.8 `ssl:default` listener（`0.0.0.0:8883`）
> 本目录**只提交生成脚本与本说明**；证书/私钥均为构建产物，由 `.gitignore` 排除（`*.pem / *.key / *.csr / *.srl / san.cnf`）。

## 1. 为什么要自签证书

EMQX 自带证书的 `CN=Server` 且**没有 SAN**。现代 TLS 栈（ESP32 esp-tls、浏览器、paho-mqtt）校验证书时**只认 SAN、不认 CN**，所以设备用 `localhost` / 局域网 IP 连接 8883 必然报证书错误。F2 用一张带 SAN 的自签证书替换掉它。

## 2. 生成（在 EMQX 容器内执行，无需宿主装 OpenSSL）

```bash
# 把 SAN_IP 换成设备/浏览器实际访问的宿主局域网 IP（ipconfig 查 WLAN 网段）
docker exec -e SAN_IP=10.254.217.62 emqx sh /opt/emqx/etc/certs/gen-certs.sh
docker restart emqx
```

生成的文件直接落在本目录（compose 已把宿主 `./certs` 挂到容器的 `/opt/emqx/etc/certs`）：

| 文件 | 角色 | 是否入库 |
|------|------|:---:|
| `cacert.pem` | CA 证书（公钥）—— 设备端校验服务器用，可放进固件 | ❌ 由脚本生成 |
| `cert.pem` | 服务器证书 —— EMQX 用 | ❌ |
| `key.pem` | 服务器私钥 —— EMQX 用 | ❌ **禁止入库** |
| `ca.key` | CA 私钥 —— 仅签发新证书时用 | ❌ **禁止入库** |
| `server.csr` / `san.cnf` / `*.srl` | 中间产物 | ❌ |

## 3. EMQX 侧接线（零 Dashboard 改动）

`ssl:default` listener 默认就指向 `${EMQX_ETC_DIR}/certs/{cert,key,cacert}.pem`，即 `/opt/emqx/etc/certs/`。compose 里这一行让该路径指向本目录：

```yaml
# docker-compose.yml · emqx.volumes
- ./certs:/opt/emqx/etc/certs:ro
```

因此**换证书 = 替换文件 + `docker restart emqx`**，不再依赖 `docker cp`（后者在容器重建后会丢，TLS 会静默退回 EMQX 自带的无 SAN 证书）。

当前 listener 参数：`verify = verify_none`（**单向 TLS**：客户端校验服务器，服务器不校验客户端证书）。设备侧只需拿到 `cacert.pem`。

## 4. 验证

```bash
# a) 服务器实际提供的证书是否带 SAN（应有 DNS:localhost / IP:127.0.0.1 / IP:<SAN_IP>）
openssl s_client -connect localhost:8883 -servername localhost </dev/null 2>/dev/null \
  | openssl x509 -noout -subject -issuer -ext subjectAltName

# b) python 实测：paho-mqtt 走 8883 + cacert.pem，CONNACK 成功即 TLS 握手 + 三元组认证全通
#    （对照脚本见 tmp/f2/mqtt_tls_test.py，一次性验证，未入库）
```

## 5. 设备端（ESP32）接入要点

- MQTT 端口 **8883**（不要再用 1883 明文）；
- 把 `cacert.pem` 的内容嵌入固件（ESP-IDF 里常用 `esp_mqtt_client_config_t.broker.verification.certificate` 直接填 PEM 字符串，或作为 `EMBED_TXTFILES` 嵌入）；
- 连接地址用证书 SAN 里**列出的**名字/IP（例如 `mqtts://10.254.217.62:8883`），否则主机名校验会失败；
- 三元组不变：`clientId = username = device_key`，`password = device_secret`。

> ⚠️ 本地自签仅供开发。生产应换正式 CA 签发证书，并考虑开启 `verify = verify_peer` + 设备证书（双向 TLS）。
