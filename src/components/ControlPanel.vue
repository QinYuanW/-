<template>
  <div class="ctrl-panel">
    <el-alert
      v-if="online === false"
      type="warning"
      :closable="false"
      show-icon
      title="设备当前离线，命令无法下发"
      description="命令在设备离线时会直接失败（不会挂起）。若希望「设备上线后自动生效」，请改用「设备影子」tab 设置期望值。"
      style="margin-bottom: 12px"
    />

    <div v-if="!controls.length && !services.length" class="muted-empty">
      该产品无可写属性或服务
    </div>

    <template v-else>
      <!-- 可写属性：勾选要设置的项 → 填值 → 一次下发（对齐 OneNET「属性设置」） -->
      <template v-if="controls.length">
        <div class="cp-tools">
          <el-checkbox :model-value="allPicked" :indeterminate="somePicked" @change="toggleAll">
            全选
          </el-checkbox>
          <span class="cp-count">已选 {{ picked.length }} / {{ controls.length }}</span>
        </div>

        <div class="cp-list">
          <div
            v-for="p in controls"
            :key="p.key"
            class="cp-row"
            :class="{ picked: picked.includes(p.key) }"
          >
            <el-checkbox
              class="cp-check"
              :model-value="picked.includes(p.key)"
              @change="(v) => toggle(p.key, v)"
            />
            <div class="cp-label">
              {{ p.name }}
              <small>{{ p.key }} · {{ p.type }}</small>
            </div>
            <div class="cp-widget">
              <el-switch v-if="p.type === 'bool'" v-model="draft[p.key]" />

              <el-select
                v-else-if="p.type === 'enum'"
                v-model="draft[p.key]"
                size="small"
                style="width: 160px"
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
                :step="stepOf(p)"
                controls-position="right"
                style="width: 140px"
              />

              <el-input
                v-else
                v-model="draft[p.key]"
                size="small"
                style="width: 160px"
                placeholder="输入值"
              />
            </div>
            <span class="cp-cur">当前 {{ curText(p) }}</span>
          </div>
        </div>

        <div class="cp-actions">
          <el-button
            type="primary"
            :disabled="!picked.length"
            :loading="sending"
            @click="submitPicked"
          >
            下发选中（{{ picked.length }}）
          </el-button>
          <span class="cp-tip">勾选 → 填值 → 一次下发；右侧「当前 xx」为设备实际上报值</span>
        </div>
      </template>

      <!-- 服务调用（沿用即时触发：服务本身即一次动作，无「值」可预填） -->
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
    </template>

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
  telemetry: { type: Object, default: () => ({}) },
  // 设备在线状态：离线时命令必然失败（E1 语义分家），提前提示用户改用影子期望值
  online: { type: Boolean, default: true }
})

const emit = defineEmits(['executed', 'log', 'log-status'])

const controls = computed(() =>
  (props.tsl?.properties || []).filter((p) => p.access === 'readwrite')
)
const services = computed(() => props.tsl?.services || [])

const sendingKey = ref('')
const sending = ref(false)      // 批量下发中
const lastCommand = ref(null)
const optimistic = ref({})
const draft = reactive({})      // 待下发的值（用户编辑；不再即时下发）
const picked = ref([])          // 勾选待下发的属性 key
let statusTimer = null

const allPicked = computed(
  () => controls.value.length > 0 && picked.value.length === controls.value.length
)
const somePicked = computed(() => picked.value.length > 0 && !allPicked.value)

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

/** 调试日志用：状态 → Element Plus Tag 类型 */
const STATUS_TAG = { 0: 'warning', 1: 'primary', 2: 'success', 3: 'danger', 4: 'danger' }
const statusLabel = (s) => STATUS_MAP[s]?.text || '未知'

const statusText = computed(() => {
  if (!lastCommand.value) return ''
  return STATUS_MAP[lastCommand.value.status]?.text || '未知'
})
const statusClass = computed(() =>
  lastCommand.value ? STATUS_MAP[lastCommand.value.status]?.cls || '' : ''
)

/** 步长：物模型未配则按类型给默认（int 1 / float 0.1） */
const stepOf = (prop) => prop.step ?? (prop.type === 'int' ? 1 : 0.1)

function isTruthy(v) {
  return v === true || v === 'true' || v === 1 || v === '1'
}

/** 草稿初始值：以设备当前上报值为准，无值则退回类型默认 */
function initDraft(p) {
  const cur = props.telemetry[p.key]
  if (p.type === 'bool') return isTruthy(cur)
  if (p.type === 'int' || p.type === 'float') {
    const n = Number(cur)
    return Number.isNaN(n) ? p.min ?? 0 : n
  }
  if (p.type === 'enum') return cur ?? (p.elements || [])[0]?.value ?? ''
  return cur === undefined || cur === null ? '' : String(cur)
}

watch(controls, (list) => {
  list.forEach((p) => {
    if (draft[p.key] === undefined) draft[p.key] = initDraft(p)
  })
}, { immediate: true, deep: true })

/** 右侧「当前 xx」：设备实际值，便于对照用户填的值 */
function curText(p) {
  const v = props.telemetry[p.key]
  if (v === undefined || v === null) return '未上报'
  if (p.type === 'bool') return isTruthy(v) ? '开' : '关'
  if (p.type === 'enum') {
    const hit = (p.elements || []).find((e) => String(e.value) === String(v))
    return hit ? hit.key : String(v)
  }
  return `${v}${p.unit || ''}`
}

function toggle(key, v) {
  const s = new Set(picked.value)
  if (v) s.add(key)
  else s.delete(key)
  picked.value = [...s]
}

