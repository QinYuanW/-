/**
 * 常用物模型项库（前端内置）：属性 / 事件 / 服务三类。
 *
 * <h3>解决什么</h3>
 * 物模型编辑器里点「+ 加一个属性/事件/服务」会得到一个**空行**，
 * 用户得手敲 key、名称、类型、单位、量程 —— 而温度、湿度、电压、高温告警这些定义
 * 在行业里几乎是有共识的。这里把它们做成可勾选的目录。
 *
 * <h3>与「通用物模型模板」的分工</h3>
 * - **模板**（`tslTemplates.js`）：一整台设备的物模型（属性 + 事件 + 服务），粒度是"设备型号"
 * - **本文件**：单个属性 / 事件 / 服务的定义，粒度是"一个量 / 一条告警 / 一个动作"
 *   两者粒度不同所以分开："从模板起了个头、再补个电池电量"是很常见的路径
 *
 * <h3>字段必须与编辑区行结构一致</h3>
 * 三类各自与 `TslEditor` 里的行形状**逐字段对齐**（见 `utils/tslBuild.js` 的 normalizeTsl）：
 * - 属性 `{ key, name, type, unit, access, min, max, step, elements[] }`
 * - 事件 `{ key, name, type }`（type ∈ info/alert/fault）
 * - 服务 `{ key, name, callType, inputData[], outputData[] }`（callType ∈ async/sync）
 * 不对齐的话，填进去一编辑就被改形。
 * key 也遵守后端 `TslValidator`：仅 `[A-Za-z0-9_]`，且三类之间全局唯一。
 *
 * <h3>为什么是静态常量而不是表</h3>
 * 同 `tslTemplates.js` 的理由：这是**静态领域知识**（行业惯例），
 * 放前端 = 无后端改动、无重启、离线可用。将来要做"用户自定义常用项"再迁库。
 */

/** 分组（属性用） */
export const PROPERTY_GROUPS = [
  { key: 'env', label: '环境量' },
  { key: 'elec', label: '电气量' },
  { key: 'ctrl', label: '控制量' },
  { key: 'pos', label: '位置与状态' },
  { key: 'sec', label: '安防量' },
  { key: 'meter', label: '计量量' }
]

const p = (group, key, name, type, opts = {}) => ({
  group,
  key,
  name,
  type,
  unit: opts.unit ?? '',
  access: opts.access ?? 'readonly',
  min: opts.min ?? '',
  max: opts.max ?? '',
  step: opts.step ?? '',
  note: opts.note ?? ''
})

