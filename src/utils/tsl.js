/**
 * 物模型（TSL）属性小工具 —— 供「调试面板」与「告警规则·联动下发」共用。
 *
 * 抽出来的原因：属性 → 控件 / 默认值的映射规则只应存在一处。
 * 两处各写一份，迟早出现"调试面板给 bool 默认 false、规则页给 true"这类不一致。
 */

/** 步长：物模型未配则按类型给默认（int 1 / float 0.1） */
export const stepOf = (prop) => prop?.step ?? (prop?.type === 'int' ? 1 : 0.1)

/** 设备上报/接口回来的值可能是 true / 'true' / 1 / '1'，统一判定 */
export const isTruthy = (v) => v === true || v === 'true' || v === 1 || v === '1'

/**
 * 按类型取默认值（物模型缺可选字段时必须安全）。
 *
 * ⚠️ 数值型**不要**兜底一个"看着合理"的区间（如 0~100）——
 * 曾把「大气压 1013」这类真实数据当场夹死。未配置就不限制：min/max 缺省时
 * 交给控件自身，只兜底一个起始值。
 */
export function defaultValueOf(prop) {
  if (!prop) return undefined
  if (prop.type === 'bool') return false
  if (prop.type === 'int' || prop.type === 'float') {
    return prop.min ?? 0
  }
  if (prop.type === 'enum') return (prop.elements || [])[0]?.value ?? ''
  return ''
}

/** 该产品下可写属性（联动下发只能写这些） */
export const writableProperties = (tsl) =>
  (tsl?.properties || []).filter((p) => p.access === 'readwrite')

/** 属性值的可读展示（bool → 开/关；enum → 枚举名；数值 → 带单位） */
export function displayValue(prop, v) {
  if (v === undefined || v === null || v === '') return '未设置'
  if (prop?.type === 'bool') return isTruthy(v) ? '开' : '关'
  if (prop?.type === 'enum') {
    const hit = (prop.elements || []).find((e) => String(e.value) === String(v))
    return hit ? hit.key : String(v)
  }
  return `${v}${prop?.unit || ''}`
}
