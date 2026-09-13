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
            :step="stepOf(prop)"
            :disabled="sendingKey === prop.key"
            style="flex: 1"
            @change="(v) => sendProperty(prop, v)"
          />
          <el-input-number
            :model-value="numValue(prop.key)"
            :min="prop.min ?? 0"
            :max="prop.max ?? 100"
            :step="stepOf(prop)"
            :disabled="sendingKey === prop.key"
            size="small"
            controls-position="right"
            class="num-input"
            @change="(v) => sendNumeric(prop, v)"
          />
          <span class="slider-val">{{ prop.unit || '' }}</span>
        </div>
        <el-select
          v-else-if="prop.type === 'enum'"
          :model-value="enumValue(prop.key)"
          :loading="sendingKey === prop.key"
          size="small"
          style="width: 160px"
          placeholder="选择枚举值"
          @change="(v) => sendProperty(prop, v)"
        >
          <el-option
            v-for="opt in prop.elements || []"
            :key="String(opt.value)"
            :value="opt.value"
            :label="opt.key"
          />
        </el-select>
        <div v-else class="str-wrap">
          <el-input
            v-model="strDraft[prop.key]"
            size="small"
            style="width: 130px"
            placeholder="输入值"
            :disabled="sendingKey === prop.key"
            @keyup.enter="sendString(prop)"
          />
          <el-button
            size="small"
            :loading="sendingKey === prop.key"
            @click="sendString(prop)"
          >
            下发
          </el-button>
        </div>
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
          @click="openService(svc)"
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

    <!-- 服务参数表单：物模型配了 inputData 的服务，调用前先填参数 -->
    <el-dialog v-model="svcVisible" :title="`调用服务 · ${svcTarget?.name || ''}`" width="480px">
      <p v-if="svcParams.length" class="svc-hint">
        该服务需 {{ svcParams.length }} 个输入参数（物模型 inputData）
      </p>
      <el-form v-if="svcParams.length" label-width="120px">
        <el-form-item v-for="p in svcParams" :key="p.key" :label="p.name || p.key">
          <el-switch v-if="p.type === 'bool'" v-model="svcValues[p.key]" />
          <el-input-number
            v-else-if="p.type === 'int' || p.type === 'float'"
            v-model="svcValues[p.key]"
            size="small"
            :step="p.type === 'int' ? 1 : 0.1"
            controls-position="right"
            style="width: 180px"
          />
          <el-input
            v-else
            v-model="svcValues[p.key]"
            size="small"
            style="width: 220px"
            :placeholder="p.key"
          />
          <span class="svc-type">{{ p.type || 'string' }}</span>
        </el-form-item>
      </el-form>
      <el-empty v-else description="该服务无需参数" :image-size="60" />
      <template #footer>
        <el-button @click="svcVisible = false">取消</el-button>
        <el-button type="primary" :loading="!!sendingKey" @click="confirmService">调用</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onBeforeUnmount } from 'vue'
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
const strDraft = reactive({})   // string 属性输入草稿（el-input 必须受控，不能直接用 telemetry 只读绑定）
let statusTimer = null

// 服务调用参数表单（物模型 services[].inputData）
const svcVisible = ref(false)
const svcTarget = ref(null)
const svcParams = ref([])
const svcValues = reactive({})

const STATUS_MAP = {
  0: { text: '待下发', cls: 'pending' },
  1: { text: '已下发', cls: 'sent' },
  2: { text: '已执行', cls: 'done' },
  3: { text: '失败', cls: 'failed' },
  4: { text: '超时', cls: 'timeout' }
}

const statusText = computed(() => {
  if (!lastCommand.value) return ''
  const s = lastCommand.value.status
  // E1：0 不再是"一闪而过"，而是设备离线时的常态挂起——把原因讲出来
  if (s === 0) return '待下发 · 设备离线挂起'
  return STATUS_MAP[s]?.text || '未知'
})
const statusClass = computed(() => (lastCommand.value ? STATUS_MAP[lastCommand.value.status]?.cls || '' : ''))

/** E1 后 dispatch 可能返回 0（设备离线挂起）——提示语必须按真实状态给，不能一律喊"已下发" */
function dispatchToast(name, status) {
  if (status === 0) {
    ElMessage.warning(`设备离线，「${name}」已挂起，上线后自动补发`)
  } else if (status === 1) {
    ElMessage.success(`已下发「${name}」`)
  } else {
    ElMessage.error(`「${name}」下发失败`)
  }
}

