<template>
  <div class="rules">
    <div class="rules-head">
      <div>
        <div class="rules-title">告警规则</div>
        <div class="rules-sub">
          阈值规则：设备上报后由规则引擎实时评估，命中即生成告警，可选自动下发指令
        </div>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="loadAll">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建规则</el-button>
      </div>
    </div>

    <div class="stat-row">
      <div class="stat-box">
        <div class="stat-label">规则总数</div>
        <div class="stat-num">{{ total }}</div>
      </div>
      <div class="stat-box">
        <div class="stat-label">启用中</div>
        <div class="stat-num on">{{ enabledCount }}</div>
      </div>
      <div class="stat-box">
        <div class="stat-label">已停用</div>
        <div class="stat-num off">{{ disabledCount }}</div>
      </div>
    </div>

    <div class="filter-row">
      <el-select
        v-model="filters.productKey"
        class="f-product"
        placeholder="全部产品"
        clearable
        @change="onFilterChange"
      >
        <el-option
          v-for="p in products"
          :key="p.product_key"
          :label="`${p.name}（${p.product_key}）`"
          :value="p.product_key"
        />
      </el-select>
      <el-select v-model="filters.enabled" class="f-mode" placeholder="全部状态" clearable @change="onFilterChange">
        <el-option label="启用中" :value="1" />
        <el-option label="已停用" :value="0" />
      </el-select>
      <el-input
        v-model="filters.keyword"
        class="f-search"
        placeholder="搜索规则名称"
        clearable
        :prefix-icon="Search"
        @keyup.enter="onFilterChange"
        @clear="onFilterChange"
      />
    </div>

    <el-table v-loading="loading" :data="rules" class="rule-table">
      <el-table-column label="规则名称" min-width="190" show-overflow-tooltip>
        <template #default="{ row }">
          <div class="r-name">{{ row.name }}</div>
          <div class="r-sub">{{ row.product_key }}</div>
        </template>
      </el-table-column>

      <el-table-column label="监测条件" min-width="170">
        <template #default="{ row }">
          <span class="cond">{{ row.property_key }}</span>
          <span class="cond-op">{{ row.operator }}</span>
          <span class="cond">{{ row.threshold }}</span>
        </template>
      </el-table-column>

      <el-table-column label="作用范围" width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.device_key" class="mono">{{ row.device_key }}</span>
          <span v-else class="scope-all">整个产品</span>
        </template>
      </el-table-column>

      <el-table-column label="级别" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="levelType(row.level)" effect="plain">
            {{ levelText(row.level) }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column label="命中动作" min-width="170">
        <template #default="{ row }">
          <span v-if="row.action_type === 1" class="act-on">告警 + 下发指令</span>
          <span v-else class="act-off">仅告警</span>
          <div v-if="row.action_type === 1" class="r-sub mono">{{ row.action_payload }}</div>
        </template>
      </el-table-column>

      <el-table-column label="冷却" width="80">
        <template #default="{ row }">{{ row.cooldown_sec }}s</template>
      </el-table-column>

      <el-table-column label="累计触发" width="100">
        <template #default="{ row }">
          <span :class="row.alarm_count > 0 ? 'cnt-hit' : 'cnt-zero'">{{ row.alarm_count }}</span>
        </template>
      </el-table-column>

      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled === 1"
            :loading="togglingId === row.id"
            @change="(val) => toggleStatus(row, val)"
          />
        </template>
      </el-table-column>

      <el-table-column label="操作" width="120" align="right">
        <template #default="{ row }">
          <el-button link size="small" @click="openEdit(row)">编辑</el-button>
          <el-button link size="small" type="danger" @click="removeRule(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty
      v-if="!loading && !rules.length"
      description="还没有告警规则，点右上角新建一条"
      :image-size="80"
    />

    <div v-if="total > 0" class="pager">
      <el-pagination
        layout="total, prev, pager, next"
        :total="total"
        :current-page="page"
        :page-size="size"
        @current-change="onPageChange"
      />
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑告警规则' : '新建告警规则'" width="620px">
      <el-form :model="form" label-width="96px">
        <el-form-item label="规则名称" required>
          <el-input v-model="form.name" maxlength="64" placeholder="如：高温自动关断" />
        </el-form-item>

        <el-form-item label="所属产品" required>
          <el-select v-model="form.productKey" class="w-full" placeholder="选择产品" @change="onProductChange">
            <el-option
              v-for="p in products"
              :key="p.product_key"
              :label="`${p.name}（${p.product_key}）`"
              :value="p.product_key"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="作用范围">
          <el-radio-group v-model="form.scope" @change="onScopeChange">
            <el-radio value="product">整个产品（该产品下所有设备）</el-radio>
            <el-radio value="device">指定设备</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="form.scope === 'device'" label="目标设备">
          <el-select v-model="form.deviceKey" class="w-full" placeholder="选择设备" filterable>
            <el-option
              v-for="d in deviceOptions"
              :key="d.device_key"
              :label="`${d.device_name || d.device_key}（${d.device_key}）`"
              :value="d.device_key"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="监测条件" required>
          <div class="cond-builder">
            <el-select v-model="form.propertyKey" class="cb-prop" placeholder="属性">
              <el-option
                v-for="pr in propertyOptions"
                :key="pr.key"
                :label="`${pr.name || pr.key}（${pr.key}${pr.unit ? ' / ' + pr.unit : ''}）`"
                :value="pr.key"
              />
            </el-select>
            <el-select v-model="form.operator" class="cb-op">
              <el-option v-for="op in OPERATORS" :key="op" :label="op" :value="op" />
            </el-select>
            <el-input-number v-model="form.threshold" class="cb-val" :precision="2" :step="1" controls-position="right" />
          </div>
        </el-form-item>

        <el-form-item label="告警级别">
          <el-select v-model="form.level" class="w-full">
            <el-option label="1 严重" :value="1" />
            <el-option label="2 一般" :value="2" />
            <el-option label="3 提示" :value="3" />
          </el-select>
        </el-form-item>

        <el-form-item label="告警类型">
          <el-input v-model="form.alarmType" maxlength="32" placeholder="规则告警" />
        </el-form-item>

        <el-form-item label="命中动作">
          <el-radio-group v-model="form.actionType">
            <el-radio :value="0">仅告警</el-radio>
            <el-radio :value="1">告警并下发指令</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="form.actionType === 1">
          <el-form-item label="下发内容" required>
            <el-input
              v-model="form.actionPayload"
              type="textarea"
              :rows="3"
              placeholder='JSON 对象，如 {"switch": false}'
            />
            <div class="payload-tip">
              <span>设备离线时自动挂起，上线后补发（期望状态语义）。快捷填充：</span>
              <el-button v-for="ps in payloadPresets" :key="ps.label" link size="small" @click="applyPreset(ps)">
                {{ ps.label }}
              </el-button>
            </div>
          </el-form-item>
        </template>

        <el-form-item label="冷却窗口">
          <el-input-number v-model="form.cooldownSec" :min="0" :max="86400" controls-position="right" />
          <span class="unit-tip">秒（同规则同设备在该窗口内只告警一次，防刷屏）</span>
        </el-form-item>

        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>

        <div class="preview">
          预览：设备上报 <b>{{ form.propertyKey || '属性' }}</b> 满足
          <b>{{ form.operator }} {{ form.threshold }}</b> 时 →
          {{ form.actionType === 1 ? '生成告警并下发指令' : '生成告警' }}
          <span v-if="form.cooldownSec > 0">（{{ form.cooldownSec }} 秒内不重复）</span>
        </div>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import {
  listRules,
  createRule,
  updateRule,
  updateRuleStatus,
  deleteRule,
  listProducts,
  listDevices
} from '../api'

const OPERATORS = ['>', '>=', '<', '<=', '==', '!=']

const rules = ref([])
const products = ref([])
const deviceOptions = ref([])
const total = ref(0)
const enabledCount = ref(0)
const disabledCount = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const togglingId = ref(null)

const filters = ref({ productKey: '', keyword: '', enabled: null })

const dialogVisible = ref(false)
const saving = ref(false)
const form = ref(blankForm())

function blankForm() {
  return {
    id: null,
    name: '',
    productKey: '',
    scope: 'product',
    deviceKey: '',
    propertyKey: '',
    operator: '>',
    threshold: 0,
    level: 2,
    alarmType: '规则告警',
    actionType: 0,
    actionPayload: '',
    cooldownSec: 60,
    enabled: 1
  }
}

/** 当前所选产品在列表里的物模型对象 */
const currentProduct = computed(() =>
  products.value.find((p) => p.product_key === form.value.productKey)
)

/** 监测属性下拉：来自该产品的物模型（避免手打 property_key 打错而规则永不命中） */
const propertyOptions = computed(() => currentProduct.value?.tsl?.properties || [])

/** 联动内容快捷填充：用该产品的「可写 bool 属性」生成，如 switch → {"switch": true/false} */
const payloadPresets = computed(() => {
  const out = []
  for (const p of propertyOptions.value) {
    if (p.access === 'readwrite' && p.type === 'bool') {
      out.push({ label: `${p.name || p.key}=false`, payload: JSON.stringify({ [p.key]: false }) })
      out.push({ label: `${p.name || p.key}=true`, payload: JSON.stringify({ [p.key]: true }) })
    }
  }
  return out
})

function levelText(l) {
  return { 1: '严重', 2: '一般', 3: '提示' }[l] || '一般'
}

function levelType(l) {
  return { 1: 'danger', 2: 'warning', 3: 'info' }[l] || 'info'
}

function applyPreset(ps) {
  form.value.actionPayload = ps.payload
}

// ───────────────────────── 数据加载 ─────────────────────────

async function loadProducts() {
  try {
    const res = await listProducts()
    products.value = res.data?.items || []
  } catch {
    products.value = []
  }
}

/**
 * 列表 + 两个计数。
 * 计数单独查（`size=1` 只要 total）而**不是**从当前页统计 ——
 * 否则会拿"本页 20 条里有几条启用"冒充"全量启用数"，界面上就是在说谎。
 */
async function loadAll() {
  loading.value = true
  try {
    const [main, on, off] = await Promise.all([
      listRules(page.value, size.value, filters.value.productKey, filters.value.keyword, filters.value.enabled),
      listRules(1, 1, filters.value.productKey, filters.value.keyword, 1),
      listRules(1, 1, filters.value.productKey, filters.value.keyword, 0)
    ])
    rules.value = main.data?.items || []
    total.value = main.data?.total || 0
    enabledCount.value = on.data?.total || 0
    disabledCount.value = off.data?.total || 0
  } catch {
    rules.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  page.value = 1
  loadAll()
}

function onPageChange(p) {
  page.value = p
  loadAll()
}

// ───────────────────────── 表单 ─────────────────────────

/** 切产品：属性下拉要跟着换，旧的选择必须清掉（否则残留别的产品的 key） */
async function onProductChange() {
  form.value.propertyKey = ''
  form.value.deviceKey = ''
  form.value.actionPayload = ''
  deviceOptions.value = []
  if (form.value.scope === 'device') await loadDevices()
}

async function onScopeChange(val) {
  form.value.deviceKey = ''
  if (val === 'device') await loadDevices()
}

async function loadDevices() {
  if (!form.value.productKey) {
    deviceOptions.value = []
    return
  }
  try {
    const res = await listDevices(1, 200, '', form.value.productKey, 0, null)
    deviceOptions.value = res.data?.items || []
  } catch {
    deviceOptions.value = []
  }
}

function openCreate() {
  form.value = blankForm()
  deviceOptions.value = []
  dialogVisible.value = true
}

function openEdit(row) {
  form.value = {
    id: row.id,
    name: row.name,
    productKey: row.product_key,
    scope: row.device_key ? 'device' : 'product',
    deviceKey: row.device_key || '',
    propertyKey: row.property_key,
    operator: row.operator,
    threshold: Number(row.threshold),
    level: row.level,
    alarmType: row.alarm_type,
    actionType: row.action_type,
    actionPayload: row.action_payload || '',
    cooldownSec: row.cooldown_sec,
    enabled: row.enabled
  }
  deviceOptions.value = []
  if (form.value.scope === 'device') loadDevices()
  dialogVisible.value = true
}

function buildPayload() {
  return {
    name: form.value.name.trim(),
    product_key: form.value.productKey,
    device_key: form.value.scope === 'device' ? form.value.deviceKey : null,
    property_key: form.value.propertyKey,
    operator: form.value.operator,
    threshold: form.value.threshold,
    level: form.value.level,
    alarm_type: form.value.alarmType,
    action_type: form.value.actionType,
    action_payload: form.value.actionType === 1 ? form.value.actionPayload : null,
    cooldown_sec: form.value.cooldownSec,
    enabled: form.value.enabled
  }
}

/** 前端先拦一道明显错误，减少一次往返（后端仍会做完整校验） */
function validate() {
  if (!form.value.name.trim()) return '规则名称不能为空'
  if (!form.value.productKey) return '请选择所属产品'
  if (form.value.scope === 'device' && !form.value.deviceKey) return '选择「指定设备」时必须选一台设备'
  if (!form.value.propertyKey) return '请选择监测属性'
  if (form.value.threshold === null || form.value.threshold === undefined) return '请填写阈值'
  if (form.value.actionType === 1) {
    const txt = (form.value.actionPayload || '').trim()
    if (!txt) return '选择「告警并下发指令」时必须填写下发内容'
    try {
      const parsed = JSON.parse(txt)
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        return '下发内容必须是 JSON 对象，如 {"switch": false}'
      }
    } catch {
      return '下发内容不是合法 JSON'
    }
  }
  return ''
}

async function save() {
  const err = validate()
  if (err) {
    ElMessage.warning(err)
    return
  }
  saving.value = true
  try {
    const payload = buildPayload()
    if (form.value.id) {
      await updateRule(form.value.id, payload)
      ElMessage.success('已保存（规则立即生效）')
    } else {
      await createRule(payload)
      ElMessage.success('已创建（规则立即生效）')
    }
    dialogVisible.value = false
    await loadAll()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row, val) {
  togglingId.value = row.id
  try {
    await updateRuleStatus(row.id, val ? 1 : 0)
    row.enabled = val ? 1 : 0
    ElMessage.success(val ? '已启用' : '已停用')
    await loadAll()
  } catch {
    // 拦截器已提示；开关靠 loadAll 重新拉取回到真实状态
  } finally {
    togglingId.value = null
  }
}

async function removeRule(row) {
  try {
    await ElMessageBox.confirm(
      `删除规则「${row.name}」后不再产生新告警；已产生的 ${row.alarm_count} 条历史告警会保留。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteRule(row.id)
    ElMessage.success('已删除')
    await loadAll()
  } catch {
    // 拦截器已提示
  }
}

onMounted(async () => {
  await loadProducts()
  await loadAll()
})
</script>

<style scoped>
.rules {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 24px;
  overflow-y: auto;
}

.rules-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.rules-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.rules-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #9ca3af;
}

.head-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.stat-box {
  background: #fff;
  border: 1px solid #e4e9f0;
  border-radius: 10px;
  padding: 14px 16px;
}

.stat-label {
  font-size: 12px;
  color: #9ca3af;
}

.stat-num {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 600;
  color: #1f2937;
  line-height: 1;
}

.stat-num.on {
  color: #047857;
}

.stat-num.off {
  color: #9ca3af;
}

.filter-row {
  display: flex;
  gap: 10px;
}

.f-product {
  width: 260px;
}

.f-mode {
  width: 140px;
}

.f-search {
  width: 220px;
}

.rule-table {
  width: 100%;
}

.r-name {
  font-size: 13px;
  font-weight: 500;
  color: #1f2937;
}

.r-sub {
  font-size: 12px;
  color: #9ca3af;
  font-family: Consolas, monospace;
}

.mono {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: #6b7280;
}

.cond {
  font-family: Consolas, monospace;
  font-size: 13px;
  color: #1f2937;
}

.cond-op {
  margin: 0 6px;
  font-family: Consolas, monospace;
  font-size: 13px;
  color: #2563eb;
  font-weight: 600;
}

.scope-all {
  font-size: 12px;
  color: #6b7280;
}

.act-on {
  font-size: 12px;
  color: #993c1d;
}

.act-off {
  font-size: 12px;
  color: #6b7280;
}

.cnt-hit {
  font-size: 13px;
  font-weight: 600;
  color: #a32d2d;
}

/* 累计触发为 0 是"规则可能配错了"的信号，用弱色提示但不要红 */
.cnt-zero {
  font-size: 13px;
  color: #b4b2a9;
}

.pager {
  display: flex;
  justify-content: flex-end;
}

.w-full {
  width: 100%;
}

.cond-builder {
  display: flex;
  gap: 8px;
  width: 100%;
}

.cb-prop {
  flex: 1;
}

.cb-op {
  width: 92px;
}

.cb-val {
  width: 140px;
}

.payload-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.8;
}

.unit-tip {
  margin-left: 8px;
  font-size: 12px;
  color: #9ca3af;
}

.preview {
  padding: 10px 12px;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.7;
  color: #4b5563;
  background: #f5f7fa;
  border-radius: 8px;
}

@media (max-width: 900px) {
  .rules {
    padding: 12px;
  }

  .stat-row {
    grid-template-columns: 1fr;
  }

  .filter-row {
    flex-direction: column;
  }

  .f-product,
  .f-mode,
  .f-search {
    width: 100%;
  }
}
</style>
