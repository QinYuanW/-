<template>
  <el-dialog v-model="visible" :title="meta.title" width="820px" top="6vh">
    <div class="cp-head">
      <el-input v-model="keyword" :placeholder="`搜索 key 或名称，如 ${hintKey}`" clearable :prefix-icon="Search" />
      <span class="cp-sum">{{ catalogSummary(kind) }}</span>
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
            <el-checkbox :model-value="picked.has(item.key)" :disabled="exists(item.key)" @click.stop @change="toggle(item)" />
            <div class="cp-main">
              <div class="cp-line1">
                <span class="cp-name">{{ item.name }}</span>
                <span class="cp-key">{{ item.key }}</span>
                <span v-if="exists(item.key)" class="cp-badge exist">已存在</span>
                <span v-else-if="kind === 'properties' && item.access === 'readwrite'" class="cp-badge rw">可写</span>
                <span v-else-if="kind === 'events'" class="cp-badge" :class="eventClass(item.type)">{{ eventLabel(item.type) }}</span>
                <span v-else-if="kind === 'services'" class="cp-badge">{{ item.callType === 'sync' ? '同步' : '异步' }}</span>
              </div>
              <div class="cp-line2">
                <template v-if="kind === 'properties'">
                  <span>{{ item.type }}</span>
                  <span v-if="item.unit">· {{ item.unit }}</span>
                  <span v-if="rangeText(item)">· {{ rangeText(item) }}</span>
                </template>
                <template v-else-if="kind === 'services'">
                  <span v-if="(item.inputData || []).length">入参 {{ (item.inputData || []).map((x) => x.key).join(' / ') }}</span>
                  <span v-else>无入参</span>
                  <span v-if="(item.outputData || []).length">· 出参 {{ (item.outputData || []).map((x) => x.key).join(' / ') }}</span>
                </template>
                <span v-if="item.note" class="cp-note">{{ item.note }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="!visibleGroups.length" class="cp-empty">没有匹配的项（换个关键词试试）</div>
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
import { CATALOGS, catalogSummary, itemsOf, toRow } from '../constants/tslCommon'

/**
 * 常用项库弹窗（属性 / 事件 / 服务三类共用）。
 *
 * <h3>为什么三类用一个组件</h3>
 * 三类的**交互完全一样**（分组 + 搜索 + 多选 + 已存在禁用），只有"每行展示什么信息"不同。
 * 写三个组件会让搜索、禁用、多选这些逻辑各存一份 —— 和 `TslEditor` 抽出来是同一个理由。
 * 差异收敛在 `kind` 上：标题、分组、条目、行内展示、输出形状。
 *
 * <h3>已存在的 key 直接禁用</h3>
 * 后端 `TslValidator` 要求 key 在**三类之间全局唯一**。若放任重复添加，
 * 用户会一路填到点保存才被拦下 —— 那时已白填一堆。
 */
const props = defineProps({
  /** 当前编辑区里已有的对应 key（用于禁用重复项） */
  existingKeys: { type: Array, default: () => [] }
})
const emit = defineEmits(['confirm'])

const visible = ref(false)
const kind = ref('properties')
const keyword = ref('')
const picked = ref(new Set())

const meta = computed(() => CATALOGS[kind.value] || CATALOGS.properties)
const hintKey = computed(() => (kind.value === 'properties' ? 'temperature' : kind.value === 'events' ? 'alarm' : 'reboot'))

const exists = (key) => props.existingKeys.includes(key)

const matched = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const items = itemsOf(kind.value)
  if (!kw) return items
  return items.filter((x) => x.key.toLowerCase().includes(kw) || x.name.toLowerCase().includes(kw))
})

/** 只显示"有匹配项"的分组，搜索时不出现一堆空分组 */
const visibleGroups = computed(() =>
  (meta.value.groups || []).filter((g) => matched.value.some((x) => x.group === g.key))
)

const byGroup = (groupKey) => matched.value.filter((x) => x.group === groupKey)

const rangeText = (item) => {
  if (item.min === '' && item.max === '') return ''
  return `${item.min === '' ? '' : item.min} ~ ${item.max === '' ? '' : item.max}`
}

const eventLabel = (t) => ({ info: '信息', alert: '告警', fault: '故障' }[t] || t)
const eventClass = (t) => (t === 'alert' ? 'alert' : t === 'fault' ? 'fault' : '')

const skippedCount = computed(() => [...picked.value].filter((k) => exists(k)).length)

function toggle(item) {
  if (exists(item.key)) return
  const s = new Set(picked.value)
  if (s.has(item.key)) s.delete(item.key)
  else s.add(item.key)
  picked.value = s
}

/** @param which 'properties' | 'events' | 'services' */
function open(which = 'properties') {
  kind.value = CATALOGS[which] ? which : 'properties'
  keyword.value = ''
  picked.value = new Set()
  visible.value = true
}

function confirm() {
  const rows = itemsOf(kind.value)
    .filter((x) => picked.value.has(x.key) && !exists(x.key))
    .map((x) => toRow(kind.value, x))
  emit('confirm', { kind: kind.value, rows })
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
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
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
  color: #6b7280;
  background: #f1efe8;
}

.cp-badge.rw {
  color: #d85a30;
  background: #faece7;
}

.cp-badge.alert {
  color: #a32d2d;
  background: #fcebeb;
}

.cp-badge.fault {
  color: #854f0b;
  background: #faeeda;
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
