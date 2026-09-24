<template>
  <el-dialog v-model="visible" title="添加常用属性" width="780px" top="6vh">
    <div class="cp-head">
      <el-input v-model="keyword" placeholder="搜索 key 或名称，如 temperature / 温度" clearable :prefix-icon="Search" />
      <span class="cp-sum">{{ catalogSummary() }}</span>
    </div>

    <div class="cp-body">
      <div v-for="g in visibleGroups" :key="g.key" class="cp-group">
        <div class="cp-group-label">{{ g.label }}<span class="cp-group-count">{{ byGroup(g.key).length }}</span></div>
        <div class="cp-grid">
          <div
            v-for="item in byGroup(g.key)"
            :key="item.key"
            class="cp-item"
            :class="{ picked: picked.has(item.key), disabled: exists(item.key) }"
            @click="toggle(item)"
          >
            <el-checkbox
              :model-value="picked.has(item.key)"
              :disabled="exists(item.key)"
              @click.stop
              @change="toggle(item)"
            />
            <div class="cp-main">
              <div class="cp-line1">
                <span class="cp-name">{{ item.name }}</span>
                <span class="cp-key">{{ item.key }}</span>
                <span v-if="exists(item.key)" class="cp-badge exist">已存在</span>
                <span v-else-if="item.access === 'readwrite'" class="cp-badge rw">可写</span>
              </div>
              <div class="cp-line2">
                <span>{{ item.type }}</span>
                <span v-if="item.unit">· {{ item.unit }}</span>
                <span v-if="rangeText(item)">· {{ rangeText(item) }}</span>
                <span v-if="item.note" class="cp-note">· {{ item.note }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="!visibleGroups.length" class="cp-empty">没有匹配的属性（换个关键词试试）</div>
    </div>

    <template #footer>
      <div class="cp-footer">
        <span class="cp-picked">已选 <b>{{ picked.size }}</b> 项<span v-if="skippedCount">，其中 {{ skippedCount }} 项已存在会被跳过</span></span>
        <div>
          <el-button @click="visible = false">取消</el-button>
          <el-button type="primary" :disabled="!picked.size" @click="confirm">添加</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { COMMON_PROPERTIES, PROPERTY_GROUPS, catalogSummary, toPropertyRow } from '../constants/tslProperties'

const props = defineProps({
  /** 当前物模型里已有的属性 key —— 已有的在弹窗里禁用并标「已存在」，避免加完保存时报 key 重复 */
  existingKeys: { type: Array, default: () => [] }
})
const emit = defineEmits(['confirm'])

const visible = ref(false)
const keyword = ref('')
const picked = ref(new Set())

const exists = (key) => props.existingKeys.includes(key)

const matched = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return COMMON_PROPERTIES
  return COMMON_PROPERTIES.filter(
    (x) => x.key.toLowerCase().includes(kw) || x.name.toLowerCase().includes(kw)
  )
})

/** 只显示"有匹配项"的分组，搜索时不出现一堆空分组 */
const visibleGroups = computed(() =>
  PROPERTY_GROUPS.filter((g) => matched.value.some((x) => x.group === g.key))
)

const byGroup = (groupKey) => matched.value.filter((x) => x.group === groupKey)

const rangeText = (item) => {
  if (item.min === '' && item.max === '') return ''
  return `${item.min === '' ? '' : item.min} ~ ${item.max === '' ? '' : item.max}`
}

const skippedCount = computed(() => [...picked.value].filter((k) => exists(k)).length)

function toggle(item) {
  if (exists(item.key)) return
  const s = new Set(picked.value)
  if (s.has(item.key)) s.delete(item.key)
  else s.add(item.key)
  picked.value = s
}

function open() {
  keyword.value = ''
  picked.value = new Set()
  visible.value = true
}

function confirm() {
  // 只回吐"当前还存在的"和"未选中的" —— 已存在的不发回去，调用方也就不用再判一次
  const rows = COMMON_PROPERTIES.filter((x) => picked.value.has(x.key) && !exists(x.key)).map(toPropertyRow)
  emit('confirm', rows)
  visible.value = false
}

defineExpose({ open })
</script>

<style scoped>
.cp-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.cp-sum {
  flex: none;
  font-size: 11px;
  color: #6b7280;
  background: #f3f6fb;
  border-radius: 4px;
  padding: 2px 8px;
}

.cp-body {
  max-height: 460px;
  overflow-y: auto;
  padding-right: 4px;
}

.cp-group {
  margin-bottom: 10px;
}

.cp-group-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #374151;
  padding: 6px 2px;
  border-bottom: 1px dashed #eef1f6;
}

.cp-group-count {
  font-size: 11px;
  font-weight: 400;
  color: #9ca3af;
}

.cp-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(330px, 1fr));
  gap: 6px;
  margin-top: 6px;
}

.cp-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 7px 10px;
  border: 1px solid #e9eef6;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.cp-item:hover {
  background: #f7fafd;
  border-color: #cfdff5;
}

.cp-item.picked {
  background: #eef4fe;
  border-color: #85b7eb;
}

.cp-item.disabled {
  cursor: not-allowed;
  opacity: 0.55;
  background: #fafbfd;
}

.cp-main {
  min-width: 0;
  flex: 1;
}

.cp-line1 {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.cp-name {
  font-size: 13px;
  color: #1f2937;
  font-weight: 500;
}

.cp-key {
  font-family: Consolas, monospace;
  font-size: 11px;
  color: #6b7280;
}

.cp-badge {
  font-size: 10px;
  border-radius: 3px;
  padding: 1px 4px;
}

.cp-badge.rw {
  color: #d85a30;
  background: #faece7;
}

.cp-badge.exist {
  color: #6b7280;
  background: #f1efe8;
}

.cp-line2 {
  margin-top: 2px;
  font-size: 11px;
  color: #9ca3af;
  line-height: 1.6;
}

.cp-note {
  color: #b4b2a9;
}

.cp-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 12px;
  color: #9ca3af;
}

.cp-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.cp-picked {
  font-size: 12px;
  color: #6b7280;
}

.cp-picked b {
  color: #185fa5;
}
</style>
