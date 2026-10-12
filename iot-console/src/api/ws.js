// E2 实时推送：连接后端 WebSocket 获取设备遥测，替代 5s 轮询。
// 契约见 docs/02_设计契约/API契约.md §9。
//
// 设计要点：
//   1. 浏览器 WebSocket 无法自定义请求头 → token 走查询参数；
//   2. 心跳 30s（防中间设备按空闲超时掐连接）；
//   3. 断线重连采用指数退避 1→2→4→8s，上限 10s，避免服务端未起时疯狂重试。

/** 后端 WS 端点（直连 8080；路径不在 /api/v1 下，由服务端自行鉴权） */
const WS_URL = `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.hostname}:8080/ws/telemetry`

const HEARTBEAT_MS = 30000
const MAX_BACKOFF_MS = 10000

/**
 * 建立遥测推送连接。
 *
 * @param {object}   opts
 * @param {string}   opts.deviceKey 只订阅该设备；留空则订阅全部
 * @param {Function} opts.onOpen    连接成功（调用方据此停掉轮询兜底）
 * @param {Function} opts.onClose   连接断开（调用方据此恢复轮询兜底）
 * @param {Function} opts.onMessage 收到报文（pong 已被内部消化，不会传出）
 * @returns {{close: Function}} 句柄，组件卸载时调用
 */
export function createTelemetrySocket({ deviceKey, onOpen, onClose, onMessage } = {}) {
  let ws = null
  let heartbeatTimer = null
  let reconnectTimer = null
  let retry = 0
  let closedByUser = false

  function buildUrl() {
    const token = localStorage.getItem('iot_token') || ''
    const params = [`token=${encodeURIComponent(token)}`]
    if (deviceKey) params.push(`device_key=${encodeURIComponent(deviceKey)}`)
    return `${WS_URL}?${params.join('&')}`
  }

  function connect() {
    try {
      ws = new WebSocket(buildUrl())
    } catch (e) {
      scheduleReconnect()
      return
    }

    ws.onopen = () => {
      retry = 0
      startHeartbeat()
      if (onOpen) onOpen()
    }

    ws.onmessage = (ev) => {
      let msg
      try {
        msg = JSON.parse(ev.data)
      } catch (e) {
        return
      }
      if (msg.type === 'pong') return
      if (onMessage) onMessage(msg)
    }

    ws.onclose = (ev) => {
      stopHeartbeat()
      if (onClose) onClose(ev)
      if (!closedByUser) scheduleReconnect()
    }

    // onerror 之后必然触发 onclose，重连逻辑统一放在 onclose，避免重复调度
    ws.onerror = () => {}
  }

  function startHeartbeat() {
    stopHeartbeat()
    heartbeatTimer = setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'ping' }))
      }
    }, HEARTBEAT_MS)
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  function scheduleReconnect() {
    if (reconnectTimer) return
    const delay = Math.min(1000 * 2 ** retry, MAX_BACKOFF_MS)
    retry += 1
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      connect()
    }, delay)
  }

  function close() {
    closedByUser = true
    stopHeartbeat()
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws) ws.close()
  }

  connect()
  return { close }
}
