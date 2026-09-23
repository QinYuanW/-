/**
 * 通用物模型模板库（前端内置）。
 *
 * <h3>为什么放在前端常量而不是后端</h3>
 * 通用物模型本质是**静态的领域知识**（编辑器里的"范文"），不含用户数据：
 * 放前端 = 无后端改动、无重启、离线可用、答辩演示零依赖。
 * 将来要做「用户自定义模板 / 跨项目共享」再迁到后端表即可，届时这里就是默认值。
 *
 * <h3>字段形状必须与保存路径一致</h3>
 * 这里的结构与 `ProductDetail.buildTsl()` 的产出**逐字段对齐** ——
 * 否则套用后一保存就被改形（例如 min/max 变成字符串、elements 的 value 变成字符串）。
 * 也与后端 `ProductServiceImpl.validateTsl()` 的约束对齐：
 *  - key 仅 `[A-Za-z0-9_]{1,64}`，且 **properties/services/events 三类之间全局唯一**
 *  - name 必填非空
 *  - properties.type ∈ int/float/bool/string/enum；access ∈ readonly/readwrite
 *  - type=enum 必须有 elements，每项 {value, key}（key 是展示文本）
 *  - events.type ∈ info/alert/fault；services.callType ∈ async/sync
 */

/** 属性类型（与后端白名单一致，供 UI 提示用） */
export const PROPERTY_TYPES = ['int', 'float', 'bool', 'string', 'enum']

/** 事件级别 */
export const EVENT_TYPES = [
  { value: 'info', label: '信息' },
  { value: 'alert', label: '告警' },
  { value: 'fault', label: '故障' }
]

const p = (key, name, type, opts = {}) => ({ key, name, type, ...opts })
const ev = (key, name, type = 'alert') => ({ key, name, type })
const svc = (key, name, callType = 'async', inputData = []) => ({ key, name, callType, inputData, outputData: [] })

