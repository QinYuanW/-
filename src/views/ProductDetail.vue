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
            <div class="tsl-title">物模型 · {{ product?.name || key }}</div>
            <el-button type="primary" size="small" :loading="saving" @click="save">保存物模型</el-button>
          </div>

          <h4 class="sec-title">属性 properties</h4>
          <el-table :data="properties" size="small">
            <el-table-column label="key" min-width="120">
              <template #default="{ row }"><el-input v-model="row.key" size="small" placeholder="temperature" /></template>
            </el-table-column>
            <el-table-column label="名称" min-width="100">
              <template #default="{ row }"><el-input v-model="row.name" size="small" placeholder="温度" /></template>
            </el-table-column>
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                <el-select v-model="row.type" size="small">
                  <el-option v-for="t in types" :key="t" :value="t" :label="t" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="单位" width="80">
              <template #default="{ row }"><el-input v-model="row.unit" size="small" placeholder="℃" /></template>
            </el-table-column>
            <el-table-column label="读写" width="120">
              <template #default="{ row }">
                <el-select v-model="row.access" size="small">
                  <el-option value="readonly" label="readonly 只读" />
                  <el-option value="readwrite" label="readwrite 可写" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="min" width="80">
              <template #default="{ row }">
                <el-input v-model="row.min" size="small" placeholder="0" @input="normalizeNum(row, 'min')" />
              </template>
            </el-table-column>
            <el-table-column label="max" width="80">
              <template #default="{ row }">
                <el-input v-model="row.max" size="small" placeholder="100" @input="normalizeNum(row, 'max')" />
              </template>
            </el-table-column>
            <el-table-column label="step" width="70">
              <template #default="{ row }">
                <el-input
                  v-if="row.type === 'int' || row.type === 'float'"
                  v-model="row.step"
                  size="small"
                  placeholder="1"
                  @input="normalizeNum(row, 'step')"
                />
              </template>
            </el-table-column>
            <el-table-column label="枚举" width="70">
              <template #default="{ row }">
                <el-button v-if="row.type === 'enum'" size="small" text type="primary" @click="openElements(row)">配置</el-button>
              </template>
            </el-table-column>
            <el-table-column label="" width="60">
              <template #default="{ $index }">
                <el-button size="small" text type="danger" @click="properties.splice($index, 1)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button size="small" class="add-btn" @click="addProperty">+ 加一个属性</el-button>

          <h4 class="sec-title">事件 events</h4>
          <el-table :data="events" size="small">
            <el-table-column label="key" min-width="150">
              <template #default="{ row }"><el-input v-model="row.key" size="small" placeholder="high_temp_alarm" /></template>
            </el-table-column>
            <el-table-column label="名称" min-width="120">
              <template #default="{ row }"><el-input v-model="row.name" size="small" placeholder="高温告警" /></template>
            </el-table-column>
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <el-select v-model="row.type" size="small">
                  <el-option value="info" label="信息" />
                  <el-option value="alert" label="告警" />
                  <el-option value="fault" label="故障" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="" width="60">
              <template #default="{ $index }">
                <el-button size="small" text type="danger" @click="events.splice($index, 1)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无事件" :image-size="50" /></template>
          </el-table>
          <el-button size="small" class="add-btn" @click="addEvent">+ 加一个事件</el-button>

          <h4 class="sec-title">服务 services</h4>
          <el-table :data="services" size="small">
            <el-table-column label="key" min-width="150">
              <template #default="{ row }"><el-input v-model="row.key" size="small" placeholder="reboot" /></template>
            </el-table-column>
            <el-table-column label="名称" min-width="120">
              <template #default="{ row }"><el-input v-model="row.name" size="small" placeholder="重启设备" /></template>
            </el-table-column>
            <el-table-column label="调用方式" width="110">
              <template #default="{ row }">
                <el-select v-model="row.callType" size="small">
                  <el-option value="async" label="异步" />
                  <el-option value="sync" label="同步" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="参数" width="90">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="openParams(row)">配置</el-button>
              </template>
            </el-table-column>
            <el-table-column label="" width="60">
              <template #default="{ $index }">
                <el-button size="small" text type="danger" @click="services.splice($index, 1)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无服务" :image-size="50" /></template>
          </el-table>
          <el-button size="small" class="add-btn" @click="addService">+ 加一个服务</el-button>

          <p class="hint">
            保存时校验：key 全局唯一且仅字母/数字/下划线；属性 type 仅 int/float/bool/string/enum（enum 必须配置枚举项 elements）、access 仅 readonly/readwrite。
          </p>
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

    <!-- 服务参数配置 -->
    <el-dialog v-model="paramsVisible" :title="`服务参数 · ${paramsService?.name || ''}`" width="640px">
      <div class="params-block">
        <p class="params-label">输入参数 inputData</p>
        <el-table :data="paramsInput" size="small">
          <el-table-column label="key" min-width="120">
            <template #default="{ row }"><el-input v-model="row.key" size="small" /></template>
          </el-table-column>
          <el-table-column label="名称" min-width="110">
            <template #default="{ row }"><el-input v-model="row.name" size="small" /></template>
          </el-table-column>
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <el-select v-model="row.type" size="small">
                <el-option v-for="t in types" :key="t" :value="t" :label="t" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="" width="60">
            <template #default="{ $index }">
              <el-button size="small" text type="danger" @click="paramsInput.splice($index, 1)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" @click="paramsInput.push({ key: '', name: '', type: 'string' })">+ 参数</el-button>
      </div>
      <div class="params-block">
        <p class="params-label">输出参数 outputData</p>
        <el-table :data="paramsOutput" size="small">
          <el-table-column label="key" min-width="120">
            <template #default="{ row }"><el-input v-model="row.key" size="small" /></template>
          </el-table-column>
          <el-table-column label="名称" min-width="110">
            <template #default="{ row }"><el-input v-model="row.name" size="small" /></template>
          </el-table-column>
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <el-select v-model="row.type" size="small">
                <el-option v-for="t in types" :key="t" :value="t" :label="t" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="" width="60">
            <template #default="{ $index }">
              <el-button size="small" text type="danger" @click="paramsOutput.splice($index, 1)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" @click="paramsOutput.push({ key: '', name: '', type: 'string' })">+ 参数</el-button>
      </div>
      <template #footer>
        <el-button @click="paramsVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmParams">确定</el-button>
      </template>
    </el-dialog>

    <!-- 枚举项配置 -->
    <el-dialog v-model="elementsVisible" :title="`枚举项 · ${elementsProp?.name || ''}`" width="480px">
      <p class="params-label">枚举项 elements（value 为上报/下发值，key 为展示文本）</p>
      <el-table :data="elementsList" size="small">
        <el-table-column label="value" width="140">
          <template #default="{ row }"><el-input v-model="row.value" size="small" placeholder="1" /></template>
        </el-table-column>
        <el-table-column label="key（展示文本）" min-width="140">
          <template #default="{ row }"><el-input v-model="row.key" size="small" placeholder="开启" /></template>
        </el-table-column>
        <el-table-column label="" width="60">
          <template #default="{ $index }">
            <el-button size="small" text type="danger" @click="elementsList.splice($index, 1)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无枚举项" :image-size="50" /></template>
      </el-table>
      <el-button size="small" class="add-btn" @click="elementsList.push({ value: '', key: '' })">+ 枚举项</el-button>
      <template #footer>
        <el-button @click="elementsVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmElements">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Back } from '@element-plus/icons-vue'
