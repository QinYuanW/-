<template>
  <div class="console">
    <div class="console-head">
      <div class="console-title">
        <span>设备控制台</span>
        <el-tag size="small" type="info" effect="plain">{{ total }} 台</el-tag>
      </div>
    </div>

    <div class="stats-bar">
      <div class="stat-card">
        <div class="stat-icon total"><el-icon><Connection /></el-icon></div>
        <div class="stat-body">
          <div class="stat-num">{{ stats.total }}</div>
          <div class="stat-label">设备总数</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon online"><el-icon><CircleCheck /></el-icon></div>
        <div class="stat-body">
          <div class="stat-num online-num">{{ stats.online }}</div>
          <div class="stat-label">在线设备</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon offline"><el-icon><CircleClose /></el-icon></div>
        <div class="stat-body">
          <div class="stat-num offline-num">{{ stats.offline }}</div>
          <div class="stat-label">离线设备</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon product"><el-icon><Goods /></el-icon></div>
        <div class="stat-body">
          <div class="stat-num">{{ stats.products }}</div>
          <div class="stat-label">产品总数</div>
        </div>
      </div>
    </div>

    <div class="toolbar">
      <el-input
        v-model="keyword"
        class="tool-search"
        placeholder="搜索 device_key / 名称"
        clearable
        :prefix-icon="Search"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <el-select v-model="statusFilter" class="tool-select" placeholder="全部状态" @change="handleFilter">
        <el-option label="全部状态" :value="0" />
        <el-option label="在线" :value="1" />
        <el-option label="离线" :value="2" />
      </el-select>
      <el-select
        v-model="productKey"
        class="tool-select wide"
        clearable
        placeholder="按产品筛选"
        @change="handleFilter"
      >
        <el-option
          v-for="p in products"
          :key="p.product_key"
          :label="`${p.name}（${p.product_key}）`"
          :value="p.product_key"
        />
      </el-select>

      <div class="tool-spacer" />

      <el-radio-group v-model="viewMode" size="small" class="view-toggle">
        <el-radio-button value="card">
          <el-icon><Grid /></el-icon>
          <span class="toggle-label">卡片</span>
        </el-radio-button>
        <el-radio-button value="list">
          <el-icon><List /></el-icon>
          <span class="toggle-label">列表</span>
        </el-radio-button>
      </el-radio-group>

      <el-button type="primary" :icon="Plus" @click="registerRef?.openDialog()">注册设备</el-button>
      <el-dropdown @command="handleBatchAction">
        <el-button :disabled="!selectedKeys.length" :icon="Operation">
          批量操作{{ selectedKeys.length ? `（${selectedKeys.length}）` : '' }}
        </el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="delete" :disabled="!selectedKeys.length">批量删除</el-dropdown-item>
            <el-dropdown-item command="export" :disabled="!selectedKeys.length">导出选中</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-button :icon="Download" @click="handleExportAll">导出全部</el-button>
      <el-button :icon="Tickets" @click="batchListRef?.open()">批次列表</el-button>
    </div>

    <DeviceCards
      v-if="viewMode === 'card'"
      ref="listRef"
      :devices="devices"
      :loading="loading"
      :total="total"
      :page="page"
      :size="size"
      :products="products"
      @select="handleSelect"
      @page-change="handlePageChange"
      @size-change="handleSizeChange"
      @selection-change="handleSelectionChange"
    />
    <DeviceList
      v-else
      ref="listRef"
      :devices="devices"
      :loading="loading"
      :total="total"
      :page="page"
      :size="size"
      :products="products"
      @select="handleSelect"
      @page-change="handlePageChange"
      @size-change="handleSizeChange"
      @selection-change="handleSelectionChange"
    />

    <DeviceRegister ref="registerRef" :show-button="false" @registered="loadDevices" />
    <BatchListDialog ref="batchListRef" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus, Operation, Download, Tickets, Grid, List } from '@element-plus/icons-vue'
import { listDevices, listProducts, deleteDevices } from '../api'
import { downloadCsv, formatTime } from '../utils/export'
import DeviceCards from '../components/DeviceCards.vue'
import DeviceList from '../components/DeviceList.vue'
import DeviceRegister from '../components/DeviceRegister.vue'
import BatchListDialog from '../components/BatchListDialog.vue'

const router = useRouter()

const devices = ref([])
const products = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const keyword = ref('')
const statusFilter = ref(0)
const productKey = ref('')
const selectedKeys = ref([])
const viewMode = ref('card')
const stats = ref({ total: 0, online: 0, offline: 0, products: 0 })

const listRef = ref()
const registerRef = ref()
const batchListRef = ref()

const EXPORT_LIMIT = 5000

async function loadDevices() {
  loading.value = true
  try {
    const res = await listDevices(page.value, size.value, keyword.value, productKey.value, statusFilter.value)
    devices.value = res.data.items || []
    total.value = res.data.total || 0
  } catch (e) {
    devices.value = []
  } finally {
    loading.value = false
  }
}

async function loadProducts() {
  try {
    products.value = (await listProducts()).data.items || []
  } catch {
    products.value = []
  }
}