export const COMMON_PROPERTIES = [
  // ── 环境量 ──
  p('env', 'temperature', '温度', 'float', { unit: '℃', min: -40, max: 125, step: 0.1, note: 'SHT3x / DHT22 一类传感器' }),
  p('env', 'humidity', '湿度', 'float', { unit: '%', min: 0, max: 100, step: 0.1 }),
  p('env', 'co2', 'CO2 浓度', 'int', { unit: 'ppm', min: 400, max: 5000, note: '常见量程 400~5000' }),
  p('env', 'pm25', 'PM2.5', 'int', { unit: 'µg/m³', min: 0, max: 1000 }),
  p('env', 'pm10', 'PM10', 'int', { unit: 'µg/m³', min: 0, max: 1000 }),
  p('env', 'pressure', '大气压', 'float', { unit: 'hPa', min: 300, max: 1100, step: 0.1, note: 'BMP280 等' }),
  p('env', 'illuminance', '光照强度', 'int', { unit: 'lux', min: 0, max: 200000 }),
  p('env', 'noise', '噪声', 'float', { unit: 'dB', min: 30, max: 120, step: 0.1 }),
  p('env', 'air_level', '空气质量等级', 'enum', {
    note: '枚举型，值由设备侧判定',
    elements: [{ value: 1, key: '优' }, { value: 2, key: '良' }, { value: 3, key: '轻度污染' }, { value: 4, key: '重度污染' }]
  }),

  // ── 电气量 ──
  p('elec', 'voltage', '电压', 'float', { unit: 'V', min: 0, max: 300, step: 0.1 }),
  p('elec', 'current', '电流', 'float', { unit: 'A', min: 0, max: 20, step: 0.01 }),
  p('elec', 'power', '功率', 'float', { unit: 'W', min: 0, max: 5000, step: 0.1 }),
  p('elec', 'energy', '累计电量', 'float', { unit: 'kWh', min: 0, max: 999999, step: 0.01, note: '只增不减的累计值' }),
  p('elec', 'battery', '电池电量', 'int', { unit: '%', min: 0, max: 100, note: '低功耗上报型设备常用' }),
  p('elec', 'rssi', '信号强度', 'int', { unit: 'dBm', min: -130, max: 0 }),

  // ── 控制量（可写）──
  p('ctrl', 'switch', '开关', 'bool', { access: 'readwrite', note: '最常用的可写布尔量' }),
  p('ctrl', 'brightness', '亮度', 'int', { access: 'readwrite', min: 0, max: 100 }),
  p('ctrl', 'color_temp', '色温', 'int', { unit: 'K', access: 'readwrite', min: 2700, max: 6500 }),
  p('ctrl', 'target_temp', '目标温度', 'float', { unit: '℃', access: 'readwrite', min: 16, max: 30, step: 0.1, note: '温控类设备用' }),
  p('ctrl', 'work_mode', '工作模式', 'enum', {
    access: 'readwrite', note: '枚举型可写量',
    elements: [{ value: 0, key: '关闭' }, { value: 1, key: '自动' }, { value: 2, key: '手动' }]
  }),

  // ── 位置与状态 ──
  p('pos', 'longitude', '经度', 'float', { unit: '°', min: -180, max: 180, step: 0.000001 }),
  p('pos', 'latitude', '纬度', 'float', { unit: '°', min: -90, max: 90, step: 0.000001 }),
  p('pos', 'speed', '速度', 'float', { unit: 'km/h', min: 0, max: 300, step: 0.1 }),
  p('pos', 'online', '在线状态', 'bool', { note: '设备自报的在线位（平台侧另有一份判定）' }),

  // ── 安防量（干接点类）──
  p('sec', 'water_leak', '水浸', 'bool', { note: '漏水/水浸干接点' }),
  p('sec', 'smoke', '烟雾', 'bool'),
  p('sec', 'door', '门磁', 'bool'),
  p('sec', 'motion', '人体感应', 'bool'),
  p('sec', 'flame', '火焰', 'bool'),

  // ── 计量量 ──
  p('meter', 'level', '水位/物位', 'float', { unit: 'cm', min: 0, max: 500, step: 0.1 }),
  p('meter', 'rainfall', '降雨量', 'float', { unit: 'mm', min: 0, max: 500, step: 0.1 }),
  p('meter', 'wind_speed', '风速', 'float', { unit: 'm/s', min: 0, max: 60, step: 0.1 }),
  p('meter', 'flow', '流量', 'float', { unit: 'L/min', min: 0, max: 1000, step: 0.01 }),
  p('meter', 'count', '累计次数', 'int', { min: 0, max: 999999999, note: '动作/计数类累计值' })
]

/** 分组（事件用） */
export const EVENT_GROUPS = [
  { key: 'alarm', label: '阈值与告警' },
  { key: 'fault', label: '故障' },
  { key: 'state', label: '状态变化' }
]

const e = (group, key, name, type, opts = {}) => ({
  group, key, name, type, note: opts.note ?? ''
})

export const COMMON_EVENTS = [
  // ── 阈值与告警 ──
  e('alarm', 'high_temp_alarm', '高温告警', 'alert', { note: '配合阈值规则用，如温度 > 50' }),
  e('alarm', 'low_temp_alarm', '低温告警', 'alert', { note: '如温度 < 0' }),
  e('alarm', 'over_threshold', '超阈值告警', 'alert', { note: '通用阈值越界（阈值由规则侧定）' }),
  e('alarm', 'low_battery', '低电量告警', 'alert', { note: '电池型设备必备' }),
  e('alarm', 'offline_alarm', '离线告警', 'alert', { note: '设备自报心跳丢失（平台侧另有超时判定）' }),
  e('alarm', 'water_leak_alarm', '漏水告警', 'alert'),
  e('alarm', 'gas_alarm', '燃气告警', 'alert'),
  e('alarm', 'tamper_alarm', '防拆告警', 'alert', { note: '外壳被拆/被移动' }),

  // ── 故障 ──
  e('fault', 'device_fault', '设备故障', 'fault', { note: '设备侧自检异常' }),
  e('fault', 'sensor_fault', '传感器故障', 'fault', { note: '读数为无效值/通信失败' }),
  e('fault', 'power_fault', '供电异常', 'fault'),

  // ── 状态变化 ──
  e('state', 'device_reboot', '设备重启', 'info', { note: '重启后补报，便于排查"为什么掉线"' }),
  e('state', 'config_changed', '配置变更', 'info'),
  e('state', 'switch_changed', '开关状态变化', 'info', { note: '需要记录每次开合时用；只在遥测里看当前值则不必' }),
  e('state', 'firmware_upgraded', '固件升级完成', 'info')
]

/** 分组（服务用） */
export const SERVICE_GROUPS = [
  { key: 'control', label: '控制类' },
  { key: 'config', label: '配置类' },
  { key: 'maint', label: '维护类' }
]