function toggleAll(v) {
  picked.value = v ? controls.value.map((p) => p.key) : []
}

function dispatchToast(name, status) {
  if (status === 1) ElMessage.success(`已下发「${name}」`)
  else if (status === 2) ElMessage.success(`「${name}」已执行`)
  else if (status === 0) ElMessage.warning(`「${name}」待下发`)
  else ElMessage.error(`「${name}」下发失败`)
}

/** 批量下发：勾选项的草稿值组装成一个 payload，一次发出 */
async function submitPicked() {
  if (!picked.value.length) return
  const keys = picked.value.slice()
  const payload = {}
  keys.forEach((k) => { payload[k] = draft[k] })

  sending.value = true
  keys.forEach((k) => { optimistic.value[k] = draft[k] })
  try {
    const res = await dispatchCommand(props.deviceKey, 1, payload)
    lastCommand.value = res.data
    dispatchToast(`属性 ×${keys.length}`, res.data.status)
    emit('log', {
      direction: 'down',
      requestId: res.data.request_id,
      action: '下发属性',
      actionType: 'primary',
      status: statusLabel(res.data.status),
      statusType: STATUS_TAG[res.data.status] || 'info',
      request: { command_type: 1, payload },
      response: res.data
    })
    startPolling(res.data.request_id, payload)
    picked.value = []
  } catch (e) {
    keys.forEach((k) => { delete optimistic.value[k] })
    emit('log', {
      direction: 'down',
      action: '下发属性',
      actionType: 'danger',
      status: '下发失败',
      statusType: 'danger',
      request: { command_type: 1, payload },
      response: { error: e?.message || '请求失败' }
    })
  } finally {
    sending.value = false
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
  // payload 后端原样透传至 down/{device_key}/command；带参服务形如
  // { "service_key": "set_mode", "params": { "mode": 2 } }
  const payload = { service_key: svc.key }
  if (params && Object.keys(params).length) payload.params = params
  try {
    const res = await dispatchCommand(props.deviceKey, 2, payload)
    lastCommand.value = res.data
    dispatchToast(`服务 ${svc.name}`, res.data.status)
    emit('log', {
      direction: 'down',
      requestId: res.data.request_id,
      action: '调用服务',
      actionType: 'primary',
      status: statusLabel(res.data.status),
      statusType: STATUS_TAG[res.data.status] || 'info',
      request: { command_type: 2, payload },
      response: res.data
    })
    startPolling(res.data.request_id, null)
  } catch (e) {
    emit('log', {
      direction: 'down',
      action: '调用服务',
      actionType: 'danger',
      status: '调用失败',
      statusType: 'danger',
      request: { command_type: 2, payload },
      response: { error: e?.message || '请求失败' }
    })
  } finally {
    sendingKey.value = ''
  }
}

/** 轮询指令状态；values 为本次下发的属性值，用于成功后本地回填 */
function startPolling(requestId, values) {
  if (statusTimer) clearInterval(statusTimer)
  statusTimer = setInterval(async () => {
    try {
      const res = await getCommandStatus(requestId)
      lastCommand.value = res.data
      emit('log-status', {
        requestId,
        status: statusLabel(res.data.status),
        statusType: STATUS_TAG[res.data.status] || 'info',
        response: res.data
      })
      if (res.data.status === 2) {
        if (values) emit('executed', values)
        clearInterval(statusTimer)
        statusTimer = null
      } else if ([3, 4].includes(res.data.status)) {
        if (values) Object.keys(values).forEach((k) => { delete optimistic.value[k] })
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

.cp-tools {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}

.cp-count {
  font-size: 12px;
  color: #9ca3af;
}

.cp-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.cp-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 6px;
  border-radius: 6px;
  border-bottom: 1px dashed #f0f2f5;
}

.cp-row.picked {
  background: #f6faff;
}

.cp-check {
  flex-shrink: 0;
}

.cp-label {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: #374151;
}

.cp-label small {
  display: block;
  font-size: 11px;
  color: #b0b8c4;
  font-family: Consolas, monospace;
}

.cp-widget {
  flex-shrink: 0;
}

.cp-cur {
  font-size: 12px;
  color: #9ca3af;
  font-family: Consolas, monospace;
  white-space: nowrap;
  min-width: 74px;
  text-align: right;
}

.cp-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 12px;
}

.cp-tip {
  font-size: 11px;
  color: #b0b8c4;
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
  color: #374151;
}

.ctrl-label small {
  display: block;
  font-size: 11px;
  color: #b0b8c4;
  font-family: Consolas, monospace;
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

.ctrl-divider {
  font-size: 12px;
  color: #9ca3af;
  margin: 18px 0 4px;
  padding-top: 12px;
  border-top: 1px solid #f0f2f5;
}

.cmd-status {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #f0f2f5;
}

.cmd-status-title {
  font-size: 12px;
  color: #9ca3af;
  margin-bottom: 8px;
}

.cmd-flow {
  display: flex;
  align-items: center;
  gap: 10px;
}

.badge {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 999px;
  background: #f1f5f9;
  color: #475569;
}

.badge.sent {
  background: #eff6ff;
  color: #1d4ed8;
}

.badge.done {
  background: #ecfdf5;
  color: #047857;
}

.badge.failed,
.badge.timeout {
  background: #fef2f2;
  color: #b91c1c;
}

.badge.pending {
  background: #fffbeb;
  color: #b45309;
}

.cmd-req {
  font-size: 12px;
  color: #b0b8c4;
  font-family: Consolas, monospace;
}
</style>
