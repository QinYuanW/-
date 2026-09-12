<template>
  <div class="console">
    <header class="topbar">
      <div class="brand">
        <div class="brand-logo">
          <el-icon :size="18"><Connection /></el-icon>
        </div>
        <span>物联网 PaaS 控制台</span>
      </div>
      <div class="topbar-right">
        <el-button text @click="$router.push('/products')">产品与物模型</el-button>
        <span class="user">
          <el-icon><UserFilled /></el-icon>
          {{ username }}
        </span>
        <el-button text @click="handleLogout">退出登录</el-button>
      </div>
    </header>

    <div class="layout">
      <aside class="sidebar">
        <DeviceRegister @registered="loadDevices" />
        <div class="sidebar-list">
          <DeviceList
            :devices="devices"
            :selected-key="selectedKey"
            :loading="deviceLoading"
            :total="total"
            :page="page"
            :size="size"
            :products="products"
            @select="handleSelect"
            @search="handleSearch"
            @page-change="handlePageChange"
            @filter="handleFilter"
          />
        </div>
      </aside>

      <main class="main">
        <template v-if="selectedKey">
          <!-- 物模型说明 -->
          <div class="panel tsl-banner">
            <div class="panel-title">
              物模型驱动 · 界面按 TSL 自动生成
              <el-tag size="small" type="info" effect="plain" style="margin-left: 8px">
                {{ selectedKey }}
              </el-tag>
            </div>
            <div class="tsl-rule">
              <span><b class="c-readonly">readonly</b> → 数据卡片</span>
              <span><b class="c-readwrite">readwrite</b> → 卡片 + 控件</span>
              <span><b class="c-service">services</b> → 服务按钮</span>
              <span class="muted">新增传感器/控制项只需改产品 TSL，前端零改动</span>
            </div>
          </div>

          <!-- 最新遥测卡片 -->
          <div class="panel">
            <div class="panel-title">
              {{ selectedName }} · 最新数据
              <span class="online-dot" :class="online ? 'on' : ''"></span>
              <span class="online-text">{{ online ? '在线' : '离线' }}</span>
            </div>
            <MetricCards :tsl="tsl" :telemetry="latest" :loading="telemetryLoading" />
          </div>

          <div class="main-grid">
            <div class="panel">
              <div class="panel-title">历史曲线</div>
              <HistoryChart
                :device-key="selectedKey"
                :tsl="tsl"
                :telemetry="latest"
              />
            </div>
            <div class="panel">
              <div class="panel-title">下发控制</div>
              <ControlPanel
                :device-key="selectedKey"
                :tsl="tsl"
                :telemetry="latest"
                @executed="handleExecuted"
              />
            </div>
          </div>
        </template>

        <el-empty v-else description="请选择左侧设备" />
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import DeviceList from '../components/DeviceList.vue'
import DeviceRegister from '../components/DeviceRegister.vue'
import MetricCards from '../components/MetricCards.vue'
import HistoryChart from '../components/HistoryChart.vue'
import ControlPanel from '../components/ControlPanel.vue'
import { listDevices, getTsl, getLatestTelemetry, listProducts } from '../api'

const router = useRouter()
const username = localStorage.getItem('iot_username') || 'admin'

const devices = ref([])
const selectedKey = ref('')
const selectedName = ref('')
const deviceLoading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const keyword = ref('')
// E5：产品筛选与产品列表（供设备列表显示产品名 / 按产品过滤）
const products = ref([])
const productKey = ref('')

const tsl = ref(null)
const latest = ref({})
const telemetryLoading = ref(false)

const online = computed(() => {
  const dev = devices.value.find((d) => d.device_key === selectedKey.value)
  return dev ? dev.online_status === 1 : false
})

let pollTimer = null

async function loadDevices() {
  deviceLoading.value = true
  try {
    const res = await listDevices(page.value, size.value, keyword.value, productKey.value)
    devices.value = res.data.items || []
    total.value = res.data.total || 0
    if (!selectedKey.value && devices.value.length) {
      handleSelect(devices.value[0])
    }
  } finally {
    deviceLoading.value = false
  }
}

async function loadProducts() {
  try {
    products.value = (await listProducts()).data.items || []
  } catch (e) {
    products.value = []
  }
}

function handleSearch(kw) {
  keyword.value = kw
  page.value = 1
  loadDevices()
}

function handleFilter(pk) {
  productKey.value = pk
  page.value = 1
  loadDevices()
}

function handlePageChange(p) {
  page.value = p
  loadDevices()
}

async function handleSelect(device) {
  selectedKey.value = device.device_key
  selectedName.value = device.device_name || device.device_key
  await Promise.all([loadTsl(), loadLatest()])
}

async function loadTsl() {
  try {
    const res = await getTsl(selectedKey.value)
    tsl.value = res.data
  } catch (e) {
    tsl.value = null
  }
}

async function loadLatest() {
  telemetryLoading.value = true
  try {
    const res = await getLatestTelemetry(selectedKey.value)
    latest.value = res.data || {}
  } catch (e) {
    latest.value = {}
  } finally {
    telemetryLoading.value = false
  }
}

function handleExecuted({ key, value }) {
  latest.value = { ...latest.value, [key]: value }
}

async function handleLogout() {
  await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
  localStorage.removeItem('iot_token')
  localStorage.removeItem('iot_username')
  router.push('/login')
}

onMounted(() => {
  loadDevices()
  loadProducts()
  pollTimer = setInterval(() => {
    if (selectedKey.value) loadLatest()
  }, 5000)
})

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.console {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.topbar {
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e5eaf2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  flex-shrink: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.brand-logo {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #4b5563;
}

.layout {
  flex: 1;
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 16px;
  padding: 16px 24px;
  min-height: 0;
}

.sidebar {
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.sidebar-list {
  flex: 1;
  min-height: 0;
}

.main {
  min-height: 0;
  overflow-y: auto;
}

.panel {
  background: #fff;
  border: 1px solid #e5eaf2;
  border-radius: 10px;
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

.tsl-banner {
  background: linear-gradient(135deg, #f8fbff, #fff);
}

.tsl-rule {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  font-size: 12px;
  color: #4b5563;
}

.tsl-rule .muted {
  color: #9ca3af;
}

.c-readonly {
  color: #2563eb;
}

.c-readwrite {
  color: #d97706;
}

.c-service {
  color: #16a34a;
}

.online-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #cbd5e1;
  margin-left: 10px;
}

.online-dot.on {
  background: #16a34a;
  box-shadow: 0 0 0 3px rgba(22, 163, 74, 0.15);
}

.online-text {
  font-size: 12px;
  font-weight: 400;
  color: #6b7280;
  margin-left: 6px;
}

.main-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
</style>
