<template>
  <div class="device-detail">
    <div class="detail-head">
      <el-button text :icon="Back" @click="$router.push('/')">返回</el-button>
      <span class="device-name">{{ device?.device_name || key }}</span>
      <el-tag size="small" type="info" effect="plain">{{ key }}</el-tag>
      <el-button text size="small" :icon="CopyDocument" title="复制 device_key" @click="copyKey" />
        <span class="online-dot" :class="online ? 'on' : ''"></span>
        <span class="online-text">{{ online ? '在线' : '离线' }}</span>
        <!-- F5：停用与在线是两个维度。详情页也要显示停用态 —— 否则用户看到"离线"却不知道
             是自己（或产品）把它停了，而下面正好有「启用」按钮可以恢复 -->
        <el-tag v-if="device?.status === 0" size="small" type="warning" effect="plain">已停用</el-tag>
        <el-tag v-else-if="device?.product_enabled === false" size="small" type="danger" effect="plain">
          产品已停用
        </el-tag>

        <!-- 5.7：详情页原先没有任何编辑/启停/删除入口（头部只有「返回」）——
             改个名字都得退回列表页去点。这里补齐，三个动作语义分明：
             改名（不影响链路）/ 停用（保留数据、可一键恢复）/ 删除（不可逆） -->
        <el-dropdown class="head-more" @command="onMoreCommand">
          <el-button text size="small" :icon="MoreFilled">更多</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="rename">重命名</el-dropdown-item>
              <el-dropdown-item command="toggle">
                {{ device?.status === 0 ? '启用设备' : '停用设备' }}
              </el-dropdown-item>
              <el-dropdown-item command="delete" divided>删除设备</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
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
              <!-- 设备的物模型是**产品的继承视图**：能看不能改。
                   原先这里只显示"N 属性 · M 服务"，既不说来源也不给出口 ——
                   用户想加个属性会在这里找半天（死胡同）。点明继承关系 + 给一个直达入口。 -->
              <div class="tsl-inherit">
                <span>{{ tsl ? `${(tsl.properties || []).length} 属性 · ${(tsl.services || []).length} 服务` : '-' }}</span>
                <span class="muted">继承自产品</span>
                <el-button link type="primary" size="small" @click="goProductTsl">查看 / 修改</el-button>
              </div>
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
          <MetricCards :tsl="tsl" :telemetry="latest" :ts="latestTs" :loading="telemetryLoading" />
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

    <!-- 5.7：重命名（device_key 不可改 —— 它进 MQTT 主题，改了设备就断了） -->
    <el-dialog v-model="renameVisible" title="修改设备名称" width="420px">
      <div class="rename-tip">
        设备标识 <span class="mono">{{ key }}</span> 不可更改；名称只用于展示与搜索。
      </div>
      <el-input
        v-model="renameForm.device_name"
        placeholder="请输入新的设备名称"
        maxlength="64"
        show-word-limit
        @keyup.enter="doRename"
      />
      <template #footer>
        <el-button @click="renameVisible = false">取消</el-button>
        <el-button type="primary" :loading="renaming" @click="doRename">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back, CopyDocument, MoreFilled } from '@element-plus/icons-vue'
import {
  listDevices,
  listProducts,
  getTsl,
  getLatestTelemetry,
  listDeviceLogs,
  listDeviceEvents,
  renameDevice,
  updateDeviceStatus,
  deleteDevices
} from '../api'
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
/** 5.3：最新遥测的数据时间（epoch 毫秒；0 = 还没有数据） */
const latestTs = ref(0)
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

/**
 * 跳到「产品详情 → 物模型」。
 *
 * 设备的物模型继承自产品（同一个产品下所有设备共用一份 TSL），
 * 所以增删属性只能在产品侧做 —— 这里给设备详情页一个直达出口，
 * 免得用户在这个只读视图里找编辑按钮。
 */
function goProductTsl() {
  const pk = device.value?.product_key
  if (!pk) return
  router.push({ path: `/products/${pk}`, query: { tab: 'tsl' } })
}

let pollTimer = null

async function loadDeviceInfo() {
  try {
    // size=50 而不是 10：keyword 是 LIKE 匹配，若本机 key 恰好是另一台的前缀，
    // 精确那台可能被挤到第 10 条之后 → device 变成 null → 详情页显示"设备不存在"。
    // 这不是臆测的边界，而是"静默错状态"（页面看着正常，只是说没有这台设备）。
    const res = await listDevices(1, 50, key.value, '', 0)
    device.value = (res.data.items || []).find((d) => d.device_key === key.value) || null
  } catch {
    device.value = null
  }
}

// ===== 5.7 详情页的编辑 / 启停 / 删除 =====
const renameVisible = ref(false)
const renaming = ref(false)
const renameForm = reactive({ device_name: '' })

function onMoreCommand(cmd) {
  if (cmd === 'rename') openRename()
  else if (cmd === 'toggle') toggleDeviceStatus()
  else if (cmd === 'delete') removeDevice()
}

function openRename() {
  renameForm.device_name = device.value?.device_name || ''
  renameVisible.value = true
}

async function doRename() {
  const name = renameForm.device_name.trim()
  if (!name) {
    ElMessage.warning('设备名称不能为空')
    return
  }
  renaming.value = true
  try {
    const res = await renameDevice(key.value, name)
    ElMessage.success(res.data?.updated ? '名称已更新' : '名称没有变化')
    renameVisible.value = false
    await loadDeviceInfo()
  } catch (e) {
    // 拦截器已提示
  } finally {
    renaming.value = false
  }
}