const s = (group, key, name, inputData, opts = {}) => ({
  group,
  key,
  name,
  callType: opts.callType ?? 'async',
  inputData: inputData ?? [],
  outputData: opts.outputData ?? [],
  note: opts.note ?? ''
})

/** 服务入参简写 */
const pi = (key, name, type, note = '') => ({ key, name, type, note })

export const COMMON_SERVICES = [
  // ── 控制类（与「控制量」属性成对：属性是"当前值"，服务是"设定值"）──
  s('control', 'set_switch', '设置开关', [pi('switch', '开关', 'bool')], { note: '下发 {switch:true/false}' }),
  s('control', 'set_brightness', '设置亮度', [pi('brightness', '亮度', 'int', '0~100')]),
  s('control', 'set_color_temp', '设置色温', [pi('color_temp', '色温', 'int', '单位 K，如 2700~6500')]),
  s('control', 'set_mode', '设置工作模式', [pi('work_mode', '工作模式', 'int', '枚举值，与属性 work_mode 的 elements 对应')]),
  s('control', 'set_target_temp', '设置目标温度', [pi('target_temp', '目标温度', 'float', '单位 ℃')]),

  // ── 配置类 ──
  s('config', 'set_report_interval', '设置上报间隔', [pi('interval', '上报间隔', 'int', '单位秒')], { note: '省电与流量控制常用' }),
  s('config', 'set_threshold', '设置阈值', [pi('property_key', '属性 key', 'string'), pi('threshold', '阈值', 'float')], { note: '把阈值下发给设备侧自行判断' }),
  s('config', 'set_mqtt_server', '设置服务器地址', [pi('host', '地址', 'string'), pi('port', '端口', 'int')], { note: '换平台/内网部署时用' }),

  // ── 维护类 ──
  s('maint', 'query_status', '查询设备状态', [], {
    callType: 'sync', note: '同步调用：平台等设备回复',
    outputData: [pi('online', '在线状态', 'bool'), pi('firmware_version', '固件版本', 'string'), pi('uptime', '运行时长', 'int', '单位秒')]
  }),
  s('maint', 'reboot', '重启设备', [], { note: '设备侧收到即执行，通常不等回复' }),
  s('maint', 'factory_reset', '恢复出厂设置', [], { note: '⚠️ 慎用：会清掉设备侧配置与已配网信息' }),
  s('maint', 'ota_upgrade', '固件升级', [pi('url', '固件地址', 'string'), pi('version', '目标版本', 'string'), pi('md5', 'MD5 校验值', 'string', '可选，用于校验完整性')], { note: '与 F1 OTA 对应' })
]

// ── 归类索引：一个 kind 一把目录 ──────────────────────────────
export const CATALOGS = {
  properties: { items: COMMON_PROPERTIES, groups: PROPERTY_GROUPS, label: '属性', title: '添加常用属性' },
  events: { items: COMMON_EVENTS, groups: EVENT_GROUPS, label: '事件', title: '添加常用事件' },
  services: { items: COMMON_SERVICES, groups: SERVICE_GROUPS, label: '服务', title: '添加常用服务' }
}

/** 目录摘要：如「34 个常用属性 · 6 组」 */
export const catalogSummary = (kind = 'properties') => {
  const c = CATALOGS[kind]
  return `${c.items.length} 个常用${c.label} · ${c.groups.length} 组`
}

/** 按 kind 取全部条目 */
export const itemsOf = (kind) => CATALOGS[kind]?.items || []

/**
 * 转成编辑区行（深拷贝，避免被编辑区改到目录本体）。
 *
 * 三类形状不同，各按自己的行结构输出 —— 与 `utils/tslBuild.js` 的 normalizeTsl 对齐。
 * 空值统一成 `''`（而不是 undefined），否则同一张表里两种"空"并存。
 */
export const toRow = (kind, item) => {
  if (kind === 'events') {
    return { key: item.key, name: item.name, type: item.type || 'info' }
  }
  if (kind === 'services') {
    return {
      key: item.key,
      name: item.name,
      callType: item.callType || 'async',
      inputData: (item.inputData || []).map((x) => ({ key: x.key, name: x.name, type: x.type || 'string' })),
      outputData: (item.outputData || []).map((x) => ({ key: x.key, name: x.name, type: x.type || 'string' }))
    }
  }
  return {
    key: item.key,
    name: item.name,
    type: item.type,
    unit: item.unit ?? '',
    access: item.access ?? 'readonly',
    min: item.min === '' || item.min == null ? '' : item.min,
    max: item.max === '' || item.max == null ? '' : item.max,
    step: item.step === '' || item.step == null ? '' : item.step,
    elements: (item.elements || []).map((x) => ({ ...x }))
  }
}
