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
            <div class="payload-editor">
              <div v-for="(row, i) in form.payloadRows" :key="i" class="payload-row">
                <el-select
                  v-model="row.key"
                  class="pr-prop"
                  placeholder="选择可写属性"
                  @change="(k) => onPayloadKeyChange(row, k)"
                >
                  <el-option
                    v-for="p in writableProps"
                    :key="p.key"
                    :label="`${p.name || p.key}（${p.key}）`"
                    :value="p.key"
                    :disabled="dupKeys.has(p.key) && p.key !== row.key"
                  />
                </el-select>

                <!-- bool 用下拉「开 / 关」而不是开关：这里选的是**本次下发的目标值**（常量），
                     一个灰色的 off 开关既像"关"、又像"未设置"、还像"不动它" —— 有歧义 -->
                <PropertyValueInput
                  v-if="propOf(row.key)"
                  :prop="propOf(row.key)"
                  :model-value="row.value"
                  width="150px"
                  bool-style="select"
                  @update:model-value="(v) => (row.value = v)"
                />
                <span v-else class="pr-empty">先选属性</span>

                <!-- 只有一行时不显示「删除」：删了就没有下发内容了，这个操作没有意义。
                     不用 :disabled —— Element Plus 对禁用的 link 按钮只做"淡化"，
                     颜色还在（淡粉/淡蓝），看着仍像能点，反而让人以为是坏的。 -->
                <el-button
                  v-if="form.payloadRows.length > 1"
                  link
                  size="small"
                  type="danger"
                  @click="removePayloadRow(i)"
                >
                  删除
                </el-button>

                <span v-else class="pr-spacer" />
              </div>

              <div class="payload-foot">
                <!-- 可写属性已用尽时，把按钮换成"为什么没有更多可选"的说明，而不是留个点不动的按钮 -->
                <el-button
                  v-if="canAddPayloadRow"
                  link
                  type="primary"
                  :icon="Plus"
                  @click="addPayloadRow"
                >
                  添加属性
                </el-button>
                <span v-else-if="writableProps.length" class="payload-note">
                  该产品的可写属性已全部用上（共 {{ writableProps.length }} 个：
                  {{ writableNames }}）。要控制别的属性，先去「产品 → 物模型」把它设为可写。
                </span>
                <span class="payload-tip">规则命中时一次可下发多个属性；设备离线时挂起、上线补发</span>
              </div>

              <div v-if="!writableProps.length" class="payload-warn">
                该产品物模型里没有「可写（readwrite）」属性，无法配置联动下发。
                请先到「产品 → 物模型」把要控制的属性设为可写。
              </div>
              <div v-else class="payload-preview">
                生成载荷 <code>{{ payloadPreview }}</code>
              </div>
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
import { Search, Refresh, Plus } from '@element-plus/icons-vue'
import PropertyValueInput from '../components/PropertyValueInput.vue'
import { writableProperties, defaultValueOf } from '../utils/tsl'
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
    // 下发内容 = 物模型「可写属性 → 值」的多行编辑，不再让用户手写 JSON
    payloadRows: [{ key: '', value: undefined }],
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

/**
 * 联动下发可选属性 = 该产品物模型里的「可写（readwrite）」属性。
 *
 * 为什么必须由物模型驱动、而不是让用户手写 JSON：
 * 下行报文的 `payload` 本来就是「属性名 → 值」的扁平对象
 * （固件 `device_property_handle(key, value_js)` 按 key 逐个处理，见 firmware/esp32_iot/main/device_dm.c），
 * 而合法属性名 / 类型 / 取值范围**早就写在 TSL 里了**。让人手写，等于把已经签好的合同再口述一遍 ——
 * 打错 key 或写错类型，规则会静默地永远不生效。
 */
const writableProps = computed(() => writableProperties(currentProduct.value?.tsl))

const propOf = (key) => writableProps.value.find((p) => p.key === key)

/** 本表单已用过的属性 key（同一 payload 重复写同一属性无意义，直接禁选） */
const dupKeys = computed(
  () => new Set(form.value.payloadRows.map((r) => r.key).filter(Boolean))
)

const canAddPayloadRow = computed(
  () => form.value.payloadRows.length < writableProps.value.length
)

