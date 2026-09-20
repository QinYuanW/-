<template>
  <div class="console">
    <div class="console-head">
      <div class="console-title">
        <span>设备控制台</span>
        <el-tag size="small" type="info" effect="plain">{{ total }} 台</el-tag>
        <!-- 4.5：把"数据是不是活的"显式说出来 —— 绿点=WS 已连（上下线实时），
             灰点=推送断开、已退回 60s 轮询兜底。没有这个指示，用户无法分辨
             "设备一直离线"和"推送挂了" -->
        <el-tooltip
          :content="realtime ? '实时推送已连接：设备上下线会立即反映到列表' : '推送未连接：已退回 60 秒轮询兜底'"
          placement="top"
        >
          <span class="rt-dot" :class="realtime ? 'on' : 'off'">
            <i></i>{{ realtime ? '实时' : '轮询' }}
          </span>
        </el-tooltip>
      </div>
    </div>

    <!-- 4.3：这三张卡是「**当前筛选结果集**」的口径，不是全平台快照 —— 卡片必须跟随筛选。
         旧实现拿**无筛选**的查询取数（listDevices(1,1) 等），于是筛出「离线 137 台」时
         卡上还写着"在线 1013"，那不是重复而是**误导**；本改动已修，并顺手省掉 3 个白请求。
         全平台的在线/离线/产品数请去仪表盘看 —— 两边从此不再说同一件事。

         ⚠️ 2026-09-20 二次修正：先前在「已按状态筛选」时把拆分卡置灰显示「—」，理由是
         "拆分必然一个为 0、没意义"。**这个理由是错的**：筛「离线」时得到的是"在线 0 / 离线 N"，
         那个 0 出现在「在线」卡上读起来完全正确（这批结果里在线 0 台），没有歧义；
         而「—」反而让人以为功能坏了（实测就是这个反馈）。现在改为**照常显示数字**，
         且因为拆分是确定的，连请求都不用发。 -->
    <div class="stats-bar">
      <div class="stat-card">
        <div class="stat-icon total"><el-icon><Connection /></el-icon></div>
        <div class="stat-body">
          <div class="stat-num">{{ total }}</div>
          <div class="stat-label">{{ resultLabel }}</div>
        </div>
      </div>
      <div class="stat-card" :class="{ muted: breakdownMissing }">
        <div class="stat-icon online"><el-icon><CircleCheck /></el-icon></div>
        <div class="stat-body">
          <div class="stat-num online-num">{{ numText(breakdown.online) }}</div>
          <div class="stat-label">其中在线</div>
        </div>
      </div>
      <div class="stat-card" :class="{ muted: breakdownMissing }">
        <div class="stat-icon offline"><el-icon><CircleClose /></el-icon></div>
        <div class="stat-body">
          <div class="stat-num offline-num">{{ numText(breakdown.offline) }}</div>
          <div class="stat-label">其中离线</div>
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
        @input="onSearchInput"
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
      <!-- E4：按分组筛选（null=全部分组，0=未分组，>0=具体分组） -->
      <el-select
        v-model="groupFilter"
        class="tool-select wide"
        placeholder="全部分组"
        @change="handleFilter"
      >
        <el-option label="全部分组" :value="null" />
        <el-option label="未分组" :value="0" />
        <el-option
          v-for="g in groups"
          :key="g.id"
          :label="`${g.name}（${g.deviceCount}）`"
          :value="g.id"
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
            <el-dropdown-item command="move-group" :disabled="!selectedKeys.length">移入分组…</el-dropdown-item>
            <el-dropdown-item command="ungroup" :disabled="!selectedKeys.length">移出分组</el-dropdown-item>
            <el-dropdown-item command="disable" divided :disabled="!selectedKeys.length">停用设备</el-dropdown-item>
            <el-dropdown-item command="enable" :disabled="!selectedKeys.length">启用设备</el-dropdown-item>
            <el-dropdown-item command="delete" divided :disabled="!selectedKeys.length">批量删除</el-dropdown-item>
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
      :default-sort="defaultSort"
      @select="handleSelect"
      @rename="openRename"
      @sort-change="handleSortChange"
      @page-change="handlePageChange"
      @size-change="handleSizeChange"
      @selection-change="handleSelectionChange"
    />

    <!-- 4.8：改设备名称（启停/删除是另外两个语义，不混在这里） -->
    <el-dialog v-model="renameVisible" title="修改设备名称" width="420px">
      <div class="rename-tip">
        设备标识 <span class="mono">{{ renameForm.device_key }}</span> 不可更改；
        名称只用于展示与搜索。
      </div>
      <el-input
        v-model="renameForm.device_name"
        placeholder="请输入新的设备名称"
        maxlength="64"
        show-word-limit
        @keyup.enter="confirmRename"
      />
      <template #footer>
        <el-button @click="renameVisible = false">取消</el-button>
        <el-button type="primary" :loading="renaming" @click="confirmRename">保存</el-button>
      </template>
    </el-dialog>

    <!-- E4：批量移入分组 -->
    <el-dialog v-model="moveDialogVisible" title="移入分组" width="420px">
      <el-select v-model="moveTargetGroup" class="move-select" placeholder="选择目标分组">
        <el-option
          v-for="g in groups"
          :key="g.id"
          :label="`${g.name}（当前 ${g.deviceCount} 台）`"
          :value="g.id"
        />
      </el-select>
      <div class="move-tip">将把已选中的 {{ selectedKeys.length }} 台设备移入该分组（原分组归属会被覆盖）</div>
      <template #footer>
        <el-button @click="moveDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="moving" @click="confirmMoveGroup">确定</el-button>
      </template>
    </el-dialog>

    <DeviceRegister ref="registerRef" :show-button="false" @registered="loadDevices" />
    <BatchListDialog ref="batchListRef" />
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus, Operation, Download, Tickets, Grid, List } from '@element-plus/icons-vue'
import {
  listDevices,
  listProducts,
  deleteDevices,
  listDeviceGroups,
  assignDevicesToGroup,
  updateDeviceStatus,
  renameDevice
} from '../api'
import { downloadCsv, formatTime } from '../utils/export'
import { createTelemetrySocket } from '../api/ws'
import DeviceCards from '../components/DeviceCards.vue'
import DeviceList from '../components/DeviceList.vue'
import DeviceRegister from '../components/DeviceRegister.vue'
import BatchListDialog from '../components/BatchListDialog.vue'

