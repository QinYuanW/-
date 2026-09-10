<template>
  <div class="ctrl-panel">
    <div v-if="!controls.length && !services.length" class="muted-empty">
      该产品无可写属性或服务
    </div>

    <!-- 可写属性控件 -->
    <div v-for="prop in controls" :key="prop.key" class="ctrl-row">
      <div class="ctrl-label">
        {{ prop.name }}
        <small>{{ prop.key }} · {{ prop.type }}</small>
      </div>
      <div class="ctrl-widget">
        <el-switch
          v-if="prop.type === 'bool'"
          :model-value="boolValue(prop.key)"
          :loading="sendingKey === prop.key"
          @change="(v) => sendProperty(prop, v)"
        />
        <div v-else-if="prop.type === 'int' || prop.type === 'float'" class="slider-wrap">
          <el-slider
            :model-value="numValue(prop.key)"
            :min="prop.min ?? 0"
            :max="prop.max ?? 100"
            :step="prop.type === 'int' ? 1 : 0.1"
            :disabled="sendingKey === prop.key"
            style="flex: 1"
            @change="(v) => sendProperty(prop, v)"
          />
          <span class="slider-val">{{ numValue(prop.key) }}{{ prop.unit || '' }}</span>
        </div>
        <el-input
          v-else
          :model-value="String(telemetry[prop.key] ?? '')"
          size="small"
          style="width: 160px"
          placeholder="输入值"
          @keyup.enter="(e) => sendProperty(prop, e.target.value)"
          @blur="(e) => sendProperty(prop, e.target.value)"
        />
      </div>
    </div>

    <!-- 服务按钮 -->
    <template v-if="services.length">
      <div class="ctrl-divider">服务调用</div>
      <div v-for="svc in services" :key="svc.key" class="ctrl-row">
        <div class="ctrl-label">
          {{ svc.name }}
          <small>{{ svc.key }}</small>
        </div>
        <el-button
          size="small"
          type="success"
          plain
          :loading="sendingKey === svc.key"
          @click="callService(svc)"
        >
          调用
        </el-button>
      </div>
    </template>

    <!-- 指令状态 -->
    <div v-if="lastCommand" class="cmd-status">
      <div class="cmd-status-title">最近指令</div>
      <div class="cmd-flow">
        <span class="badge" :class="statusClass">{{ statusText }}</span>
        <span class="cmd-req">{{ lastCommand.request_id.slice(0, 8) }}...</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { dispatchCommand, getCommandStatus } from '../api'

