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
      // silentError：把提示交给调用方自己呈现（如登录页要在表单内联显示错误）——
      // 否则同一个错误既飘 toast 又出现在表单里，噪音翻倍，用户还会以为错了两次
      if (!res.config?.silentError) ElMessage.error(body.message || '请求失败')
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
    if (!err.config?.silentError) {
      ElMessage.error(err.response?.data?.message || err.message || '网络错误')
    }
    return Promise.reject(err)
  }
)

/**
 * 登录。
 *
 * <p>`silentError`：登录失败要**内联显示在密码框下方**（评审 2.2）——
 * 用户视线在表单上，错误却飘在屏幕右上角是找不到的。所以这里关掉全局 toast，
 * 由 Login.vue 自己渲染错误（它还会在网络异常时给一句兜底文案）。
 *
 * <p>`remember_me`（评审 2.6）：勾选则由后端签发更长有效期的 token
 * （`jwt.remember-expire`，默认 7 天），否则仍是默认 2 小时。
 */
export const login = (username, password, rememberMe = false) =>
  http.post('/login', { username, password, remember_me: rememberMe }, { silentError: true })

/**
 * 修改密码（评审 1.4）。
 *
 * <p>用户名由后端从 token 取（不从请求体传），所以这里只需要原密码 + 新密码。
 */
export const changePassword = (oldPassword, newPassword) =>
  http.put('/users/password', { old_password: oldPassword, new_password: newPassword })

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
  groupId = null,
  sort = '',
  order = ''
) =>
  http.get('/devices', {
    params: {
      page,
      size,
      keyword: keyword || undefined,
      product_key: productKey || undefined,
      online_status: onlineStatus > 0 ? onlineStatus : undefined,
      group_id: groupId === null || groupId === undefined ? undefined : groupId,
      // 4.2：排序由后端做 —— 只排当前页是错的（1000+ 台、50 多页）
      sort: sort || undefined,
      order: order || undefined
    }
  })

/** 4.8：改设备名称（只改名称；启停走 updateDeviceStatus，删除走 deleteDevices） */
export const renameDevice = (deviceKey, deviceName) =>
  http.put(`/devices/${deviceKey}`, { device_name: deviceName })

/**
 * 4.7：注册批次列表。
 *
 * 由后端从**设备表推导**（device_key 形如 `<前缀>_<yyyyMMddHHmmss>_<序号>`），
 * 不再依赖浏览器 localStorage —— 换浏览器/清缓存不会"看起来丢了数据"。
 * 返回 {batches, ungrouped, deviceTotal}。
 */
export const listBatches = (limit = 50) =>
  http.get('/devices/batches', { params: { limit } })

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

// ===== F2 产品 / 设备启停 =====

/**
 * 启停产品。
 * 停用后该产品下**所有**设备会被拒绝 MQTT 接入（级联判断在认证处实时合成），
 * 且**不**修改子设备自身的启停状态 —— 产品恢复后各设备原状态准确复现。
 */
export const updateProductStatus = (productKey, status) =>
  http.put(`/products/${productKey}/status`, { status })

/** 6.1：改产品名称（只改 name；物模型走 updateProductTsl，启停走 updateProductStatus） */
export const renameProduct = (productKey, name) =>
  http.put(`/products/${productKey}`, { name })

/**
 * 6.1：删除产品。
 *
 * <p>后端带护栏：名下还有未删除设备时会被拒绝（4001）—— 否则那些设备会指向一个
 * 不存在的产品，认证/物模型/级联停用全部失效且界面上救不回来。
 */
export const deleteProduct = (productKey) =>
  http.delete(`/products/${productKey}`)

/**
 * 批量启停设备。
 * 停用后**立即**无法 MQTT 接入（认证校验 status 并清缓存），但数据全部保留，可一键恢复。
 * 与「批量删除」的区别：删除会级联清空遥测/日志/指令/影子且不可逆。
 */
export const updateDeviceStatus = (deviceKeys, status) =>
  http.put('/devices/status', { device_keys: deviceKeys, status })

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

// ===== E3 尾巴：告警规则管理（契约 §11）=====
// 补齐「有告警、无配置入口」的断头路：规则引擎早就在跑，但规则一直只能进 MySQL 敲 SQL。

/**
 * 规则分页。
 *
 * @param {number|null} enabled 启停筛选：`null`/`undefined` = 全部；`1` = 启用；`0` = 停用
 *   ⚠️ 同 listDevices 的 groupId —— **不能**写 `enabled || undefined`，`0` 会被当假值丢掉，
 *   导致「只看停用」静默失效。
 */
export const listRules = (page = 1, size = 20, productKey = '', keyword = '', enabled = null) =>
  http.get('/rules', {
    params: {
      page,
      size,
      product_key: productKey || undefined,
      keyword: keyword || undefined,
      enabled: enabled === null || enabled === undefined ? undefined : enabled
    }
  })

/** 规则详情 */
export const getRule = (id) => http.get(`/rules/${id}`)

/** 新建规则（返回 { id }） */
export const createRule = (data) => http.post('/rules', data)

/** 编辑规则（全字段覆盖） */
export const updateRule = (id, data) => http.put(`/rules/${id}`, data)

/** 启用 / 停用 */
export const updateRuleStatus = (id, enabled) => http.put(`/rules/${id}/status`, { enabled })

/** 删除规则（逻辑删除，历史告警保留） */
export const deleteRule = (id) => http.delete(`/rules/${id}`)

export default http