const route = useRoute()
const router = useRouter()

const devices = ref([])
const products = ref([])
const groups = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const keyword = ref('')
const statusFilter = ref(0)
const productKey = ref('')
// E4：分组筛选（null=全部分组 / 0=未分组 / >0=具体分组）
const groupFilter = ref(null)
const selectedKeys = ref([])
const viewMode = ref('card')
// 4.3：结果集的在线/离线拆分（null = 取数失败，此时显示「—」而不是假报一个 0）
const breakdown = ref({ online: null, offline: null })
let breakdownKey = ''

/** 是否有任何筛选生效（决定第一张卡叫「设备总数」还是「筛选结果」） */
const hasFilter = computed(() => !!(
  keyword.value.trim() || productKey.value || groupFilter.value !== null || statusFilter.value
))

/** 仅在取数失败时为真（用于把卡片淡化）—— 正常情况必须有数字，不要用「—」糊弄 */
const breakdownMissing = computed(() => breakdown.value.online === null && breakdown.value.offline === null)

const resultLabel = computed(() => (hasFilter.value ? '筛选结果' : '设备总数'))

const numText = (v) => (v === null || v === undefined ? '—' : v)

// E4：批量移入分组
const moveDialogVisible = ref(false)
const moveTargetGroup = ref(null)
const moving = ref(false)

const listRef = ref()
const registerRef = ref()
const batchListRef = ref()

// ===== 4.2 排序 =====
// 交给后端排（前端只能排当前页 20 条，而这里有 1000+ 台、50 多页，那样排出来的结果是错的）
const sortBy = ref('')
const sortOrder = ref('')

/** 从 URL 恢复时点亮列头箭头（el-table 的 default-sort 只在初始化时生效） */
const defaultSort = computed(() => (
  sortBy.value ? { prop: sortBy.value, order: sortOrder.value === 'asc' ? 'ascending' : 'descending' } : {}
))

function handleSortChange({ prop, order }) {
  // order 为 null = 用户点了第三次（取消排序）→ 回到后端默认的「在线优先 + 最近活跃」
  sortBy.value = order ? (prop || '') : ''
  sortOrder.value = order === 'ascending' ? 'asc' : order === 'descending' ? 'desc' : ''
  page.value = 1
  loadDevices()
}

// ===== 4.8 改设备名称 =====
const renameVisible = ref(false)
const renaming = ref(false)
const renameForm = reactive({ device_key: '', device_name: '' })

function openRename(row) {
  renameForm.device_key = row.device_key
  renameForm.device_name = row.device_name || ''
  renameVisible.value = true
}

