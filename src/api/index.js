import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 10000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('iot_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      if (body.code === 4010) {
        localStorage.removeItem('iot_token')
        router.push('/login')
      }
      return Promise.reject(new Error(body.message))
    }
    return body
  },
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('iot_token')
      router.push('/login')
    }
    ElMessage.error(err.response?.data?.message || err.message || '网络错误')
    return Promise.reject(err)
  }
)

export const login = (username, password) =>
  http.post('/login', { username, password })

/**
 * 设备列表。
 *
 * @param {number|null} groupId 分组筛选：`null`/`undefined` = 不过滤；`0` = **未分组**；`>0` = 具体分组
 *   ⚠️ 这里**不能**写 `groupId || undefined` —— `0`（未分组）是有效值，会被 `||` 当假值丢掉，
 *   导致「未分组」筛选静默失效（实测踩过）。
 */
export const listDevices = (
  page = 1,
  size = 20,
  keyword = '',
  productKey = '',
  onlineStatus = 0,
  groupId = null
) =>
  http.get('/devices', {
    params: {
      page,
      size,
      keyword: keyword || undefined,
      product_key: productKey || undefined,
      online_status: onlineStatus > 0 ? onlineStatus : undefined,
      group_id: groupId === null || groupId === undefined ? undefined : groupId
    }
  })

// ===== 3.3 设备列表增强 =====

export const deleteDevices = (deviceKeys) =>
  http.delete('/devices', { data: { device_keys: deviceKeys } })

export const listDeviceLogs = (deviceKey, page = 1, size = 20) =>
  http.get(`/devices/${deviceKey}/logs`, { params: { page, size } })

export const getTsl = (deviceKey) =>
  http.get(`/devices/${deviceKey}/tsl`)

export const getLatestTelemetry = (deviceKey) =>
  http.get(`/devices/${deviceKey}/telemetry/latest`)

export const getHistoryTelemetry = (deviceKey, property, start, end) =>
  http.get(`/devices/${deviceKey}/telemetry`, { params: { property, start, end } })

// ===== 调试台：模拟设备上报 =====
// 复用 EMQX 规则引擎回调的同一接口（/telemetry 在 JWT 放行清单内），
// 前端伪造一条上报即可走通完整链路：Kafka → 入库 → 影子对齐 → 在线状态刷新。

export const mockReportTelemetry = (data) =>
  http.post('/telemetry', data)

// ===== E6 控制台统计（仪表盘「物联概览」）=====
// 契约见 docs/02_设计契约/API契约.md §7

/** 概览统计：product / device / message / alarm */
export const getStatsOverview = () =>
  http.get('/stats/overview')

/** 消息趋势；granularity=day（默认近 30 天）| month（近 12 月） */
export const getMessageTrend = (granularity = 'day', range) =>
  http.get('/stats/message-trend', { params: { granularity, range } })

/** 告警列表（E6 阶段后端返回空列表，数据链路依赖 E3） */
export const listAlerts = (page = 1, size = 10) =>
  http.get('/alerts', { params: { page, size } })

/** E3 设备事件分页（事件记录 tab；契约 §8.2） */
export const listDeviceEvents = (deviceKey, page = 1, size = 20, eventKey) =>
  http.get(`/devices/${deviceKey}/events`, { params: { page, size, event_key: eventKey } })

// ===== E4 设备分组（契约 §10）=====

/** 分组列表（含各分组设备数与未分组数） */
export const listDeviceGroups = () => http.get('/device-groups')

/** 新建分组 */
export const createDeviceGroup = (data) => http.post('/device-groups', data)

/** 编辑分组 */
export const updateDeviceGroup = (id, data) => http.put(`/device-groups/${id}`, data)

/** 删除分组（组内设备回到「未分组」，不级联删设备） */
export const deleteDeviceGroup = (id) => http.delete(`/device-groups/${id}`)

/** 批量分配设备到分组（id=0 表示移出分组） */
export const assignDevicesToGroup = (id, deviceKeys) =>
  http.put(`/device-groups/${id}/devices`, { device_keys: deviceKeys })

export const dispatchCommand = (deviceKey, commandType, payload) =>
  http.post(`/devices/${deviceKey}/commands`, { command_type: commandType, payload })

export const getCommandStatus = (requestId) =>
  http.get(`/commands/${requestId}`)

export const registerDevice = (data) =>
  http.post('/devices', data)

export const registerBatch = (data) =>
  http.post('/devices/batch', data)

// ===== E5 产品与物模型管理 =====

export const listProducts = () =>
  http.get('/products')

export const createProduct = (data) =>
  http.post('/products', data)

export const getProductTsl = (productKey) =>
  http.get(`/products/${productKey}/tsl`)

export const updateProductTsl = (productKey, tsl) =>
  http.put(`/products/${productKey}/tsl`, tsl)

// ===== E1 设备影子 =====

export const getShadow = (deviceKey) =>
  http.get(`/devices/${deviceKey}/shadow`)

export const setShadowDesired = (deviceKey, desired) =>
  http.put(`/devices/${deviceKey}/shadow/desired`, desired)

export const clearShadow = (deviceKey) =>
  http.delete(`/devices/${deviceKey}/shadow`)

export const listPendingCommands = (deviceKey) =>
  http.get(`/devices/${deviceKey}/commands/pending`)

export default http
