<template>
  <div class="device-detail">
    <div class="detail-head">
      <el-button text :icon="Back" @click="$router.push('/')">返回</el-button>
      <span class="device-name">{{ device?.device_name || key }}</span>
      <el-tag size="small" type="info" effect="plain">{{ key }}</el-tag>
      <el-button text size="small" :icon="CopyDocument" title="复制 device_key" @click="copyKey" />
      <span class="online-dot" :class="online ? 'on' : ''"></span>
      <span class="online-text">{{ online ? '在线' : '离线' }}</span>
    </div>

    <el-tabs v-model="activeTab" class="detail-tabs">
      <el-tab-pane lazy label="设备信息" name="info">
        <div class="panel">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="device_key">{{ key }}</el-descriptions-item>
            <el-descriptions-item label="设备名称">{{ device?.device_name || '-' }}</el-descriptions-item>
            <el-descriptions-item label="所属产品">{{ productName }}</el-descriptions-item>
            <el-descriptions-item label="在线状态">
              <span class="online-dot" :class="online ? 'on' : ''"></span>
              <span class="online-text">{{ online ? '在线' : '离线' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="最后在线时间">{{ device?.last_online_at || '-' }}</el-descriptions-item>
            <el-descriptions-item label="物模型">
              {{ tsl ? `${(tsl.properties || []).length} 属性 · ${(tsl.services || []).length} 服务` : '-' }}
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </el-tab-pane>

      <el-tab-pane lazy label="实时数据" name="telemetry">
        <div class="panel">
          <div class="panel-title">
            最新数据
            <span class="muted">按物模型 TSL 自动渲染</span>
          </div>
          <MetricCards :tsl="tsl" :telemetry="latest" :loading="telemetryLoading" />
        </div>
        <div class="panel">
          <div class="panel-title">历史曲线</div>
          <HistoryChart :device-key="key" :tsl="tsl" :telemetry="latest" />
        </div>
      </el-tab-pane>

      <el-tab-pane lazy label="调试" name="debug">
        <!-- 两个方向分成独立视图（对齐 OneNET「应用模拟器 / 设备模拟器」的分层），日志各看各的 -->
        <el-tabs v-model="debugMode" class="debug-subtabs">
          <el-tab-pane lazy label="下发控制 · 平台 → 设备" name="down">
            <div class="debug-grid">
              <div class="debug-left">
                <div class="panel">
                  <div class="panel-title">属性设置 / 服务调用</div>
                  <ControlPanel
                    :device-key="key"
                    :tsl="tsl"
                    :telemetry="latest"
                    :online="device ? online : true"
                    @executed="handleExecuted"
                    @log="pushLog"
                    @log-status="updateLogStatus"
                  />
                </div>
              </div>
              <div class="debug-right">
                <div class="panel debug-log-panel">
                  <DebugLog :logs="downLogs" @clear="clearLogs('down')" />
                </div>
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane lazy label="模拟上报 · 设备 → 平台" name="up">
            <div class="debug-grid">
              <div class="debug-left">
                <div class="panel">
                  <MockReport :device-key="key" :tsl="tsl" @log="pushLog" />
                </div>
              </div>
              <div class="debug-right">
                <div class="panel debug-log-panel">
                  <DebugLog :logs="upLogs" @clear="clearLogs('up')" />
                </div>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-tab-pane>

      <el-tab-pane lazy label="设备影子" name="shadow">
        <div class="panel">
          <div class="panel-title">
            设备影子 · 期望值 / 上报值 / 差异
            <el-tag size="small" type="success" effect="plain" style="margin-left: 8px">E1</el-tag>
          </div>
          <ShadowPanel :device-key="key" :tsl="tsl" />
        </div>
      </el-tab-pane>

      <el-tab-pane lazy label="事件记录" name="events">
        <div class="panel">
          <div class="panel-title">设备事件 · 由设备经 <code>up/{{ key }}/event</code> 上报</div>
          <el-table v-loading="eventsLoading" :data="events" size="small">
            <el-table-column label="事件时间" width="170">
              <template #default="{ row }">{{ row.ts }}</template>
            </el-table-column>
            <el-table-column label="事件标识" width="150">
              <template #default="{ row }">
                <code>{{ row.event_key }}</code>
              </template>
            </el-table-column>
            <el-table-column label="事件名称" min-width="130">
              <template #default="{ row }">{{ row.event_name }}</template>
            </el-table-column>
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-tag :type="eventTypeMeta(row.type).type" size="small" effect="plain">
                  {{ eventTypeMeta(row.type).text }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="参数" min-width="200">
              <template #default="{ row }">
                <code class="event-params">{{ row.paramsText }}</code>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="暂无事件记录" :image-size="70" />
            </template>
          </el-table>
          <el-pagination
            v-if="eventsTotal > eventsPageSize"
            class="log-pager"
            layout="total, prev, pager, next"
            :total="eventsTotal"
            :page-size="eventsPageSize"
            :current-page="eventsPage"
            @current-change="handleEventsPage"
          />
        </div>
      </el-tab-pane>

      <el-tab-pane lazy label="操作日志" name="logs">
        <div class="panel">
          <div class="panel-title">设备日志 · 上下线 / 指令 / 操作</div>
          <el-table v-loading="logsLoading" :data="logs" size="small">
            <el-table-column label="时间" width="170">
              <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
            </el-table-column>
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-tag :type="logTypeMeta(row.log_type).type" size="small" effect="plain">
                  {{ logTypeMeta(row.log_type).text }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="content" label="内容" min-width="200" show-overflow-tooltip />
            <template #empty>
              <el-empty description="暂无日志记录" :image-size="70" />
            </template>
          </el-table>
          <div class="log-pager">
            <el-pagination
              small
              background
              layout="total, prev, pager, next"
              :total="logsTotal"
              :page-size="logsPageSize"
              :current-page="logsPage"
              @current-change="handleLogsPage"
            />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Back, CopyDocument } from '@element-plus/icons-vue'
import { listDevices, listProducts, getTsl, getLatestTelemetry, listDeviceLogs, listDeviceEvents } from '../api'
import { createTelemetrySocket } from '../api/ws'
import { formatTime } from '../utils/export'
import { copyText } from '../utils/clipboard'
import MetricCards from '../components/MetricCards.vue'
import HistoryChart from '../components/HistoryChart.vue'
import ControlPanel from '../components/ControlPanel.vue'
import ShadowPanel from '../components/ShadowPanel.vue'
import MockReport from '../components/MockReport.vue'
import DebugLog from '../components/DebugLog.vue'

const route = useRoute()
const router = useRouter()

const key = computed(() => route.params.key)
const device = ref(null)
const products = ref([])
const tsl = ref(null)
const latest = ref({})
const telemetryLoading = ref(false)
const activeTab = ref(route.query.tab || 'info')

const DETAIL_TABS = ['info', 'telemetry', 'debug', 'shadow', 'events', 'logs']
watch(
  () => route.query.tab,
  (t) => {
    if (DETAIL_TABS.includes(t)) activeTab.value = t
  }
)

// tab 变化写回 URL：刷新后停在同一 tab，链接也能直接分享
watch(activeTab, (t) => {
  if (route.query.tab === t) return   // 防止与上面「URL → tab」方向形成回环
  router.replace({ query: { ...route.query, tab: t } })
})

async function copyKey() {
  const ok = await copyText(key.value)
  if (ok) ElMessage.success('已复制 device_key')
  else ElMessage.error('复制失败，请手动选中复制')
}

// 操作日志
const logs = ref([])
const logsTotal = ref(0)
const logsPage = ref(1)
const logsPageSize = 10
const logsLoading = ref(false)

const LOG_TYPE_META = {
  1: { text: '上线', type: 'success' },
  2: { text: '下线', type: 'info' },
  3: { text: '操作', type: 'primary' },
  4: { text: '指令', type: 'warning' },
  5: { text: '告警', type: 'danger' }
}
const logTypeMeta = (t) => LOG_TYPE_META[t] || { text: `类型${t}`, type: 'info' }

const online = computed(() => device.value?.online_status === 1)
const productName = computed(() => {
  const p = products.value.find((x) => x.product_key === device.value?.product_key)
  return p ? p.name : device.value?.product_key || '-'
})

let pollTimer = null

async function loadDeviceInfo() {
  try {
    const res = await listDevices(1, 10, key.value, '', 0)
    device.value = (res.data.items || []).find((d) => d.device_key === key.value) || null
  } catch {
    device.value = null
  }
}

async function loadProducts() {
  try {
    products.value = (await listProducts()).data.items || []
  } catch {
    products.value = []
  }
}

async function loadTsl() {
  try {
    tsl.value = (await getTsl(key.value)).data
  } catch {
    tsl.value = null
  }
}

async function loadLatest() {
  telemetryLoading.value = true
  try {
    latest.value = (await getLatestTelemetry(key.value)).data || {}
  } catch {
    latest.value = {}
  } finally {
    telemetryLoading.value = false
  }
}

async function loadLogs() {
  logsLoading.value = true
  try {
    const res = await listDeviceLogs(key.value, logsPage.value, logsPageSize)
    logs.value = res.data.items || []
    logsTotal.value = res.data.total || 0
  } catch {
    logs.value = []
  } finally {
    logsLoading.value = false
  }
}

// ===== E3 事件记录（lazy 面板 + 首次打开时才拉取）=====
const events = ref([])
const eventsTotal = ref(0)
const eventsPage = ref(1)
const eventsPageSize = 20
const eventsLoading = ref(false)
const eventsLoaded = ref(false)

const EVENT_TYPE_META = {
  info: { text: '信息', type: 'info' },
  alert: { text: '告警', type: 'warning' },
  fault: { text: '故障', type: 'danger' }
}
const eventTypeMeta = (t) => EVENT_TYPE_META[t] || { text: t || '信息', type: 'info' }

async function loadEvents() {
  eventsLoading.value = true
  try {
    const res = await listDeviceEvents(key.value, eventsPage.value, eventsPageSize)
    events.value = (res.data.items || []).map((e) => ({
      ...e,
      paramsText: e.params && Object.keys(e.params).length ? JSON.stringify(e.params) : '-'
    }))
    eventsTotal.value = res.data.total || 0
    eventsLoaded.value = true
  } catch {
    events.value = []
  } finally {
    eventsLoading.value = false
  }
}

function handleEventsPage(p) {
  eventsPage.value = p
  loadEvents()
}

watch(activeTab, (t) => {
  if (t === 'events' && !eventsLoaded.value) loadEvents()
})

function handleLogsPage(p) {
  logsPage.value = p
  loadLogs()
}

/** 下发成功（设备已执行）后本地回填遥测，不等下一轮轮询 */
function handleExecuted(patch) {
  latest.value = { ...latest.value, ...patch }
}

// ===== 调试日志：按方向隔离（down=下发控制，up=模拟上报），各视图只看自己那一半 =====
// 注意命名：`logs` 已被上方「操作日志」tab 占用，这里用 debugLogs 区分
const debugLogs = ref([])
const LOG_LIMIT = 50
const debugMode = ref('down')   // 调试题的子视图：down | up

const downLogs = computed(() => debugLogs.value.filter((l) => l.direction === 'down'))
const upLogs = computed(() => debugLogs.value.filter((l) => l.direction === 'up'))

function pushLog(entry) {
  const time = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  debugLogs.value = [
    { id: `${Date.now()}-${Math.random()}`, time, ...entry },
    ...debugLogs.value
  ].slice(0, LOG_LIMIT)
}

/** 指令轮询到状态跃迁时回填对应日志：0/1 → 2/3/4 的流转在日志里可见 */
function updateLogStatus({ requestId, status, statusType, response }) {
  if (!requestId) return
  const hit = debugLogs.value.find((l) => l.requestId === requestId)
  if (!hit) return
  hit.status = status
  hit.statusType = statusType
  if (response) hit.response = response
}

/** 只清当前方向的日志，互不影响 */
function clearLogs(direction) {
  debugLogs.value = debugLogs.value.filter((l) => l.direction !== direction)
}

// ===== E2 实时数据：WebSocket 为主，5s 轮询降级为兜底 =====
// 这样 WS 正常时不产生任何轮询请求；WS 断开（服务端未起 / 网络抖动）时自动回到轮询，
// 保证数据不断档 —— 不会出现"推送挂了页面就死了"。

let wsClient = null

function startFallbackPolling() {
  if (pollTimer) return
  pollTimer = setInterval(loadLatest, 5000)
}

function stopFallbackPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function connectRealtime() {
  wsClient = createTelemetrySocket({
    deviceKey: key.value,
    // WS 连通 → 停掉轮询，兑现「HTTP 轮询 → WS 推送」的优化
    onOpen: () => stopFallbackPolling(),
    // WS 断开 → 立刻恢复轮询兜底（重连由 ws.js 指数退避负责）
    onClose: () => startFallbackPolling(),
    onMessage: (msg) => {
      if (msg.device_key !== key.value) return
      if (msg.type === 'telemetry' && msg.properties) {
        latest.value = { ...latest.value, ...msg.properties }
      } else if (msg.type === 'status' && device.value) {
        device.value = { ...device.value, online_status: msg.online_status }
      }
    }
  })
}

function disconnectRealtime() {
  if (wsClient) {
    wsClient.close()
    wsClient = null
  }
}

function init() {
  loadDeviceInfo()
  loadProducts()
  loadTsl()
  loadLatest()
  loadLogs()
}

watch(key, () => {
  device.value = null
  tsl.value = null
  latest.value = {}
  logs.value = []
  logsPage.value = 1
  events.value = []
  eventsPage.value = 1
  eventsLoaded.value = false
  activeTab.value = 'info'
  init()
  // 订阅的设备变了 → 重建实时通道
  disconnectRealtime()
  startFallbackPolling()
  connectRealtime()
})

onMounted(() => {
  init()
  startFallbackPolling()   // 先起兜底；WS 一旦连通会自动把它停掉
  connectRealtime()
})

onBeforeUnmount(() => {
  disconnectRealtime()
  stopFallbackPolling()
})
</script>

<style scoped>
.device-detail {
  height: 100%;
  overflow-y: auto;
  padding: 16px 24px;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.device-name {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.detail-tabs {
  background: transparent;
}

.panel {
  background: #fff;
  border: 1px solid #e5eaf2;
  border-radius: var(--radius-md, 10px);
  padding: 16px;
  margin-bottom: 16px;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  margin-bottom: 14px;
  display: flex;
  align-items: center;
}

.muted {
  font-size: 12px;
  font-weight: 400;
  color: #9ca3af;
  margin-left: 8px;
}

.online-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #d1d5db;
  display: inline-block;
  margin-left: 6px;
}

.online-dot.on {
  background: #16a34a;
}

.online-text {
  font-size: 12px;
  color: #4b5563;
  margin-left: 4px;
}

.empty-desc p {
  font-size: 14px;
  font-weight: 600;
  color: #4b5563;
  margin-bottom: 6px;
}

.empty-desc span {
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.7;
}

.empty-desc code {
  background: #f3f4f6;
  border-radius: 4px;
  padding: 1px 6px;
  font-family: Consolas, monospace;
  font-size: 11px;
  color: #6b7280;
}

.log-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

/* 调试题内的方向子 tab（下发控制 / 模拟上报） */
.debug-subtabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.debug-subtabs :deep(.el-tabs__item) {
  font-size: 13px;
}

/* 调试台两栏：左功能点，右调试日志（sticky 跟随滚动） */
.debug-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.debug-grid .panel {
  margin-bottom: 0;
}

.debug-left {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.debug-right {
  min-width: 0;
  position: sticky;
  top: 0;
}

.debug-log-panel {
  height: 580px;
  display: flex;
  flex-direction: column;
}

@media (max-width: 1200px) {
  .debug-grid {
    grid-template-columns: 1fr;
  }

  .debug-right {
    position: static;
  }

  .debug-log-panel {
    height: 380px;
  }
}
</style>