async function confirmRename() {
  const name = renameForm.device_name.trim()
  if (!name) {
    ElMessage.warning('设备名称不能为空')
    return
  }
  renaming.value = true
  try {
    const res = await renameDevice(renameForm.device_key, name)
    ElMessage.success(res.data?.updated ? '名称已更新' : '名称没有变化')
    renameVisible.value = false
    loadDevices()
  } catch (e) {
    // 拦截器已提示
  } finally {
    renaming.value = false
  }
}

const EXPORT_LIMIT = 5000

async function loadDevices(opts) {
  // 注意用 opts?.silent 而不是解构默认值：模板里 @registered="loadDevices" 会把事件载荷
  // 当作第一个参数传进来，解构非对象参数会直接抛错
  const silent = !!(opts && opts.silent)
  if (!silent) loading.value = true
  try {
    const res = await listDevices(
      page.value,
      size.value,
      keyword.value,
      productKey.value,
      statusFilter.value,
      groupFilter.value,
      sortBy.value,
      sortOrder.value
    )
    devices.value = res.data.items || []
    total.value = res.data.total || 0
  } catch (e) {
    devices.value = []
  } finally {
    if (!silent) loading.value = false
    loadBreakdown() // 不 await：列表先渲染，统计随后补上
  }
}

// ===== 4.5 实时推送（E2 早已落地，此前只接了设备详情页）=====
// 列表页原来只在进入时拉一次：设备上下线不会反映到列表（评审 4.5）。
// 这里接 WS —— telemetry / status 两种报文都能把在线状态落到当前页的行上；
// WS 断开自动退回 60s 静默轮询，避免"推送一挂页面就死"。
const realtime = ref(false) // WS 是否已连接（头部绿点/灰点就是它）
let socket = null
let fallbackTimer = null
const FALLBACK_POLL_MS = 60000