/** 可写属性名列表（用于"已全部用上"的说明文案） */
const writableNames = computed(() =>
  writableProps.value.map((p) => p.name || p.key).join('、')
)

/** 一行是否已填好（⚠️ bool 的 `false` 是有效值，不能按"假值"丢掉） */
function rowFilled(r) {
  if (!r.key) return false
  if (r.value === undefined || r.value === null) return false
  return !(typeof r.value === 'string' && r.value.trim() === '')
}

/** 最终写进 action_payload 的对象 */
const payloadObject = computed(() => {
  const o = {}
  form.value.payloadRows.filter(rowFilled).forEach((r) => { o[r.key] = r.value })
  return o
})

const payloadPreview = computed(() => JSON.stringify(payloadObject.value))

/**
 * 切属性时把值重置为该属性的类型默认。
 * 不重置的话会带着上一个属性的值 —— 比如把 temperature 的 26.5 填进 bool 的开关。
 */
function onPayloadKeyChange(row, key) {
  row.value = defaultValueOf(propOf(key))
}

function addPayloadRow() {
  form.value.payloadRows.push({ key: '', value: undefined })
}

function removePayloadRow(i) {
  form.value.payloadRows.splice(i, 1)
  if (!form.value.payloadRows.length) addPayloadRow()
}

function levelText(l) {
  return { 1: '严重', 2: '一般', 3: '提示' }[l] || '一般'
}

function levelType(l) {
  return { 1: 'danger', 2: 'warning', 3: 'info' }[l] || 'info'
}

/** 把库里的 action_payload（JSON 字符串）还原成编辑行 */
function payloadRowsFrom(json) {
  const rows = []
  if (json) {
    try {
      const o = JSON.parse(json)
      if (o && typeof o === 'object' && !Array.isArray(o)) {
        Object.keys(o).forEach((k) => rows.push({ key: k, value: o[k] }))
      }
    } catch {
      // 历史脏数据（非 JSON 对象）：忽略并落回空行，不阻断编辑
    }
  }
  return rows.length ? rows : [{ key: '', value: undefined }]
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

/** 切产品：属性下拉与下发内容都要跟着换（旧选择必须清掉，否则残留别的产品的属性 key） */
async function onProductChange() {
  form.value.propertyKey = ''
  form.value.deviceKey = ''
  form.value.payloadRows = [{ key: '', value: undefined }]
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
    payloadRows: payloadRowsFrom(row.action_payload),
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
    action_payload: form.value.actionType === 1 ? JSON.stringify(payloadObject.value) : null,
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
    const rows = form.value.payloadRows.filter(rowFilled)
    if (!rows.length) return '选择「告警并下发指令」时，至少要配一个要下发的属性'
    const keys = form.value.payloadRows.filter((r) => r.key).map((r) => r.key)
    if (new Set(keys).size !== keys.length) return '同一个属性不能重复配置'
    const missing = form.value.payloadRows.find((r) => r.key && !rowFilled(r))
    if (missing) return `属性「${missing.key}」还没填值`
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

/* ─── 联动下发内容：TSL 驱动的属性行编辑 ─── */
.payload-editor {
  width: 100%;
}

.payload-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.pr-prop {
  flex: 1;
  min-width: 0;
}

.pr-empty {
  width: 150px;
  font-size: 12px;
  color: #b0b8c4;
}

.payload-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

/* 只有一行时占住「删除」的位置，避免右侧元素左右横跳 */
.pr-spacer {
  display: inline-block;
  width: 28px;
}

/* "可写属性已全部用上"的说明：替代一个点不动的按钮 */
.payload-note {
  font-size: 12px;
  line-height: 1.7;
  color: #854f0b;
}

.payload-tip {
  font-size: 11px;
  color: #b0b8c4;
}

/* 物模型没有可写属性时的硬提示：这不是"用户没填"，而是配置前提不成立 */
.payload-warn {
  margin-top: 8px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.6;
  color: #854f0b;
  background: #faeeda;
  border-radius: 6px;
}

.payload-preview {
  margin-top: 8px;
  font-size: 12px;
  color: #6b7280;
}

.payload-preview code {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: #1f2937;
  background: #f1f5f9;
  padding: 2px 6px;
  border-radius: 4px;
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