export const TSL_TEMPLATES = [
  {
    id: 'temp_humidity',
    name: '温湿度传感器',
    category: '环境监测',
    description: '最常见的环境采集设备。两个只读属性 + 高温告警事件，适合 SHT3x / DHT22 一类传感器。',
    tsl: {
      properties: [
        p('temperature', '温度', 'float', { unit: '℃', access: 'readonly', min: -40, max: 125, step: 0.1 }),
        p('humidity', '湿度', 'float', { unit: '%', access: 'readonly', min: 0, max: 100, step: 0.1 })
      ],
      events: [ev('high_temp_alarm', '高温告警', 'alert')],
      services: []
    }
  },
  {
    id: 'air_quality',
    name: 'CO2 空气质量',
    category: '环境监测',
    description: '空气质量监测：CO2 浓度 + 温湿度，并把浓度分档成枚举便于展示与联动。',
    tsl: {
      properties: [
        p('co2', 'CO2 浓度', 'int', { unit: 'ppm', access: 'readonly', min: 400, max: 5000 }),
        p('temperature', '温度', 'float', { unit: '℃', access: 'readonly', min: -40, max: 125, step: 0.1 }),
        p('humidity', '湿度', 'float', { unit: '%', access: 'readonly', min: 0, max: 100, step: 0.1 }),
        p('air_level', '空气等级', 'enum', {
          access: 'readonly',
          elements: [
            { value: 1, key: '优' },
            { value: 2, key: '良' },
            { value: 3, key: '轻度污染' }
          ]
        })
      ],
      events: [ev('co2_alarm', 'CO2 超限告警', 'alert')],
      services: []
    }
  },
  {
    id: 'smart_switch',
    name: '智能开关（继电器）',
    category: '控制类',
    description: '可写开关 + 电压电流监测 + 开关指令与倒计时服务。控制类设备的最小可用模型。',
    tsl: {
      properties: [
        p('power', '开关', 'bool', { access: 'readwrite' }),
        p('voltage', '电压', 'float', { unit: 'V', access: 'readonly', min: 0, max: 300, step: 0.1 }),
        p('current', '电流', 'float', { unit: 'A', access: 'readonly', min: 0, max: 20, step: 0.01 })
      ],
      events: [ev('switch_toggled', '开关状态变化', 'info')],
      services: [
        svc('set_power', '设置开关', 'async', [p('power', '目标状态', 'bool', { access: 'readwrite' })]),
        svc('set_timer', '倒计时关闭', 'async', [p('seconds', '延迟秒数', 'int', { access: 'readwrite', min: 0, max: 86400 })])
      ]
    }
  },
  {
    id: 'smart_light',
    name: '智能灯（调光调色）',
    category: '控制类',
    description: '亮度与色温可调，含灯效模式枚举 —— 与 WS2812 一类的灯带固件契约对齐。',
    tsl: {
      properties: [
        p('power', '开关', 'bool', { access: 'readwrite' }),
        p('brightness', '亮度', 'int', { access: 'readwrite', min: 0, max: 100 }),
        p('color_temp', '色温', 'int', { unit: 'K', access: 'readwrite', min: 2700, max: 6500 }),
        p('light_mode', '灯效模式', 'enum', {
          access: 'readwrite',
          elements: [
            { value: 0, key: '常亮' },
            { value: 1, key: '呼吸灯' },
            { value: 2, key: '彩虹' }
          ]
        })
      ],
      events: [],
      services: [svc('set_brightness', '设置亮度', 'async', [p('brightness', '目标亮度', 'int', { access: 'readwrite', min: 0, max: 100 })])]
    }
  },
  {
    id: 'weather_station',
    name: '小型气象站',
    category: '环境监测',
    description: '六要素气象采集，含暴雨与大风两个告警。演示"同一设备多属性 + 多事件"的形态。',
    tsl: {
      properties: [
        p('temperature', '温度', 'float', { unit: '℃', access: 'readonly', min: -40, max: 60, step: 0.1 }),
        p('humidity', '湿度', 'float', { unit: '%', access: 'readonly', min: 0, max: 100, step: 0.1 }),
        p('pressure', '大气压', 'float', { unit: 'hPa', access: 'readonly', min: 800, max: 1100, step: 0.1 }),
        p('illuminance', '光照强度', 'int', { unit: 'lux', access: 'readonly', min: 0, max: 200000 }),
        p('rainfall', '降雨量', 'float', { unit: 'mm', access: 'readonly', min: 0, max: 500, step: 0.1 }),
        p('wind_speed', '风速', 'float', { unit: 'm/s', access: 'readonly', min: 0, max: 60, step: 0.1 })
      ],
      events: [ev('heavy_rain_alarm', '暴雨告警', 'alert'), ev('high_wind_alarm', '大风告警', 'fault')],
      services: []
    }
  },
  {
    id: 'water_level',
    name: '水位 / 水浸监测',
    category: '安防类',
    description: '水位读数 + 漏水干接点 + 电池电量，含漏水故障事件。低功耗上报型设备的典型形态。',
    tsl: {
      properties: [
        p('level', '水位', 'float', { unit: 'cm', access: 'readonly', min: 0, max: 500, step: 0.1 }),
        p('leak', '漏水', 'bool', { access: 'readonly' }),
        p('battery', '电池电量', 'int', { unit: '%', access: 'readonly', min: 0, max: 100 })
      ],
      events: [ev('water_leak_alarm', '漏水告警', 'fault')],
      services: []
    }
  }
]

/** 按 id 取模板 */
export const findTemplate = (id) => TSL_TEMPLATES.find((t) => t.id === id) || null

/**
 * 把后端的自定义模板（TslTemplateVO）转成与内置模板**同构**的形状，
 * 这样模板列表 / 套用对话框 / 套用逻辑都不必区分来源。
 *
 * @returns {{ id, name, category, description, tsl, builtin, source, rawId }}
 *   - `id` 用 `custom_<rawId>` 前缀，避免与内置模板 id 撞车（列表里要当 key 用）
 *   - `rawId` 保留后端主键，编辑/删除时要用
 */
