<template>
  <div class="tsl-editor">
    <!-- 三类分段：只渲染当前这一类（段上带计数，避免"被藏起来的东西被忘记"） -->
    <el-radio-group v-model="sec" size="small" class="sec-switch">
      <el-radio-button value="properties">属性 {{ properties.length }}</el-radio-button>
      <el-radio-button value="events">事件 {{ events.length }}</el-radio-button>
      <el-radio-button value="services">服务 {{ services.length }}</el-radio-button>
    </el-radio-group>

    <!-- ══════════ 属性 ══════════ -->
    <div v-show="sec === 'properties'">
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
      <!-- 常用属性库：温度/湿度/电压这些属性的定义（单位、类型、量程）在行业里几乎有共识，
           与其让用户手敲，不如列出来勾选。与「+ 加一个属性」并存：冷门属性和自定义单位仍需手填。 -->
      <el-button size="small" class="add-btn" :icon="List" @click="propPickerRef?.open()">从常用属性添加</el-button>
    </div>

    <!-- ══════════ 事件 ══════════ -->
    <div v-show="sec === 'events'">
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
    </div>

    <!-- ══════════ 服务 ══════════ -->
    <div v-show="sec === 'services'">
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
    </div>

    <p class="hint">
      保存时校验：key 全局唯一且仅字母/数字/下划线；属性 type 仅 int/float/bool/string/enum（enum 必须配置枚举项 elements）、access 仅 readonly/readwrite。
    </p>

    <!-- 服务参数配置 -->
    <el-dialog v-model="paramsVisible" :title="`服务参数 · ${paramsService?.name || ''}`" width="640px" append-to-body>
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
    <el-dialog v-model="elementsVisible" :title="`枚举项 · ${elementsProp?.name || ''}`" width="480px" append-to-body>
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

    <!-- 常用属性库 -->
    <CommonPropertyDialog ref="propPickerRef" :existing-keys="existingPropKeys" @confirm="onAddCommonProperties" />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { List } from '@element-plus/icons-vue'
import CommonPropertyDialog from './CommonPropertyDialog.vue'

/**
 * 物模型编辑器（属性 / 事件 / 服务）。
 *
 * <h3>为什么要抽成组件</h3>
 * 原先**产品详情**里有一套完整的表格编辑器，而**模板编辑**只有一个 JSON 文本框 ——
 * 同一个"物模型"，一处能点着改、一处要手敲 JSON。用户问出「模板这里不能自己加属性吗」很正常。
 * 与其给模板再写一套（两套必然漂移），不如把产品这套抽出来共用：**一处维护、两处使用**。
 * 这与 `util/TslValidator` 是同一条原则 —— 规则只有一份。
 *
 * <h3>约定：属性/事件/服务三个数组是"就地修改"的</h3>
 * 组件直接改传入的数组元素（`row.key = x` 这类），**不**通过 emit 回传整个数组。
 * 理由：这是深层的三层嵌套结构，逐字段 emit 会让父组件代码爆炸；
 * 而 Vue 对「数组/对象内部属性」的修改是响应式的，不做 prop 重赋值就不会告警。
 * 父组件只需持有这三个 ref，改完自己 buildTsl / 保存即可。
 */
const props = defineProps({
  properties: { type: Array, required: true },
  events: { type: Array, required: true },
  services: { type: Array, required: true }
})

/** 当前分段（`v-model:section`） */
const sec = defineModel('section', { type: String, default: 'properties' })

const emit = defineEmits(['change'])

const types = ['int', 'float', 'bool', 'string', 'enum']

// ===== 常用属性库 =====
const propPickerRef = ref()
/** 已有属性 key：传给弹窗禁用重复项（key 在三段之间全局唯一，放任重复会填到保存才被拦） */
const existingPropKeys = computed(() =>
  props.properties.map((p) => String(p.key || '').trim()).filter(Boolean)
)

function onAddCommonProperties(rows) {
  if (!rows.length) return
  props.properties.push(...rows)
  sec.value = 'properties'
  emit('change')
}

// ===== 属性 / 事件 / 服务 =====
function addProperty() {
  props.properties.push({ key: '', name: '', type: 'float', unit: '', access: 'readonly', min: '', max: '', step: '', elements: [] })
  emit('change')
}

function addEvent() {
  props.events.push({ key: '', name: '', type: 'info' })
  emit('change')
}

function addService() {
  props.services.push({ key: '', name: '', callType: 'async', inputData: [], outputData: [] })
  emit('change')
}

/** 数字列：非数字就清空（否则会以字符串形式进 JSON，后端 Number 转换会失败） */
function normalizeNum(row, field) {
  const v = row[field]
  if (v === '' || v == null) return
  const n = Number(v)
  if (Number.isNaN(n)) row[field] = ''
  else row[field] = n
}

// ===== 服务参数 =====
const paramsVisible = ref(false)
const paramsService = ref(null)
const paramsInput = ref([])
const paramsOutput = ref([])

function openParams(row) {
  paramsService.value = row
  paramsInput.value = (row.inputData || []).map((p) => ({ ...p }))
  paramsOutput.value = (row.outputData || []).map((p) => ({ ...p }))
  paramsVisible.value = true
}

function confirmParams() {
  if (paramsService.value) {
    paramsService.value.inputData = paramsInput.value.filter((p) => String(p.key || '').trim())
    paramsService.value.outputData = paramsOutput.value.filter((p) => String(p.key || '').trim())
  }
  paramsVisible.value = false
  emit('change')
}

// ===== 枚举项 =====
const elementsVisible = ref(false)
const elementsProp = ref(null)
const elementsList = ref([])

function openElements(row) {
  elementsProp.value = row
  elementsList.value = (row.elements || []).map((e) => ({ value: e.value ?? '', key: e.key ?? '' }))
  elementsVisible.value = true
}

function confirmElements() {
  if (elementsProp.value) {
    elementsProp.value.elements = elementsList.value
      .filter((e) => e.value !== '' && e.value != null && String(e.key || '').trim())
      .map((e) => {
        const v = String(e.value).trim()
        return { value: /^-?\d+(\.\d+)?$/.test(v) ? Number(v) : v, key: String(e.key).trim() }
      })
  }
  elementsVisible.value = false
  emit('change')
}

defineExpose({ types })
</script>

<style scoped>
.sec-switch {
  margin-bottom: 4px;
}

.sec-title {
  margin: 12px 0 6px;
  font-size: 13px;
  font-weight: 600;
  color: #374151;
}

.add-btn {
  margin-top: 8px;
}

.hint {
  margin-top: 10px;
  font-size: 11px;
  line-height: 1.7;
  color: #9ca3af;
}

.params-block {
  margin-bottom: 14px;
}

.params-label {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 600;
  color: #374151;
}
</style>
