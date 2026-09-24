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
              <!-- 7.2：属性/事件/服务三张表原先全挤在一个 tab 里，要翻很久才能到"服务"。
                   改成子 tab 只渲染当前这一类 —— 段上带计数，避免"被藏起来的东西被忘记"。 -->
              <el-radio-group v-model="tslSection" size="small" class="sec-switch">
                <el-radio-button value="properties">属性 {{ properties.length }}</el-radio-button>
                <el-radio-button value="events">事件 {{ events.length }}</el-radio-button>
                <el-radio-button value="services">服务 {{ services.length }}</el-radio-button>
              </el-radio-group>
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

          <div v-show="tslSection === 'properties'">
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
          <div v-show="tslSection === 'events'">
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
          <div v-show="tslSection === 'services'">
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
    <!-- 常用属性库（多选后追加到属性表，同样只改编辑区，点保存才落库） -->
    <CommonPropertyDialog ref="propPickerRef" :existing-keys="existingPropKeys" @confirm="onAddCommonProperties" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRoute, onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Back, Download, Upload, MagicStick, CollectionTag, List } from '@element-plus/icons-vue'
import { listProducts, getProductTsl, updateProductTsl, listDevices, createTslTemplate } from '../api'
import { formatTime } from '../utils/export'
import { mergeTsl } from '../constants/tslTemplates'
import TslTemplateDialog from '../components/TslTemplateDialog.vue'
import CommonPropertyDialog from '../components/CommonPropertyDialog.vue'

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
 * 走 buildTsl() 复用同一套校验，避免"存了份存不进产品的模板"。
 */
async function saveAsTemplate() {
  const { errors, tsl: t } = buildTsl()
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

// ===== 常用属性库（往现有物模型上补单个属性）=====
const propPickerRef = ref()

/**
 * 当前编辑区已有的属性 key。
 *
 * 传给弹窗用于**禁用已存在的项** —— 后端校验里 key 是「三段之间全局唯一」，
 * 若放任重复添加，用户会一路填到点保存时才被拦下，那时已白填一堆。
 */
const existingPropKeys = computed(() =>
  properties.value.map((p) => String(p.key || '').trim()).filter(Boolean)
)

function onAddCommonProperties(rows) {
  if (!rows.length) return
  properties.value = [...properties.value, ...rows]
  tslSection.value = 'properties'
  ElMessage.success(`已添加 ${rows.length} 个常用属性，点「保存物模型」才会落库`)
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
  const { tsl: t } = buildTsl()
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
  } finally {
    // 7.1：无论成功还是失败都要重取基线 —— 放在 finally 里，
    // 否则加载失败（三个数组被清空）会被判成"有未保存改动"，一进页面就弹提示
    loadedSnapshot.value = currentSnapshot()
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