export function toUnifiedCustom(vo) {
  return {
    id: `custom_${vo.id}`,
    rawId: vo.id,
    name: vo.name,
    category: vo.category || '我的模板',
    description: vo.description || '（无说明）',
    tsl: vo.tsl && typeof vo.tsl === 'object' ? vo.tsl : { properties: [], events: [], services: [] },
    builtin: false,
    source: 'custom'
  }
}

/** 内置模板的统一形状（补上 source / builtin，便于与自定义的一起渲染） */
export const UNIFIED_BUILTIN = TSL_TEMPLATES.map((t) => ({ ...t, builtin: true, source: 'builtin' }))

/**
 * 合并展示列表：**内置在前、自定义在后**。
 *
 * 顺序是有意的 —— 内置是"随版本走的范文"，位置稳定、用户下次还能在同一位找到；
 * 自定义是自己新建的，放后面不打断既有肌肉记忆。
 */
export function allTemplates(customList = []) {
  return [...UNIFIED_BUILTIN, ...customList.map(toUnifiedCustom)]
}


/** 模板规模摘要，如「4 属性 · 1 事件 · 1 服务」 */
export function templateSummary(tpl) {
  const t = tpl?.tsl || {}
  const parts = []
  parts.push(`${(t.properties || []).length} 属性`)
  if ((t.events || []).length) parts.push(`${t.events.length} 事件`)
  if ((t.services || []).length) parts.push(`${t.services.length} 服务`)
  return parts.join(' · ')
}

/** 深拷贝一份模板的 tsl（避免被编辑区改到模板本体） */
export const cloneTsl = (tsl) => JSON.parse(JSON.stringify(tsl || {}))

/**
 * 把模板填进编辑区的三条列表。
 *
 * @param mode 'replace' 整体替换 | 'append' 追加（**同名 key 跳过**，不覆盖已有定义）
 * @returns {{ properties, events, services, added: number, skipped: number }}
 */
export function mergeTsl(current, incoming, mode = 'replace') {
  const src = cloneTsl(incoming)
  const normalizeProp = (x) => ({ ...x, unit: x.unit ?? '', min: x.min ?? '', max: x.max ?? '', step: x.step ?? '', elements: (x.elements || []).map((e) => ({ ...e })) })
  const normalizeSvc = (x) => ({ ...x, callType: x.callType || 'async', inputData: (x.inputData || []).map((i) => ({ ...i })), outputData: (x.outputData || []).map((i) => ({ ...i })) })

  const inProps = (src.properties || []).map(normalizeProp)
  const inEvents = (src.events || []).map((e) => ({ ...e }))
  const inSvcs = (src.services || []).map(normalizeSvc)

  if (mode === 'replace') {
    return {
      properties: inProps,
      events: inEvents,
      services: inSvcs,
      added: inProps.length + inEvents.length + inSvcs.length,
      skipped: 0
    }
  }

  const cur = {
    properties: (current?.properties || []).map(normalizeProp),
    events: (current?.events || []).map((e) => ({ ...e })),
    services: (current?.services || []).map(normalizeSvc)
  }
  // 三类共享一个命名空间（后端 validateTsl 就是这么校验的），所以判重要跨三类
  const taken = new Set([
    ...cur.properties.map((x) => x.key),
    ...cur.events.map((x) => x.key),
    ...cur.services.map((x) => x.key)
  ])
  let added = 0
  let skipped = 0
  const pushIfNew = (list, item) => {
    if (taken.has(item.key)) {
      skipped++
      return
    }
    taken.add(item.key)
    list.push(item)
    added++
  }
  inProps.forEach((x) => pushIfNew(cur.properties, x))
  inEvents.forEach((x) => pushIfNew(cur.events, x))
  inSvcs.forEach((x) => pushIfNew(cur.services, x))
  return { ...cur, added, skipped }
}
