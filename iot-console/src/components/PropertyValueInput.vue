<template>
  <!--
    物模型属性值控件：按 TSL 的 type 渲染对应输入件。
    纯展示型组件（prop + v-model），不含业务语义 —— 调试面板与告警规则页共用，
    避免"同一个属性在两处长得不一样"。
  -->
  <!--
    bool 有两种呈现，取决于"这个值是什么语义"：
    · boolStyle="select"（默认给"配置类"场景，如告警规则的下发内容）——
      这里选的是一个**常量**（本次下发的目标值），下拉「开 / 关」是明确的；
      用 el-switch 会歧义：灰色的 off 既像"关"、又像"未设置"、又像"不动它"。
    · boolStyle="switch"（给"调试类"场景，如设备调试面板）——
      那一行旁边有「当前 xx」做对照，开关读起来是"目标值 vs 当前值"，切换更快。
  -->
  <el-select
    v-if="prop.type === 'bool' && boolStyle === 'select'"
    :model-value="modelValue"
    :size="size"
    :style="width ? { width } : undefined"
    @update:model-value="onInput"
  >
    <el-option label="开" :value="true" />
    <el-option label="关" :value="false" />
  </el-select>

  <el-switch
    v-else-if="prop.type === 'bool'"
    :model-value="modelValue"
    @update:model-value="onInput"
  />

  <el-select
    v-else-if="prop.type === 'enum'"
    :model-value="modelValue"
    :size="size"
    :style="width ? { width } : undefined"
    placeholder="选择枚举值"
    @update:model-value="onInput"
  >
    <el-option
      v-for="o in prop.elements || []"
      :key="String(o.value)"
      :value="o.value"
      :label="o.key"
    />
  </el-select>

  <el-input-number
    v-else-if="prop.type === 'int' || prop.type === 'float'"
    :model-value="modelValue"
    :size="size"
    :min="prop.min ?? undefined"
    :max="prop.max ?? undefined"
    :step="stepOf(prop)"
    :precision="prop.type === 'int' ? 0 : undefined"
    controls-position="right"
    :style="width ? { width } : undefined"
    @update:model-value="onInput"
  />

  <el-input
    v-else
    :model-value="modelValue"
    :size="size"
    :style="width ? { width } : undefined"
    placeholder="输入值"
    @update:model-value="onInput"
  />
</template>

<script setup>
import { stepOf } from '../utils/tsl'

const props = defineProps({
  /** TSL 属性定义：{ key, name, type, unit, min, max, step, elements } */
  prop: { type: Object, required: true },
  modelValue: { default: undefined },
  /** 控件尺寸：'small' | 'default' */
  size: { type: String, default: 'small' },
  /** 控件宽度，如 '160px'；不传则由控件自适应 */
  width: { type: String, default: '' },
  /**
   * bool 属性的呈现方式：
   * · `'select'` —— 下拉「开 / 关」，用于**选一个常量**的场景（告警规则的下发内容）
   * · `'switch'` —— 开关，用于有「当前值」对照的**调试**场景（设备调试面板）
   *
   * 默认 `'switch'` 是为了不静默改变既有调用方的行为（调试面板）；
   * **新增"配置类"调用方请显式传 `'select'`** —— 常量选择用下拉才不会歧义。
   */
  boolStyle: { type: String, default: 'switch' }
})

const emit = defineEmits(['update:modelValue'])

/** 注意不要把这个函数命名为 emit —— 会遮蔽 defineEmits 的返回值 */
function onInput(v) {
  emit('update:modelValue', v)
}
</script>