/** 本地时间格式化：telemetry 的 ts 是 epoch 毫秒，而 utils/formatTime 只认 ISO 字符串 */
function fmtLocal(ms) {
  const d = new Date(Number(ms))
  if (Number.isNaN(d.getTime())) return ''
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} `
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/**
 * 把一条实时状态变更应用到列表与统计卡。
 *
 * 只处理**当前页可见的行** —— 它必然属于当前筛选结果集，所以统计可以精确 ±1。
 * 不在当前页的设备不去猜它属于哪些筛选（报文里没有 product/group 信息），
 * **保守不动统计**：下次刷新会纠正，好过在这里算出一个错的数。
 */
function applyStatusChange(deviceKey, onlineStatus, at) {
  const row = devices.value.find((d) => d.device_key === deviceKey)
  if (!row) return
  const wasOnline = row.online_status === 1
  const nowOnline = onlineStatus === 1
  row.online_status = nowOnline ? 1 : 0
  if (at) row.last_online_at = at
  if (wasOnline === nowOnline) return

  const b = breakdown.value
  if (b.online === null || b.offline === null) return
  breakdown.value = {
    online: b.online + (nowOnline ? 1 : -1),
    offline: b.offline + (nowOnline ? -1 : 1)
  }
}

function startRealtime() {
  socket = createTelemetrySocket({
    // 不传 device_key = 订阅全部设备
    onOpen: () => { realtime.value = true; stopFallbackPolling() },
    onClose: () => { realtime.value = false; startFallbackPolling() },
    onMessage: (msg) => {
      if (msg.type === 'telemetry') {
        // 「收到上报即视为在线」—— 与后端 refreshOnline 的语义保持一致
        applyStatusChange(msg.device_key, 1, msg.ts ? fmtLocal(msg.ts) : '')
      } else if (msg.type === 'status') {
        applyStatusChange(msg.device_key, msg.online_status, msg.at || '')
      }
    }
  })
}

function stopRealtime() {
  if (socket) socket.close()
  socket = null
  stopFallbackPolling()
}

/** WS 断开时的兜底：静默轮询，保证"推送挂了列表也不会僵住" */
function startFallbackPolling() {
  if (fallbackTimer) return
  fallbackTimer = setInterval(() => loadDevices({ silent: true }), FALLBACK_POLL_MS)
}

function stopFallbackPolling() {
  if (fallbackTimer) clearInterval(fallbackTimer)
  fallbackTimer = null
}

async function loadGroups() {
  try {
    groups.value = (await listDeviceGroups()).data.items || []
  } catch {
    groups.value = []
  }
}

async function loadProducts() {
  try {
    products.value = (await listProducts()).data.items || []
  } catch {
    products.value = []
  }
}

/**
 * 4.3：取「当前筛选结果集」的在线/离线拆分（结果集总数直接用 loadDevices 返回的 total）。
 *
 * ⚠️ key 里带上 `total` —— 它同时是「筛选变了」与「集合变了（增删设备）」的信号：
 *   翻页不重复请求，而增删/移组后会自动重算，不必在每个改动点记得手动刷新统计。
 * 代价：设备上下线不会即时反映在这里 —— 但设备列表本身也是快照，两者口径一致反而对
 *   （实时性统一交给 4.5「列表接 WS 推送」解决）。
 */
async function loadBreakdown() {
  const key = [keyword.value.trim(), productKey.value, groupFilter.value, statusFilter.value, total.value].join('|')
  if (key === breakdownKey) return
  breakdownKey = key

  // 已按状态筛选时拆分是**确定的**，连请求都不用发：
  //   筛「离线」→ 结果集全离线，于是 在线 0 / 离线 total；筛「在线」反之。
  if (statusFilter.value === 1) {
    breakdown.value = { online: total.value, offline: 0 }
    return
  }
  if (statusFilter.value === 2) {
    breakdown.value = { online: 0, offline: total.value }
    return
  }
  try {
    const [on, off] = await Promise.all([
      listDevices(1, 1, keyword.value, productKey.value, 1, groupFilter.value),
      listDevices(1, 1, keyword.value, productKey.value, 2, groupFilter.value)
    ])
    breakdown.value = { online: on.data.total ?? 0, offline: off.data.total ?? 0 }
  } catch {
    breakdown.value = { online: null, offline: null } // 统计失败不阻塞页面，卡片显示「—」
  }
}

let searchTimer = null

/** 边输边搜（300ms 防抖）：原来只有回车才触发，用户输完没反应会以为搜索坏了 */
function onSearchInput() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(handleSearch, 300)
}

function handleSearch() {
  clearTimeout(searchTimer)
  page.value = 1
  listRef.value?.clearSelection()
  selectedKeys.value = []
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
  } else if (command === 'move-group') {
    moveTargetGroup.value = null
    moveDialogVisible.value = true
  } else if (command === 'ungroup') {
    await doAssign(0, '已移出分组')
  } else if (command === 'disable') {
    await doToggleStatus(0)
  } else if (command === 'enable') {
    await doToggleStatus(1)
  }
}

/**
 * F2 批量启停设备。
 *
 * 停用是「软禁用」：设备立即无法 MQTT 接入，但**遥测/日志/影子/指令全部保留**，可一键恢复 ——
 * 与批量删除（级联清空、不可逆）是完全不同的语义，因此单独确认、文案也刻意强调"保留"。
 */
async function doToggleStatus(status) {
  if (!selectedKeys.value.length) return
  const isDisable = status === 0
  try {
    await ElMessageBox.confirm(
      isDisable
        ? `将停用 ${selectedKeys.value.length} 台设备：立即拒绝其 MQTT 接入与数据上报，但历史数据、影子与配置全部保留，可随时重新启用。`
        : `将启用 ${selectedKeys.value.length} 台设备，恢复其接入与上报能力。`,
      isDisable ? '停用设备' : '启用设备',
      { type: 'warning', confirmButtonText: isDisable ? '停用' : '启用' }
    )
  } catch {
    return
  }
  try {
    const res = await updateDeviceStatus(selectedKeys.value, status)
    ElMessage.success(`${isDisable ? '已停用' : '已启用'} ${res.data.affected} 台设备`)
    listRef.value?.clearSelection?.()
    selectedKeys.value = []
    await loadDevices()
  } catch (e) {
    // 拦截器已提示
  }
}

/**
 * E4：把选中设备分配到某分组。
 * @param {number} groupId 0 或 null = 移出分组（回到「未分组」）
 */
async function doAssign(groupId, okMsg) {
  if (!selectedKeys.value.length) return
  try {
    const res = await assignDevicesToGroup(groupId ?? 0, selectedKeys.value)
    ElMessage.success(`${okMsg}（${res.data.affected} 台）`)
    listRef.value?.clearSelection?.()
    selectedKeys.value = []
    // 设备的分组归属变了 → 列表与分组计数都要刷新
    await Promise.all([loadDevices(), loadGroups()])
  } catch (e) {
    // 拦截器已提示
  }
}

async function confirmMoveGroup() {
  if (!moveTargetGroup.value) {
    ElMessage.warning('请先选择目标分组')
    return
  }
  moving.value = true
  try {
    await doAssign(moveTargetGroup.value, '已移入分组')
    moveDialogVisible.value = false
  } finally {
    moving.value = false
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
    const res = await listDevices(p, 100, keyword.value, productKey.value, statusFilter.value, groupFilter.value, sortBy.value, sortOrder.value)
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
  // 支持从别处带着筛选跳过来（仪表盘下钻 / 设备分组页跳转），并从 URL 恢复筛选状态
  const q = route.query
  // E4：?group_id=0 表示未分组
  if (q.group_id !== undefined && q.group_id !== null && q.group_id !== '') {
    groupFilter.value = Number(q.group_id)
  }
  // 3.1：?status=1 在线 / 2 离线（对齐 online_status 口径；0 或空 = 全部）
  if (q.status !== undefined && q.status !== null && q.status !== '') {
    statusFilter.value = Number(q.status)
  }
  if (q.product_key) productKey.value = String(q.product_key)
  if (q.keyword) keyword.value = String(q.keyword)
  // 4.2：排序也能从 URL 恢复，链接分享出去看到的顺序一致
  if (q.sort) sortBy.value = String(q.sort)
  if (q.order) sortOrder.value = String(q.order)

  syncQuery()
  loadDevices()
  loadProducts()
  loadGroups()
  // 4.5：先起兜底轮询（万一 WS 一直连不上），WS 一旦 onopen 就会把它停掉
  startRealtime()
  startFallbackPolling()
})

/**
 * 把当前筛选写回 URL（replace，不留历史记录）。
 *
 * 这样「仪表盘下钻 → 设备列表」落下的是**带筛选的链接**：刷新不丢、能直接发给别人，
 * 也回答了"我到底筛了什么"（筛选控件本身也会显示选中态）。
 * ⚠️ 值为默认值时必须从 query 里删掉，否则 URL 会挂满 `status=0&product_key=` 这类噪音。
 */
function syncQuery() {
  const next = { ...route.query }
  const set = (k, v) => { next[k] = String(v) }
  const clear = (k) => { delete next[k] }

  statusFilter.value ? set('status', statusFilter.value) : clear('status')
  productKey.value ? set('product_key', productKey.value) : clear('product_key')
  keyword.value.trim() ? set('keyword', keyword.value.trim()) : clear('keyword')
  // 分组：null = 全部分组（清掉）；0 = 未分组，是有效值必须留下
  groupFilter.value === null ? clear('group_id') : set('group_id', groupFilter.value)
  // 4.2：排序（空 = 后端默认的「在线优先 + 最近活跃」）
  sortBy.value ? set('sort', sortBy.value) : clear('sort')
  sortOrder.value ? set('order', sortOrder.value) : clear('order')

  // 逐键排序后比较：对象字面量的键序不保证一致，直接 JSON.stringify 会误判"变了"而反复 replace
  const canon = (o) => JSON.stringify(Object.keys(o).sort().map((k) => [k, String(o[k])]))
  if (canon(next) !== canon(route.query)) router.replace({ query: next })
}

// 筛选/排序一变就同步 URL（控件分散在模板各处，用 watch 比逐个改 @change handler 更不容易漏）
watch([statusFilter, productKey, keyword, groupFilter, sortBy, sortOrder], syncQuery)

onBeforeUnmount(() => {
  clearTimeout(searchTimer)
  stopRealtime()
})
</script>

<style scoped>
/* E4：批量移入分组对话框 */
.move-select {
  width: 100%;
}

.move-tip {
  margin-top: 10px;
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.6;
}

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

/* 4.8：改名弹窗里的说明（说清"能改什么、不能改什么"） */
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

/* 4.5：实时/轮询指示点 —— 让"数据是不是活的"可见。
   没有它，用户分不清"设备一直离线"和"推送挂了" */
.rt-dot {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 400;
  padding: 1px 8px;
  border-radius: 10px;
  background: #f1f5f9;
  color: #64748b;
  cursor: default;
}

.rt-dot i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #94a3b8;
}

.rt-dot.on {
  background: #ecfdf5;
  color: #047857;
}

.rt-dot.on i {
  background: #10b981;
}

.stats-bar {
  display: grid;
  grid-template-columns: repeat(3, 1fr); /* 4.3：产品数卡已删（与仪表盘重复且与设备页无关） */
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

/* 仅在统计取数失败时淡化（数字显示「—」）—— 正常情况必须有数字，
   拿「—」糊弄会让人以为功能坏了（2026-09-20 实测反馈） */
.stat-card.muted {
  opacity: 0.5;
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
