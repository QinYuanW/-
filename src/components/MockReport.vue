<template>
  <div class="mock-report">
    <div class="mr-title">
      模拟上报
      <span class="mr-hint">伪造设备上报，走真实链路：EMQX 规则 → Kafka → 入库 → 影子对齐</span>
    </div>

    <div v-if="!properties.length" class="mr-empty">该产品未配置属性</div>

    <template v-else>
      <div class="mr-tools">
        <el-checkbox :model-value="allPicked" :indeterminate="somePicked" @change="toggleAll">
          全选
        </el-checkbox>
        <span class="mr-count">已选 {{ picked.length }} / {{ properties.length }}</span>
      </div>

      <div class="mr-list">
        <div
          v-for="p in properties"
          :key="p.key"
          class="mr-row"
          :class="{ picked: picked.includes(p.key) }"
        >
          <el-checkbox
            class="mr-check"
            :model-value="picked.includes(p.key)"
            @change="(v) => toggle(p.key, v)"
          />
          <div class="mr-label">
            {{ p.name }}
            <small>{{ p.key }} · {{ p.type }}</small>
          </div>
          <div class="mr-widget">
            <el-radio-group v-if="p.type === 'bool'" v-model="draft[p.key]" size="small">
              <el-radio-button :value="true">true-开</el-radio-button>
              <el-radio-button :value="false">false-关</el-radio-button>
            </el-radio-group>

            <el-select
              v-else-if="p.type === 'enum'"
              v-model="draft[p.key]"
              size="small"
              style="width: 180px"
              placeholder="选择枚举值"
            >
              <el-option
                v-for="o in p.elements || []"
                :key="String(o.value)"
                :value="o.value"
                :label="o.key"
              />
            </el-select>

            <el-input-number
              v-else-if="p.type === 'int' || p.type === 'float'"
              v-model="draft[p.key]"
              size="small"
              :min="p.min ?? undefined"
              :max="p.max ?? undefined"
              :step="p.step ?? (p.type === 'int' ? 1 : 0.1)"
              controls-position="right"
              :placeholder="rangeHint(p)"
              style="width: 200px"
            />

            <el-input
              v-else
              v-model="draft[p.key]"
              size="small"
              style="width: 200px"
              :placeholder="rangeHint(p)"
            />
          </div>
        </div>
      </div>

      <div class="mr-actions">
        <el-button
          type="primary"
          :loading="sending"
          :disabled="!picked.length"
          @click="submit"
        >
          上报选中（{{ picked.length }}）
        </el-button>
        <span class="mr-tip">上报成功后设备会变为「在线」，可借此激活上方的「下发控制」</span>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { mockReportTelemetry } from '../api'

const props = defineProps({
  deviceKey: { type: String, required: true },
  tsl: { type: Object, default: null }
})

const emit = defineEmits(['log'])

const sending = ref(false)
const picked = ref([])
const draft = reactive({})

const properties = computed(() => props.tsl?.properties || [])
const allPicked = computed(
  () => properties.value.length > 0 && picked.value.length === properties.value.length
)
const somePicked = computed(() => picked.value.length > 0 && !allPicked.value)

// 按类型初始化草稿值（enum 取首项，数值取 min 或 0）
watch(properties, (list) => {
  list.forEach((p) => {
    if (draft[p.key] !== undefined) return
    if (p.type === 'bool') draft[p.key] = false
    else if (p.type === 'int' || p.type === 'float') draft[p.key] = p.min ?? 0
    else if (p.type === 'enum') draft[p.key] = (p.elements || [])[0]?.value ?? ''
    else draft[p.key] = ''
  })
}, { immediate: true })

function toggle(key, v) {
  const s = new Set(picked.value)
  if (v) s.add(key)
  else s.delete(key)
  picked.value = [...s]
}

function toggleAll(v) {
  picked.value = v ? properties.value.map((p) => p.key) : []
}

/** 值域提示（学 OneNET：把范围/步长/单位写在输入提示里） */
function rangeHint(p) {
  const parts = []
  if (p.min !== undefined && p.max !== undefined) parts.push(`${p.min} — ${p.max}`)
  if (p.step !== undefined) parts.push(`步长 ${p.step}`)
  if (p.unit) parts.push(`单位 ${p.unit}`)
  return parts.join('；') || `输入 ${p.type} 值`
}

async function submit() {
  if (!picked.value.length) return
  const payload = { device_key: props.deviceKey, ts: Date.now(), properties: {} }
  picked.value.forEach((k) => {
    payload.properties[k] = draft[k]
  })

  sending.value = true
  try {
    const res = await mockReportTelemetry(payload)
    emit('log', {
      action: '模拟上报',
      actionType: 'success',
      status: `已上报 ${picked.value.length} 项`,
      statusType: 'success',
      request: payload,
      response: res
    })
    ElMessage.success(`已模拟上报 ${picked.value.length} 个属性`)
  } catch (e) {
    emit('log', {
      action: '模拟上报',
      actionType: 'danger',
      status: '上报失败',
      statusType: 'danger',
      request: payload,
      response: { error: e?.message || '请求失败' }
    })
  } finally {
    sending.value = false
  }
}
</script>

<style scoped>
.mock-report {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.mr-title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}

.mr-hint {
  font-size: 11px;
  font-weight: 400;
  color: #b0b8c4;
}

.mr-empty {
  font-size: 13px;
  color: #9ca3af;
  padding: 16px 0;
}

.mr-tools {
  display: flex;
  align-items: center;
  gap: 12px;
}

.mr-count {
  font-size: 12px;
  color: #9ca3af;
}

.mr-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 300px;
  overflow-y: auto;
}

.mr-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 6px;
  border-radius: 6px;
  border-bottom: 1px dashed #f0f2f5;
}

.mr-row.picked {
  background: #f6faff;
}

.mr-check {
  flex-shrink: 0;
}

.mr-label {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: #374151;
}

.mr-label small {
  display: block;
  font-size: 11px;
  color: #b0b8c4;
  font-family: Consolas, monospace;
}

.mr-widget {
  flex-shrink: 0;
}

.mr-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding-top: 4px;
}

.mr-tip {
  font-size: 11px;
  color: #b0b8c4;
}
</style>