/**
 * 启用 / 停用。
 *
 * <p>走的是**批量启停接口**（单台传长度为 1 的列表）—— 复用同一套语义与缓存失效逻辑，
 * 不另开一个"单台启停"的端点（那会变成两份要同步维护的行为）。
 */
async function toggleDeviceStatus() {
  const d = device.value
  if (!d) return
  const disable = d.status !== 0
  try {
    await ElMessageBox.confirm(
      disable
        ? `停用后「${d.device_name || d.device_key}」将立即无法接入 MQTT，但遥测、日志、影子、指令全部保留，随时可一键恢复。`
        : `重新启用「${d.device_name || d.device_key}」后，设备可再次接入。`,
      disable ? '停用设备' : '启用设备',
      { type: 'warning', confirmButtonText: disable ? '停用' : '启用' }
    )
  } catch {
    return
  }
  try {
    await updateDeviceStatus([d.device_key], disable ? 0 : 1)
    ElMessage.success(disable ? '设备已停用' : '设备已启用')
    await loadDeviceInfo()
  } catch (e) {
    // 拦截器已提示
  }
}

/** 删除：不可逆，且会级联清理遥测/日志/指令/影子 —— 文案要把这件事说清 */
async function removeDevice() {
  const d = device.value
  if (!d) return
  try {
    await ElMessageBox.confirm(
      `将删除设备「${d.device_name || d.device_key}」（遥测、日志、指令、影子一并清理），此操作不可恢复。\n` +
      '如果只是暂时不想让它接入，请改用「停用设备」。',
      '删除设备',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }
  try {
    await deleteDevices([d.device_key])
    ElMessage.success('设备已删除')
    router.push('/')
  } catch (e) {
    // 拦截器已提示
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
    // 5.3：接口响应体从「裸属性表」改成 {ts, properties} —— ts 用来显示「数据时间」，
    // 否则看到 26.5℃ 无法判断是刚上报的还是几小时前的。
    // 这里同时兼容旧结构：前端与后端是两个独立仓库/进程，改接口时必然有一段
    // 「前端已更新、后端还没重启」的窗口 —— 不兼容的话那段时间卡片全变成 "--"。
    const d = (await getLatestTelemetry(key.value)).data || {}
    latest.value = d.properties || d
    latestTs.value = Number(d.ts) || 0
  } catch {
    latest.value = {}
    latestTs.value = 0
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

// lazy 面板 + 首次打开时才拉取。immediate：处理「带 ?tab=events 直达」的情况 ——
// 此时 activeTab 初始值就是 events，watch 不加 immediate 不会触发，数据就永远不加载
watch(activeTab, (t) => {
  if (t === 'events' && !eventsLoaded.value) loadEvents()
}, { immediate: true })

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
/** WS 是否已连（决定"回到前台时要不要重启轮询兜底"） */
let wsConnected = false

function startFallbackPolling() {
  // 5.8：后台标签页不轮询 —— 每 5 秒打一次接口纯属浪费（评审 5.8），
  // 而且用户切回来看到的还是"离开那一刻"的旧值，不如回来时立刻补一次。
  if (pollTimer || document.hidden) return
  pollTimer = setInterval(loadLatest, 5000)
}

function stopFallbackPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/**
 * 5.8：标签页可见性变化。
 *
 * <ul>
 *   <li>隐藏 → 停轮询（省请求）</li>
 *   <li>回到前台 → **立刻补一次**最新值；WS 未连时顺带恢复轮询兜底</li>
 * </ul>
 * 注意"回到前台"这件事本身永远要拉一次：后台期间若 WS 也断了，
 * 页面在用户眼里会停在旧值上，看不出任何异常。
 */
function onVisibilityChange() {
  if (document.hidden) {
    stopFallbackPolling()
    return
  }
  loadLatest()
  if (!wsConnected) startFallbackPolling()
}

function connectRealtime() {
  wsClient = createTelemetrySocket({
    deviceKey: key.value,
    // WS 连通 → 停掉轮询，兑现「HTTP 轮询 → WS 推送」的优化
    onOpen: () => {
      wsConnected = true
      stopFallbackPolling()
    },
    // WS 断开 → 立刻恢复轮询兜底（重连由 ws.js 指数退避负责）
    onClose: () => {
      wsConnected = false
      startFallbackPolling()
    },
    onMessage: (msg) => {
      if (msg.device_key !== key.value) return
      if (msg.type === 'telemetry' && msg.properties) {
        latest.value = { ...latest.value, ...msg.properties }
        // 5.3：WS 报文里的 ts 也要落到"数据时间"上，否则卡片显示的时间会停在首屏那一次
        if (msg.ts) latestTs.value = Number(msg.ts)
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
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  disconnectRealtime()
  stopFallbackPolling()
  document.removeEventListener('visibilitychange', onVisibilityChange)
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

/* 5.7：「更多」下拉推到最右 —— 它是页面级操作，不该跟状态标签挤在一起 */
.head-more {
  margin-left: auto;
}

/* 5.7：重命名弹窗里的说明 */
.rename-tip {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.7;
  margin-bottom: 10px;
}

.rename-tip .mono {
  font-family: Consolas, monospace;
  color: #1f2937;
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
/* 设备信息里「物模型」那一行：数量 + 继承说明 + 跳转，三者一行放得下 */
.tsl-inherit {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