// JetLinks/FastBee 式统计卡片：设备总数/在线/离线/产品数（复用现有分页接口取 total）
async function loadStats() {
  try {
    const [all, online, offline, prods] = await Promise.all([
      listDevices(1, 1),
      listDevices(1, 1, '', '', 1),
      listDevices(1, 1, '', '', 2),
      listProducts()
    ])
    stats.value = {
      total: all.data.total || 0,
      online: online.data.total || 0,
      offline: offline.data.total || 0,
      products: (prods.data.items || []).length
    }
  } catch {
    // 统计失败不阻塞页面
  }
}

function handleSearch() {
  page.value = 1
  loadDevices()
}

function handleFilter() {
  page.value = 1
  listRef.value?.clearSelection()
  selectedKeys.value = []
  loadDevices()
}

function handlePageChange(p) {
  page.value = p
  listRef.value?.clearSelection()
  selectedKeys.value = []
  loadDevices()
}

function handleSizeChange(s) {
  size.value = s
  page.value = 1
  listRef.value?.clearSelection()
  selectedKeys.value = []
  loadDevices()
}

function handleSelectionChange(rows) {
  selectedKeys.value = rows.map((r) => r.device_key)
}

function handleSelect(device) {
  router.push(`/devices/${device.device_key}`)
}

async function handleBatchAction(command) {
  if (command === 'delete') {
    await doDelete(selectedKeys.value)
  } else if (command === 'export') {
    exportRows(devices.value.filter((d) => selectedKeys.value.includes(d.device_key)))
  }
}

async function doDelete(keys) {
  if (!keys.length) return
  await ElMessageBox.confirm(
    `将删除 ${keys.length} 台设备（遥测、日志、指令、影子一并清理），此操作不可恢复。确定继续吗？`,
    '批量删除',
    { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
  )
  try {
    const res = await deleteDevices(keys)
    ElMessage.success(`已删除 ${res.data.deleted} 台设备`)
    listRef.value?.clearSelection()
    selectedKeys.value = []
    loadDevices()
  } catch (e) {
    // 拦截器已提示
  }
}

async function fetchAllForExport() {
  const all = []
  let p = 1
  while (all.length < EXPORT_LIMIT) {
    const res = await listDevices(p, 100, keyword.value, productKey.value, statusFilter.value)
    const items = res.data.items || []
    all.push(...items)
    if (all.length >= (res.data.total || 0)) break
    p += 1
  }
  return all
}

async function handleExportAll() {
  try {
    const rows = await fetchAllForExport()
    exportRows(rows)
  } catch (e) {
    // 拦截器已提示
  }
}

function exportRows(rows) {
  if (!rows.length) {
    ElMessage.warning('没有可导出的设备')
    return
  }
  const productNameOf = (key) => products.value.find((x) => x.product_key === key)?.name || key || ''
  downloadCsv(
    `devices_${new Date().toISOString().slice(0, 10)}.csv`,
    ['device_key', 'device_name', 'product_key', 'product_name', 'online_status', 'last_online_at'],
    rows.map((d) => ({
      device_key: d.device_key,
      device_name: d.device_name,
      product_key: d.product_key,
      product_name: productNameOf(d.product_key),
      online_status: d.online_status === 1 ? '在线' : '离线',
      last_online_at: formatTime(d.last_online_at)
    }))
  )
  ElMessage.success(`已导出 ${rows.length} 台设备`)
}

onMounted(() => {
  loadDevices()
  loadProducts()
  loadStats()
})
</script>

<style scoped>
.console {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 24px;
  overflow: hidden;
}

.console-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.console-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
  display: flex;
  align-items: center;
  gap: 8px;
}

.stats-bar {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.stat-card {
  background: #fff;
  border: 1px solid #e4e9f0;
  border-radius: var(--radius-md, 10px);
  padding: 14px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.stat-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  color: #fff;
  flex-shrink: 0;
}

.stat-icon.total {
  background: linear-gradient(135deg, #6366f1, #4f46e5);
}

.stat-icon.online {
  background: linear-gradient(135deg, #10b981, #059669);
}

.stat-icon.offline {
  background: linear-gradient(135deg, #9ca3af, #6b7280);
}

.stat-icon.product {
  background: linear-gradient(135deg, #3b82f6, #2563eb);
}

.stat-num {
  font-size: 22px;
  font-weight: 700;
  color: #1f2937;
  line-height: 1.1;
}

.stat-num.online-num {
  color: #059669;
}

.stat-num.offline-num {
  color: #6b7280;
}

.stat-label {
  font-size: 12px;
  color: #9ca3af;
  margin-top: 2px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.tool-search {
  width: 240px;
}

.tool-select {
  width: 130px;
}

.tool-select.wide {
  width: 220px;
}

.tool-spacer {
  flex: 1;
}

.view-toggle {
  margin-right: 4px;
}

.view-toggle :deep(.el-radio-button__inner) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.toggle-label {
  font-size: 12px;
}

@media (max-width: 900px) {
  .console {
    padding: 12px;
  }

  .stats-bar {
    grid-template-columns: repeat(2, 1fr);
  }

  .tool-search {
    width: 100%;
  }
}
</style>
