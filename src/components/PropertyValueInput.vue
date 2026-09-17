<template>
  <!--
    物模型属性值控件：按 TSL 的 type 渲染对应输入件。
    纯展示型组件（prop + v-model），不含业务语义 —— 调试面板与告警规则页共用，
    避免"同一个属性在两处长得不一样"。
  -->
  <el-switch v-if="prop.type === 'bool'" :model-value="modelValue" @update:model-value="onInput" />

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
  width: { type: String, default: '' }
})

const emit = defineEmits(['update:modelValue'])

/** 注意不要把这个函数命名为 emit —— 会遮蔽 defineEmits 的返回值 */
function onInput(v) {
  emit('update:modelValue', v)
}
</script>