import { listProducts, getProductTsl, updateProductTsl, listDevices } from '../api'
import { formatTime } from '../utils/export'

const route = useRoute()
const key = computed(() => route.params.key)

const types = ['int', 'float', 'bool', 'string', 'enum']

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
const tsl = computed(() => ({
  properties: properties.value,
  events: events.value,
  services: services.value
}))

// 产品下设备
const devices = ref([])
const devicesLoading = ref(false)
const devicesTotal = ref(0)
const devicesPage = ref(1)
const devicesPageSize = 10

// 服务参数弹窗
const paramsVisible = ref(false)
const paramsService = ref(null)
const paramsInput = ref([])
const paramsOutput = ref([])
const paramsSnapshot = ref(null)

// 枚举项弹窗
const elementsVisible = ref(false)
const elementsProp = ref(null)
const elementsList = ref([])

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
    properties.value = (t.properties || []).map((p) => ({
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
    events.value = (t.events || []).map((e) => ({
      key: e.key || '',
      name: e.name || '',
      type: e.type || 'info'
    }))
    services.value = (t.services || []).map((s) => ({
      key: s.key || '',
      name: s.name || '',
      callType: s.callType || 'async',
      inputData: (s.inputData || []).map((p) => ({ ...p })),
      outputData: (s.outputData || []).map((p) => ({ ...p }))
    }))
  } catch (e) {
    properties.value = []
    events.value = []
    services.value = []
  }
}

function normalizeNum(row, field) {
  const v = row[field]
  if (v === '' || v == null) return
  const n = Number(v)
  if (Number.isNaN(n)) row[field] = ''
  else row[field] = n
}

function addProperty() {
  properties.value.push({ key: '', name: '', type: 'float', unit: '', access: 'readonly', min: '', max: '', step: '', elements: [] })
}

function addEvent() {
  events.value.push({ key: '', name: '', type: 'info' })
}

function addService() {
  services.value.push({ key: '', name: '', callType: 'async', inputData: [], outputData: [] })
}

function openParams(row) {
  paramsService.value = row
  paramsInput.value = row.inputData.map((p) => ({ ...p }))
  paramsOutput.value = row.outputData.map((p) => ({ ...p }))
  paramsSnapshot.value = row
  paramsVisible.value = true
}

function confirmParams() {
  paramsService.value.inputData = paramsInput.value
    .filter((p) => String(p.key || '').trim())
    .map((p) => ({ ...p }))
  paramsService.value.outputData = paramsOutput.value
    .filter((p) => String(p.key || '').trim())
    .map((p) => ({ ...p }))
  paramsVisible.value = false
}

function openElements(row) {
  elementsProp.value = row
  elementsList.value = (row.elements || []).map((e) => ({ value: e.value ?? '', key: e.key ?? '' }))
  elementsVisible.value = true
}

function confirmElements() {
  elementsProp.value.elements = elementsList.value
    .filter((e) => e.value !== '' && e.value != null && String(e.key || '').trim())
    .map((e) => {
      const v = String(e.value).trim()
      return { value: /^-?\d+(\.\d+)?$/.test(v) ? Number(v) : v, key: String(e.key).trim() }
    })
  elementsVisible.value = false
}

function buildTsl() {
  const sections = { properties: properties.value, events: events.value, services: services.value }
  const seen = new Set()
  const errors = []

  for (const [section, list] of Object.entries(sections)) {
    list.forEach((item, i) => {
      const at = `${section}[${i}]`
      const k = String(item.key || '').trim()
      if (!k) return errors.push(`${at}.key 不能为空`)
      if (!/^[A-Za-z0-9_]{1,64}$/.test(k)) {
        errors.push(`${at}.key 仅允许字母/数字/下划线（≤64）: ${k}`)
      }
      if (seen.has(k)) errors.push(`${at}.key 重复: ${k}`)
      seen.add(k)
      if (!String(item.name || '').trim()) errors.push(`${at}.name 不能为空`)
      if (section === 'properties') {
        if (!types.includes(item.type)) errors.push(`${at}.type 仅允许 int/float/bool/string/enum`)
        if (!['readonly', 'readwrite'].includes(item.access)) errors.push(`${at}.access 仅允许 readonly/readwrite`)
        if (item.type === 'enum') {
          const elems = Array.isArray(item.elements) ? item.elements : []
          if (!elems.length) errors.push(`${at}.type=enum 时必须配置 elements 枚举项`)
          elems.forEach((e, j) => {
            if (e.value === '' || e.value == null || !String(e.key || '').trim()) {
              errors.push(`${at}.elements[${j}] 每项需含 value 与 key（key 为展示文本）`)
            }
          })
        }
      }
    })
  }

  const clean = (list) =>
    list
      .filter((item) => String(item.key || '').trim())
      .map((item) => {
        const out = { key: String(item.key).trim(), name: String(item.name).trim() }
        if (item.type) out.type = item.type
        if (item.unit !== undefined && item.unit !== '') out.unit = item.unit
        if (item.access) out.access = item.access
        if (item.min !== '' && item.min != null) out.min = Number(item.min)
        if (item.max !== '' && item.max != null) out.max = Number(item.max)
        if (item.step !== '' && item.step != null) out.step = Number(item.step)
        if (item.type === 'enum' && Array.isArray(item.elements) && item.elements.length) {
          out.elements = item.elements
            .filter((e) => e.value !== '' && e.value != null && String(e.key || '').trim())
            .map((e) => {
              const v = String(e.value).trim()
              return { value: /^-?\d+(\.\d+)?$/.test(v) ? Number(v) : v, key: String(e.key).trim() }
            })
        }
        return out
      })

  return {
    errors,
    tsl: {
      properties: clean(properties.value),
      events: events.value
        .filter((e) => String(e.key || '').trim())
        .map((e) => ({ key: String(e.key).trim(), name: String(e.name).trim(), type: e.type || 'info' })),
      services: services.value
        .filter((s) => String(s.key || '').trim())
        .map((s) => ({
          key: String(s.key).trim(),
          name: String(s.name).trim(),
          callType: s.callType || 'async',
          inputData: (s.inputData || []).filter((p) => String(p.key || '').trim()),
          outputData: (s.outputData || []).filter((p) => String(p.key || '').trim())
        }))
    }
  }
}

async function save() {
  const { errors, tsl: t } = buildTsl()
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

watch(activeTab, (tab) => {
  if (tab === 'devices') {
    devicesPage.value = 1
    loadDevices()
  }
})

onMounted(load)
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