function boolValue(key) {
  const v = optimistic.value[key] ?? props.telemetry[key]
  return v === true || v === 'true' || v === 1 || v === '1'
}

function numValue(key) {
  const v = optimistic.value[key] ?? props.telemetry[key]
  const n = Number(v)
  return Number.isNaN(n) ? 0 : n
}

/** 数值步长：物模型未配则按类型给默认（int 1 / float 0.1） */
const stepOf = (prop) => prop.step ?? (prop.type === 'int' ? 1 : 0.1)

/** 输入框回传：拦住空值与 NaN，避免误下发 0 */
function sendNumeric(prop, v) {
  if (v === null || v === undefined || v === '') return
  const n = Number(v)
  if (Number.isNaN(n)) return
  sendProperty(prop, n)
}

/** string 属性：仅「回车 / 点下发」触发，不再用 blur（切断切 tab、点别处导致的误发指令） */
function sendString(prop) {
  const v = strDraft[prop.key]
  if (v === undefined || v === null || String(v).trim() === '') {
    ElMessage.warning('请先输入要下发的值')
    return
  }
  sendProperty(prop, String(v))
}

// 枚举当前值：与 elements 按字符串弱比较，规避遥测 1 与枚举 value 1（number）类型不一致导致的空显示
function enumValue(key) {
  const v = optimistic.value[key] ?? props.telemetry[key]
  if (v === undefined || v === null || v === '') return ''
  const prop = controls.value.find((p) => p.key === key)
  const hit = (prop?.elements || []).find((e) => String(e.value) === String(v))
  return hit ? hit.value : v
}

// 遥测刷新到该键后释放乐观覆盖，恢复真实数据驱动；同时回填 string 输入草稿
watch(() => props.telemetry, (t) => {
  for (const key of Object.keys(optimistic.value)) {
    if (key in t) delete optimistic.value[key]
  }
  for (const key of Object.keys(t)) {
    if (strDraft[key] === undefined) strDraft[key] = String(t[key] ?? '')
  }
}, { deep: true, immediate: true })

async function sendProperty(prop, value) {
  sendingKey.value = prop.key
  optimistic.value[prop.key] = value
  try {
    const res = await dispatchCommand(props.deviceKey, 1, { [prop.key]: value })
    lastCommand.value = res.data
    dispatchToast(prop.name, res.data.status)
    startPolling(res.data.request_id, prop.key, value)
  } catch (e) {
    delete optimistic.value[prop.key]
  } finally {
    sendingKey.value = ''
  }
}

/** 服务入口：物模型配了 inputData 就先弹参数表单，没有参数则直接调用 */
function openService(svc) {
  const inputs = Array.isArray(svc.inputData) ? svc.inputData : []
  if (!inputs.length) {
    callService(svc, null)
    return
  }
  svcTarget.value = svc
  svcParams.value = inputs
  Object.keys(svcValues).forEach((k) => delete svcValues[k])
  inputs.forEach((p) => {
    svcValues[p.key] = p.type === 'bool' ? false : p.type === 'int' || p.type === 'float' ? 0 : ''
  })
  svcVisible.value = true
}

function confirmService() {
  if (!svcTarget.value) return
  const params = {}
  svcParams.value.forEach((p) => { params[p.key] = svcValues[p.key] })
  svcVisible.value = false
  callService(svcTarget.value, params)
}

async function callService(svc, params) {
  sendingKey.value = svc.key
  try {
    // payload 后端原样透传至 down/{device_key}/command；带参服务形如
    // { "service_key": "set_mode", "params": { "mode": 2 } }
    const payload = { service_key: svc.key }
    if (params && Object.keys(params).length) payload.params = params
    const res = await dispatchCommand(props.deviceKey, 2, payload)
    lastCommand.value = res.data
    dispatchToast(`服务 ${svc.name}`, res.data.status)
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

/* 数值属性：滑块旁挂精确输入框（滑块适合粗调，输入框适合精确值） */
.num-input {
  width: 118px;
  flex-shrink: 0;
}

/* string 属性：输入框 + 显式下发按钮 */
.str-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.svc-hint {
  font-size: 12px;
  color: #9ca3af;
  margin-bottom: 12px;
}

.svc-type {
  margin-left: 10px;
  font-size: 11px;
  color: #b0b8c4;
  font-family: Consolas, monospace;
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