const props = defineProps({
  deviceKey: { type: String, required: true },
  tsl: { type: Object, default: null },
  telemetry: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['executed'])

const controls = computed(() =>
  (props.tsl?.properties || []).filter((p) => p.access === 'readwrite')
)
const services = computed(() => props.tsl?.services || [])

const sendingKey = ref('')
const lastCommand = ref(null)
const optimistic = ref({})
let statusTimer = null

const STATUS_MAP = {
  0: { text: '待下发', cls: 'pending' },
  1: { text: '已下发', cls: 'sent' },
  2: { text: '已执行', cls: 'done' },
  3: { text: '失败', cls: 'failed' },
  4: { text: '超时', cls: 'timeout' }
}

const statusText = computed(() => (lastCommand.value ? STATUS_MAP[lastCommand.value.status]?.text || '未知' : ''))
const statusClass = computed(() => (lastCommand.value ? STATUS_MAP[lastCommand.value.status]?.cls || '' : ''))

function boolValue(key) {
  const v = optimistic.value[key] ?? props.telemetry[key]
  return v === true || v === 'true' || v === 1 || v === '1'
}

function numValue(key) {
  const v = optimistic.value[key] ?? props.telemetry[key]
  const n = Number(v)
  return Number.isNaN(n) ? 0 : n
}

// 遥测刷新到该键后释放乐观覆盖，恢复真实数据驱动
watch(() => props.telemetry, (t) => {
  for (const key of Object.keys(optimistic.value)) {
    if (key in t) delete optimistic.value[key]
  }
}, { deep: true })

async function sendProperty(prop, value) {
  sendingKey.value = prop.key
  optimistic.value[prop.key] = value
  try {
    const res = await dispatchCommand(props.deviceKey, 1, { [prop.key]: value })
    lastCommand.value = res.data
    ElMessage.success(`已下发「${prop.name}」`)
    startPolling(res.data.request_id, prop.key, value)
  } catch (e) {
    delete optimistic.value[prop.key]
  } finally {
    sendingKey.value = ''
  }
}

async function callService(svc) {
  sendingKey.value = svc.key
  try {
    const res = await dispatchCommand(props.deviceKey, 2, { service_key: svc.key })
    lastCommand.value = res.data
    ElMessage.success(`已调用服务「${svc.name}」`)
    startPolling(res.data.request_id)
  } catch (e) {
    // 拦截器已提示
  } finally {
    sendingKey.value = ''
  }
}

function startPolling(requestId, propKey, propValue) {
  if (statusTimer) clearInterval(statusTimer)
  statusTimer = setInterval(async () => {
    try {
      const res = await getCommandStatus(requestId)
      lastCommand.value = res.data
      if (res.data.status === 2) {
        // 设备已执行 → 乐观更新本地状态，不等下一次遥测轮询
        if (propKey !== undefined) emit('executed', { key: propKey, value: propValue })
        clearInterval(statusTimer)
        statusTimer = null
      } else if ([3, 4].includes(res.data.status)) {
        // 失败/超时 → 回滚乐观覆盖
        if (propKey !== undefined) delete optimistic.value[propKey]
        clearInterval(statusTimer)
        statusTimer = null
      }
    } catch (e) {
      clearInterval(statusTimer)
      statusTimer = null
    }
  }, 2000)
}

onBeforeUnmount(() => {
  if (statusTimer) clearInterval(statusTimer)
})
</script>

<style scoped>
.ctrl-panel {
  min-height: 120px;
}

.muted-empty {
  font-size: 13px;
  color: #9ca3af;
  text-align: center;
  padding: 30px 0;
}

.ctrl-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 0;
  border-bottom: 1px dashed #eef1f6;
}

.ctrl-row:last-child {
  border-bottom: none;
}

.ctrl-label {
  font-size: 13px;
  color: #1f2937;
}

.ctrl-label small {
  display: block;
  color: #9ca3af;
  font-size: 11px;
  margin-top: 2px;
  font-family: Consolas, monospace;
}

.ctrl-widget {
  display: flex;
  align-items: center;
}

.slider-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 180px;
}

.slider-val {
  font-size: 12px;
  color: #6b7280;
  min-width: 42px;
  text-align: right;
  font-family: Consolas, monospace;
}

.ctrl-divider {
  font-size: 12px;
  color: #9ca3af;
  margin: 10px 0 4px;
  padding-top: 10px;
  border-top: 1px solid #f1f4f9;
}

.cmd-status {
  margin-top: 14px;
  border: 1px dashed #e5eaf2;
  border-radius: 8px;
  padding: 10px 12px;
  background: #fbfcfe;
}

.cmd-status-title {
  font-size: 12px;
  color: #6b7280;
  margin-bottom: 6px;
}

.cmd-flow {
  display: flex;
  align-items: center;
  gap: 8px;
}

.badge {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 4px;
  font-size: 12px;
}

.badge.pending {
  background: #fef3c7;
  color: #92400e;
}

.badge.sent {
  background: #dbeafe;
  color: #1e40af;
}

.badge.done {
  background: #dcfce7;
  color: #166534;
}

.badge.failed {
  background: #fee2e2;
  color: #991b1b;
}

.badge.timeout {
  background: #f3e8ff;
  color: #6b21a8;
}

.cmd-req {
  font-size: 11px;
  color: #9ca3af;
  font-family: Consolas, monospace;
}
</style>
