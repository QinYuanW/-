<template>
  <div class="product-detail">
    <div class="detail-head">
      <el-button text :icon="Back" @click="$router.push('/products')">返回</el-button>
      <span class="product-name">{{ product?.name || key }}</span>
      <el-tag size="small" type="info" effect="plain">{{ key }}</el-tag>
    </div>

    <el-tabs v-model="activeTab" class="product-tabs">
      <!-- 基础信息 -->
      <el-tab-pane label="基础信息" name="info">
        <div class="panel">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="产品名称">{{ product?.name || '-' }}</el-descriptions-item>
            <el-descriptions-item label="product_key">{{ key }}</el-descriptions-item>
            <el-descriptions-item label="接入协议">{{ product?.protocol || 'MQTT' }}</el-descriptions-item>
            <el-descriptions-item label="数据格式">{{ product?.data_format || 'JSON' }}</el-descriptions-item>
            <el-descriptions-item label="节点类型">{{ nodeTypeText }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatTime(product?.created_at) }}</el-descriptions-item>
            <el-descriptions-item label="物模型规模">
              {{ (tsl.properties || []).length }} 属性 ·
              {{ (tsl.events || []).length }} 事件 ·
              {{ (tsl.services || []).length }} 服务
            </el-descriptions-item>
          </el-descriptions>
          <p class="hint">
            控制台设备页按此产品的物模型自动渲染数据卡片与控制控件；修改物模型后，设备详情实时生效。
          </p>
        </div>
      </el-tab-pane>

      <!-- 物模型：三要素均可 UI 编辑 -->
      <el-tab-pane label="物模型" name="tsl">
        <div class="panel">
          <div class="tsl-head">
            <div class="tsl-head-left">
              <div class="tsl-title">物模型 · {{ product?.name || key }}</div>
            </div>
            <div class="tsl-actions">
              <!-- 通用物模型：从内置模板一键生成常见设备的属性/事件/服务，
                   省掉"手敲十几个 key"的过程。与「导入」一样，只填编辑区，仍需点保存才落库。 -->
              <el-button size="small" :icon="MagicStick" @click="tplRef?.open()">套用模板</el-button>
              <!-- 7.4：TSL 导入/导出 —— 把物模型复制到另一个产品原先只能手敲 -->
              <el-button size="small" :icon="Download" @click="exportTsl">导出</el-button>
              <el-button size="small" :icon="Upload" @click="openImport">导入</el-button>
              <!-- 存为模板：改完产品顺手存下来复用（模板入库，换机器也在） -->
              <el-button size="small" :icon="CollectionTag" @click="saveAsTemplate">存为模板</el-button>
              <!-- 7.1：把"有未保存改动"显式说出来，并在保存按钮上给出主次 —— 
                   否则用户看不出当前页面是否已经落库，切走时也不会预期有提醒 -->
              <el-tag v-if="dirty" size="small" type="warning" effect="plain">有未保存的改动</el-tag>
              <el-button
                type="primary"
                size="small"
                :loading="saving"
                :disabled="!dirty"
                @click="save"
              >
                保存物模型
              </el-button>
            </div>
          </div>

          <TslEditor
            v-model:section="tslSection"
            :properties="properties"
            :events="events"
            :services="services"
          />
        </div>
      </el-tab-pane>

      <!-- 设备列表 -->
      <el-tab-pane label="设备列表" name="devices">
        <div class="panel">
          <el-table v-loading="devicesLoading" :data="devices" size="small">
            <el-table-column prop="device_key" label="device_key" min-width="170" show-overflow-tooltip />
            <el-table-column prop="device_name" label="名称" min-width="110" show-overflow-tooltip />
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <span class="status-tag" :class="row.online_status === 1 ? 'on' : 'off'">
                  <i class="dot"></i>{{ row.online_status === 1 ? '在线' : '离线' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="最后在线" min-width="150">
              <template #default="{ row }">{{ formatTime(row.last_online_at) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="$router.push(`/devices/${row.device_key}`)">详情</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <el-empty description="该产品下暂无设备" :image-size="70" />
            </template>
          </el-table>
          <div class="dev-pager">
            <el-pagination
              small
              background
              layout="total, prev, pager, next"
              :total="devicesTotal"
              :page-size="devicesPageSize"
              :current-page="devicesPage"
              @current-change="handleDevicesPage"
            />
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 7.4：导入物模型（粘贴 JSON 或选文件） -->
    <el-dialog v-model="importVisible" title="导入物模型" width="600px">
      <div class="import-hint">
        粘贴物模型 JSON，或选择一个 .json 文件。导入会**整体替换**编辑区里的
        属性 / 事件 / 服务（还没落库，点「保存物模型」才生效，关掉页面即可放弃）。
      </div>
      <el-input
        v-model="importText"
        type="textarea"
        :rows="10"
        placeholder='{"properties":[{"key":"temperature","name":"温度","type":"float","unit":"℃"}],"events":[],"services":[]}'
      />
      <div class="import-file">
        <input ref="fileRef" type="file" accept=".json,application/json" @change="onPickFile" />
      </div>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" @click="doImport">导入</el-button>
      </template>
    </el-dialog>
    <!-- 通用物模型模板选择（只把结果交给编辑区，不直接落库） -->
    <TslTemplateDialog ref="tplRef" @apply="onTemplateApply" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back, Download, Upload, MagicStick, CollectionTag } from '@element-plus/icons-vue'
import { listProducts, getProductTsl, updateProductTsl, listDevices, createTslTemplate } from '../api'
import { formatTime } from '../utils/export'
import { mergeTsl } from '../constants/tslTemplates'
import TslTemplateDialog from '../components/TslTemplateDialog.vue'
import TslEditor from '../components/TslEditor.vue'
import { normalizeTsl, buildTsl } from '../utils/tslBuild'

const route = useRoute()
const key = computed(() => route.params.key)

const product = ref(null)
const loading = ref(false)
const saving = ref(false)
const activeTab = ref(route.query.tab || 'info')

const PRODUCT_TABS = ['info', 'tsl', 'devices']
watch(
  () => route.query.tab,
  (t) => {
    if (PRODUCT_TABS.includes(t)) activeTab.value = t
  }
)

// 物模型编辑态
const properties = ref([])
const events = ref([])
const services = ref([])

/**
 * 7.2：物模型内的分段（属性 / 事件 / 服务）。
 *
 * <p>用 `v-show` 而不是 `v-if`：三张表都留在 DOM 里，切换不重建表格、
 * 也不影响任何按 DOM 计算的逻辑（保存校验读的是 state 不是 DOM）。
 */
const tslSection = ref('properties')

// ===== 存为模板（用户自定义通用物模型）=====

/**
 * 把**当前编辑区**的物模型存成可复用模板。
 *
 * 为什么用编辑区而不是库里已保存的那份：用户此刻看到的就是他想要的样子 ——
 * 若改用库里的版本，会出现"我明明改好了，存下来的却是旧的"这种最伤信任的错。
 * 走 buildTsl({ properties: properties.value, events: events.value, services: services.value }) 复用同一套校验，避免"存了份存不进产品的模板"。
 */
async function saveAsTemplate() {
  const { errors, tsl: t } = buildTsl({ properties: properties.value, events: events.value, services: services.value })
  if (errors.length) {
    ElMessage.error(errors[0])
    return
  }
  const total = (t.properties?.length || 0) + (t.events?.length || 0) + (t.services?.length || 0)
  if (!total) {
    ElMessage.warning('当前物模型是空的，先加点属性再存吧')
    return
  }
  let name = ''
  try {
    const r = await ElMessageBox.prompt(
      '把当前编辑区的物模型存成可复用模板（存库，换机器也在）。之后可在侧栏「物模型」里套用到别的产品。',
      '存为模板',
      {
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValue: `${key.value} 的物模型`,
        inputPlaceholder: '模板名称',
        inputValidator: (v) => (v && v.trim() ? true : '模板名称不能为空')
      }
    )
    name = String(r.value || '').trim()
  } catch (e) {
    return // 用户取消
  }
  try {
    await createTslTemplate({
      name,
      description: `从产品 ${key.value} 存下的物模型`,
      tsl: t
    })
    ElMessage.success(`已存为模板「${name}」，可在侧栏「物模型」里找到`)
  } catch (e) {
    // 拦截器已提示
  }
}

// ===== 通用物模型模板 =====
const tplRef = ref()

/**
 * 套用模板到**编辑区**（与「导入」同一条路径：不直接落库，点保存才生效）。
 *
 * 为什么不直接落库：模板是"起点"不是"终点"——用户套完几乎总要改两个 key/量程，
 * 直接写库会让人以为"一按就改完并生效了"，改完忘了保存还更难发现。
 */
function onTemplateApply({ tsl: tplTsl, mode, templateName }) {
  const merged = mergeTsl(
    { properties: properties.value, events: events.value, services: services.value },
    tplTsl,
    mode
  )
  properties.value = merged.properties
  events.value = merged.events
  services.value = merged.services
  // 跳到有内容的那一段，让用户立刻看到结果
  tslSection.value = merged.properties.length
    ? 'properties'
    : (merged.events.length ? 'events' : 'services')
  ElMessage.success(
    mode === 'replace'
      ? `已套用「${templateName}」（${merged.added} 项），点「保存物模型」才会落库`
      : `已追加 ${merged.added} 项、跳过同名 ${merged.skipped} 项，点「保存物模型」才会落库`
  )
}

// ===== 7.4 TSL 导入 / 导出 =====
const importVisible = ref(false)
const importText = ref('')
const fileRef = ref()

/**
 * 导出**当前编辑中**的物模型（不是库里那一版）—— 用户点导出通常就是想把这版拿走。
 * 未保存的改动也会一起带走，这样"改完导出、去另一个产品导入"的流程才成立。
 */
function exportTsl() {
  const { tsl: t } = buildTsl({ properties: properties.value, events: events.value, services: services.value })
  const blob = new Blob([JSON.stringify(t, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${key.value}-tsl.json`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  // 延迟释放：紧跟着 click 就 revoke 有概率让下载"刚发起就被取消"
  // （headless 下必现：CDP 事件是 willBegin → canceled）。等浏览器真正接手再释放。
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function openImport() {
  importText.value = ''
  importVisible.value = true
}

function onPickFile(e) {
  const f = e.target.files && e.target.files[0]
  if (!f) return
  const reader = new FileReader()
  reader.onload = () => { importText.value = String(reader.result || '') }
  reader.readAsText(f, 'utf-8')
  // 清空 input：同一个文件再选一次也要能触发 change
  e.target.value = ''
}

/**
 * 导入：整体替换编辑区的三类定义。
 *
 * <p>字段映射与 {@link loadTsl} 保持一致 —— 两边形状不同的话，
 * 导入后会被 7.1 的脏检查判成"有未保存改动"（其实用户什么都没改），
 * 于是一切换 tab 就弹提醒，很烦。
 */
function doImport() {
  let parsed
  try {
    parsed = JSON.parse(importText.value)
  } catch (err) {
    ElMessage.error('JSON 解析失败：' + (err && err.message ? err.message : '格式不正确'))
    return
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    ElMessage.error('导入内容必须是一个 JSON 对象')
    return
  }
  if (!Array.isArray(parsed.properties) && !Array.isArray(parsed.events) && !Array.isArray(parsed.services)) {
    ElMessage.error('JSON 里至少要有一个 properties / events / services 数组')
    return
  }
  properties.value = (parsed.properties || []).map((p) => ({
    key: p.key || '',
    name: p.name || '',
    type: p.type || 'float',
    unit: p.unit ?? '',
    access: p.access || 'readonly',
    min: p.min ?? '',
    max: p.max ?? '',
    step: p.step ?? '',
    elements: (p.elements || []).map((e) => ({ value: e.value ?? '', key: e.key ?? '' }))
  }))
  events.value = (parsed.events || []).map((e) => ({
    key: e.key || '',
    name: e.name || '',
    type: e.type || 'info'
  }))
  services.value = (parsed.services || []).map((s) => ({
    key: s.key || '',
    name: s.name || '',
    callType: s.callType || 'async',
    inputData: (s.inputData || []).map((p) => ({ ...p })),
    outputData: (s.outputData || []).map((p) => ({ ...p }))
  }))
  importVisible.value = false
  // 跳到有内容的那一段，让用户立刻看到导入结果（否则"导入了什么也没发生"）
  tslSection.value = properties.value.length
    ? 'properties'
    : (events.value.length ? 'events' : 'services')
  ElMessage.success('已导入到编辑区，点「保存物模型」才会落库')
}
const tsl = computed(() => ({
  properties: properties.value,
  events: events.value,
  services: services.value
}))

// ===== 7.1 未保存改动提醒 =====
// 物模型可能改了十几行，切个 tab / 点返回 / 刷新就全丢，而页面不会拦一下（评审 7.1）。
// 脏判定用「载入快照 vs 当前状态」的 JSON 对比，而不是手动置 dirty 标志 ——
// 手动标志要覆盖每个输入框的每种事件（输入/勾选/增删行/枚举弹窗…），漏一个就静默失效。
const loadedSnapshot = ref('')

const currentSnapshot = () => JSON.stringify({
  properties: properties.value,
  events: events.value,
  services: services.value
})

const dirty = computed(() => loadedSnapshot.value !== '' && currentSnapshot() !== loadedSnapshot.value)

const LEAVE_CONFIRM = {
  type: 'warning',
  confirmButtonText: '放弃修改并离开',
  cancelButtonText: '留在本页',
  confirmButtonClass: 'el-button--danger'
}

/** 用户确认放弃时返回 true。ElMessageBox 取消会 reject，必须捕获 */
async function confirmLeave() {
  try {
    await ElMessageBox.confirm('物模型有未保存的改动，离开后本次修改将丢失。确定离开吗？', '有未保存的改动', LEAVE_CONFIRM)
    return true
  } catch {
    return false
  }
}

/**
 * 真正放弃修改：把三个数组还原到载入基线。
 *
 * 光"允许导航"是不够的 —— 用户点了「放弃修改并离开」后如果只是切了个 tab（人还在这个页面），
 * 那些"已放弃"的改动仍留在内存里，切回物模型还能看到。**那按钮文案就成了假话。**
 * （路由离开时组件会卸载，这里多跑一次也无害。）
 */
function discardChanges() {
  if (!loadedSnapshot.value) return
  try {
    const s = JSON.parse(loadedSnapshot.value)
    properties.value = s.properties || []
    events.value = s.events || []
    services.value = s.services || []
  } catch {
    // 快照解析失败就不动数据 —— 宁可留着改动，也别把表单清空
  }
}

// 产品下设备
const devices = ref([])
const devicesLoading = ref(false)
const devicesTotal = ref(0)
const devicesPage = ref(1)
const devicesPageSize = 10

// 服务参数弹窗
const paramsSnapshot = ref(null)

// 枚举项弹窗

const nodeTypeText = computed(() => {
  const m = { 1: '直连设备', 2: '网关设备', 3: '子设备' }
  return m[product.value?.node_type] || '直连设备'
})

async function load() {
  loading.value = true
  try {
    const res = await listProducts()
    const items = res.data.items || []
    product.value = items.find((p) => p.product_key === key.value) || null
    if (product.value) loadTsl(product.value.product_key)
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function loadTsl(productKey) {
  try {
    const res = await getProductTsl(productKey)
    const t = res.data || {}
    const sections = normalizeTsl(t)
    properties.value = sections.properties
    events.value = sections.events
    services.value = sections.services
  } catch (e) {
    properties.value = []
    events.value = []
    services.value = []
  } finally {
    // 7.1：无论成功还是失败都要重取基线 —— 放在 finally 里，
    // 否则加载失败（三个数组被清空）会被判成"有未保存改动"，一进页面就弹提示
    loadedSnapshot.value = currentSnapshot()
  }
}


async function save() {
  const { errors, tsl: t } = buildTsl({ properties: properties.value, events: events.value, services: services.value })
  if (errors.length) {
    ElMessage.error(errors[0])
    return
  }
  saving.value = true
  try {
    await updateProductTsl(key.value, t)
    ElMessage.success('物模型已保存，控制台界面将按新 TSL 自动更新')
    await loadTsl(key.value)
  } catch (e) {
    // 校验错误信息由后端返回、拦截器已提示
  } finally {
    saving.value = false
  }
}

async function loadDevices() {
  devicesLoading.value = true
  try {
    const res = await listDevices(devicesPage.value, devicesPageSize, '', key.value, 0)
    devices.value = res.data.items || []
    devicesTotal.value = res.data.total || 0
  } catch (e) {
    devices.value = []
  } finally {
    devicesLoading.value = false
  }
}

function handleDevicesPage(p) {
  devicesPage.value = p
  loadDevices()
}

// 切换 tab：从「物模型」离开且有未保存改动 → 先确认（用户取消就把 tab 拨回去）
let revertTab = false
watch(activeTab, async (tab, prev) => {
  if (revertTab) {
    revertTab = false
    return
  }
  // 7.1：只有「物模型」tab 是可编辑的，所以只拦"从它离开"
  if (prev === 'tsl' && tab !== 'tsl' && dirty.value) {
    if (!(await confirmLeave())) {
      revertTab = true
      activeTab.value = 'tsl'
      return
    }
    discardChanges() // 用户选了「放弃修改」，就真的丢掉（否则切回来还能看到"已放弃"的改动）
  }
  if (tab === 'devices') {
    devicesPage.value = 1
    loadDevices()
  }
})

// 7.1：离开路由（含「返回」按钮）也要拦
onBeforeRouteLeave(async () => {
  if (!dirty.value) return true
  return await confirmLeave()
})

// 7.1：刷新 / 关标签页。浏览器只允许弹它自己的通用提示，不能自定义文案 —— 有拦总比没有强
function onBeforeUnload(e) {
  if (!dirty.value) return
  e.preventDefault()
  e.returnValue = ''
}

onMounted(() => {
  load()
  window.addEventListener('beforeunload', onBeforeUnload)
})

onBeforeUnmount(() => window.removeEventListener('beforeunload', onBeforeUnload))
</script>

<style scoped>
.product-detail {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 24px;
  overflow-y: auto;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.product-name {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.panel {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: var(--radius-md, 10px);
  padding: 16px;
  margin-bottom: 12px;
}

.sec-title {
  margin: 16px 0 8px;
  font-size: 13px;
  color: #4b5563;
}

.add-btn {
  margin-top: 8px;
  width: 100%;
}

.tsl-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

/* 7.1：保存区 —— 「有未保存的改动」标签与保存按钮并排 */
.tsl-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 7.2：标题 + 分段器放左边，操作按钮放右边 */
.tsl-head-left {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.sec-switch :deep(.el-radio-button__inner) {
  padding: 5px 12px;
}

/* 7.4：导入弹窗 */
.import-hint {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.7;
  margin-bottom: 10px;
}

.import-file {
  margin-top: 10px;
  font-size: 12px;
  color: #6b7280;
}

.tsl-title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
}

.hint {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}

.dev-pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 999px;
}

.status-tag .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.status-tag.on {
  background: #ecfdf5;
  color: #047857;
}

.status-tag.on .dot {
  background: #10b981;
}

.status-tag.off {
  background: #f3f4f6;
  color: #6b7280;
}

.status-tag.off .dot {
  background: #d1d5db;
}

.params-block {
  margin-bottom: 16px;
}

.params-label {
  font-size: 13px;
  color: #4b5563;
  margin-bottom: 8px;
  font-weight: 600;
}

@media (max-width: 900px) {
  .product-detail {
    padding: 12px;
  }
}
</style>
