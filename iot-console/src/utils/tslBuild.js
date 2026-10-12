/**
 * 物模型（TSL）的「编辑区 ↔ 报文」双向转换与本地校验。
 *
 * <h3>为什么要有这个文件</h3>
 * 这套逻辑原先只存在于 `ProductDetail.vue` 内部（`loadTsl` 的映射 + `buildTsl` 的校验）。
 * 现在**模板编辑**也要用同一套编辑器，如果各写一份，两边迟早不一致 ——
 * 典型后果是"产品里能存、模板里存不进去"，用户完全无从理解。
 *
 * 与此对应的是后端 `util/TslValidator`：**同一套规则，前后端各有一个权威实现**，
 * 前端这份负责"尽早给出人话提示"，后端那份负责"最终把关"。
 * 两边规则必须同步改 —— 改这里时记得看一眼 `TslValidator`。
 */

/** 属性数据类型（与后端 ALLOWED_TYPES 一致） */
export const TSL_TYPES = ['int', 'float', 'bool', 'string', 'enum']

/**
 * 把后端返回的 tsl 转成编辑区行结构。
 *
 * 空值统一成 `''` 而不是 undefined —— 否则同一张表里两种"空"并存，
 * `buildTsl` 里 `!== '' && != null` 这类判断会漏。
 */
export function normalizeTsl(raw) {
  const t = raw || {}
  return {
    properties: (t.properties || []).map((p) => ({
      key: p.key || '',
      name: p.name || '',
      type: p.type || 'float',
      unit: p.unit ?? '',
      access: p.access || 'readonly',
      min: p.min ?? '',
      max: p.max ?? '',
      step: p.step ?? '',
      elements: (p.elements || []).map((e) => ({ value: e.value ?? '', key: e.key ?? '' }))
    })),
    events: (t.events || []).map((e) => ({
      key: e.key || '',
      name: e.name || '',
      type: e.type || 'info'
    })),
    services: (t.services || []).map((s) => ({
      key: s.key || '',
      name: s.name || '',
      callType: s.callType || 'async',
      inputData: (s.inputData || []).map((p) => ({ ...p })),
      outputData: (s.outputData || []).map((p) => ({ ...p }))
    }))
  }
}

/**
 * 判断一行是不是"刚点加号、还什么都没填"的空行。
 *
 * ⚠️ 这类行必须**跳过校验**：下游 `clean` 本来就会把它们过滤掉（只保留有 key 的），
 * 若校验阶段却对它们报错，就出现"自己和下游打架"——用户点了一下「+ 加一个属性」试手感，
 * 结果整个物模型存不进去，且错误信息指向一个他没打算填的行。
 */
const isBlankRow = (item) =>
  !String(item.key || '').trim() &&
  !String(item.name || '').trim() &&
  (item.elements || []).length === 0 &&
  !String(item.unit || '').trim()

/**
 * 编辑区 → 待落库的 tsl，并做本地校验。
 *
 * @returns {{ errors: string[], tsl: object }} errors 非空时不要提交（后端也会拒，但让它先给出人话）
 */
export function buildTsl({ properties, events, services }) {
  const sections = { properties: properties || [], events: events || [], services: services || [] }
  const seen = new Set()
  const errors = []

  for (const [section, list] of Object.entries(sections)) {
    list.forEach((item, i) => {
      if (isBlankRow(item)) return // 空行忽略（与下游 clean 的口径一致）
      const at = `${section}[${i}]`
      const k = String(item.key || '').trim()
      if (!k) return errors.push(`${at}.key 不能为空`)
      if (!/^[A-Za-z0-9_]{1,64}$/.test(k)) {
        errors.push(`${at}.key 仅允许字母/数字/下划线（≤64）: ${k}`)
      }
      if (seen.has(k)) errors.push(`${at}.key 重复: ${k}`)
      seen.add(k)
      if (!String(item.name || '').trim()) errors.push(`${at}.name 不能为空`)
      if (section === 'properties') {
        if (!TSL_TYPES.includes(item.type)) errors.push(`${at}.type 仅允许 int/float/bool/string/enum`)
        if (!['readonly', 'readwrite'].includes(item.access)) errors.push(`${at}.access 仅允许 readonly/readwrite`)
        if (item.type === 'enum') {
          const elems = Array.isArray(item.elements) ? item.elements : []
          if (!elems.length) errors.push(`${at}.type=enum 时必须配置 elements 枚举项`)
          elems.forEach((e, j) => {
            if (e.value === '' || e.value == null || !String(e.key || '').trim()) {
              errors.push(`${at}.elements[${j}] 每项需含 value 与 key（key 为展示文本）`)
            }
          })
        }
      }
    })
  }

  const cleanProp = (list) =>
    list
      .filter((item) => String(item.key || '').trim())
      .map((item) => {
        const out = { key: String(item.key).trim(), name: String(item.name).trim() }
        if (item.type) out.type = item.type
        if (item.unit !== undefined && item.unit !== '') out.unit = item.unit
        if (item.access) out.access = item.access
        if (item.min !== '' && item.min != null) out.min = Number(item.min)
        if (item.max !== '' && item.max != null) out.max = Number(item.max)
        if (item.step !== '' && item.step != null) out.step = Number(item.step)
        if (item.type === 'enum' && Array.isArray(item.elements) && item.elements.length) {
          out.elements = item.elements
            .filter((e) => e.value !== '' && e.value != null && String(e.key || '').trim())
            .map((e) => {
              const v = String(e.value).trim()
              return { value: /^-?\d+(\.\d+)?$/.test(v) ? Number(v) : v, key: String(e.key).trim() }
            })
        }
        return out
      })

  return {
    errors,
    tsl: {
      properties: cleanProp(sections.properties),
      events: sections.events
        .filter((e) => String(e.key || '').trim())
        .map((e) => ({ key: String(e.key).trim(), name: String(e.name).trim(), type: e.type || 'info' })),
      services: sections.services
        .filter((s) => String(s.key || '').trim())
        .map((s) => ({
          key: String(s.key).trim(),
          name: String(s.name).trim(),
          callType: s.callType || 'async',
          inputData: (s.inputData || []).filter((p) => String(p.key || '').trim()),
          outputData: (s.outputData || []).filter((p) => String(p.key || '').trim())
        }))
    }
  }
}

/** 空物模型的三段（新建时用） */
export const emptyTslSections = () => ({ properties: [], events: [], services: [] })

/** 规模摘要：「4 属性 · 1 事件 · 2 服务」 */
export function tslSummary({ properties = [], events = [], services = [] }) {
  const parts = [`${properties.length} 属性`]
  if (events.length) parts.push(`${events.length} 事件`)
  if (services.length) parts.push(`${services.length} 服务`)
  return parts.join(' · ')
}
