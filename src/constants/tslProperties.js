/**
 * 常用属性库（前端内置）。
 *
 * <h3>解决什么</h3>
 * 在「产品 → 物模型 → 属性」里点「+ 加一个属性」会得到一个**空行**，
 * 用户得手敲 key、名称、类型、单位、量程 —— 而温度、湿度、电压这些属性的定义
 * 在行业里几乎是有共识的（℃ / float / readonly / -40~125）。这里把它们做成可勾选的目录。
 *
 * <h3>与「通用物模型模板」的分工</h3>
 * - **模板**（`tslTemplates.js`）：一整台设备的物模型（属性 + 事件 + 服务），粒度是"设备型号"
 * - **本文件**：单个属性的定义，粒度是"一个量" —— 用来**往已有物模型上补一两个属性**
 *   两者粒度不同，所以分开；用户"从模板起了个头、再补个电池电量"是很常见的路径。
 *
 * <h3>字段必须与编辑区行结构一致</h3>
 * 结构与 `ProductDetail` 里属性行的形状**逐字段对齐**（见 `normalizeProp`）：
 * `{ key, name, type, unit, access, min, max, step, elements: [] }`，
 * 其中 min/max/step 用数字（空则 `''`）。不对齐的话，填进去一编辑就被改形。
 * key 也遵守后端 `TslValidator`：仅 `[A-Za-z0-9_]`，且三类之间全局唯一。
 *
 * <h3>为什么是静态常量而不是表</h3>
 * 同 `tslTemplates.js` 的理由：这是**静态领域知识**（行业惯例），
 * 放前端 = 无后端改动、无重启、离线可用。将来要做"用户自定义常用属性"再迁库。
 */

/** 属性分组：顺序即展示顺序 */
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
    min: '', max: '', step: '', note: '枚举型，值由设备侧判定',
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

/** 按分组取（未指定分组时返回全部） */
export const propertiesOfGroup = (groupKey) =>
  groupKey ? COMMON_PROPERTIES.filter((x) => x.group === groupKey) : COMMON_PROPERTIES

/** 目录摘要：如「32 个常用属性 · 6 组」 */
export const catalogSummary = () =>
  `${COMMON_PROPERTIES.length} 个常用属性 · ${PROPERTY_GROUPS.length} 组`

/**
 * 转成编辑区属性行（深拷贝，避免被编辑区改到目录本体）。
 *
 * min/max/step 空值统一成 `''`（而不是 undefined）—— 与 `addProperty()` 造的空行保持一致，
 * 否则同一张表里两种"空"并存，`buildTsl` 的 `!== '' && != null` 判断会漏。
 */
export const toPropertyRow = (item) => ({
  key: item.key,
  name: item.name,
  type: item.type,
  unit: item.unit ?? '',
  access: item.access ?? 'readonly',
  min: item.min === '' || item.min == null ? '' : item.min,
  max: item.max === '' || item.max == null ? '' : item.max,
  step: item.step === '' || item.step == null ? '' : item.step,
  elements: (item.elements || []).map((e) => ({ ...e }))
})
