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
      <el-tab-pane label="设备信息" name="info">
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

      <el-tab-pane label="实时数据" name="telemetry">
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

      <el-tab-pane label="调试" name="debug">
        <div class="panel">
          <div class="panel-title">下发控制 · readwrite 属性 / 服务</div>
          <ControlPanel :device-key="key" :tsl="tsl" :telemetry="latest" @executed="handleExecuted" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="设备影子" name="shadow">
        <div class="panel">
          <div class="panel-title">
            设备影子 · 期望值 / 上报值 / 差异
            <el-tag size="small" type="success" effect="plain" style="margin-left: 8px">E1</el-tag>
          </div>
          <ShadowPanel :device-key="key" :tsl="tsl" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="事件记录" name="events">
        <div class="panel">
          <el-empty :image-size="90">
            <template #description>
              <div class="empty-desc">
                <p>事件记录功能规划中</p>
                <span>设备事件通过 <code>up/{{ key }}/event</code> topic 上报，事件存储与查询链路在后端 D/E 段任务实现后开放。</span>
              </div>
            </template>
          </el-empty>
        </div>
      </el-tab-pane>

      <el-tab-pane label="操作日志" name="logs">
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
import { listDevices, listProducts, getTsl, getLatestTelemetry, listDeviceLogs } from '../api'
import { formatTime } from '../utils/export'
import { copyText } from '../utils/clipboard'
import MetricCards from '../components/MetricCards.vue'
import HistoryChart from '../components/HistoryChart.vue'
import ControlPanel from '../components/ControlPanel.vue'
import ShadowPanel from '../components/ShadowPanel.vue'

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

function handleLogsPage(p) {
  logsPage.value = p
  loadLogs()
}

function handleExecuted({ key: k, value: v }) {
  latest.value = { ...latest.value, [k]: v }
}

function startPolling() {
  clearInterval(pollTimer)
  pollTimer = setInterval(loadLatest, 5000)
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
  activeTab.value = 'info'
  init()
  startPolling()
})

onMounted(() => {
  init()
  startPolling()
})

onBeforeUnmount(() => clearInterval(pollTimer))
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
</style>
