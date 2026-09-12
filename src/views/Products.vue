<template>
  <div class="products-page">
    <header class="topbar">
      <div class="brand">
        <div class="brand-logo">
          <el-icon :size="18"><Box /></el-icon>
        </div>
        <span>产品与物模型</span>
      </div>
      <div class="topbar-right">
        <el-button text @click="$router.push('/')">← 返回控制台</el-button>
      </div>
    </header>

    <div class="layout">
      <section class="panel left">
        <div class="panel-head">
          <span>产品列表</span>
          <el-button type="primary" size="small" :icon="Plus" @click="openCreate">新建产品</el-button>
        </div>
        <el-table
          :data="products"
          v-loading="loading"
          highlight-current-row
          size="small"
          @current-change="handleSelect"
        >
          <el-table-column prop="product_key" label="product_key" min-width="130" show-overflow-tooltip />
          <el-table-column prop="name" label="名称" min-width="110" show-overflow-tooltip />
          <el-table-column label="属性数" width="70">
            <template #default="{ row }">{{ (row.tsl?.properties || []).length }}</template>
          </el-table-column>
        </el-table>
        <p class="hint">选中产品后，右侧可编辑它的物模型。设备控制台按这里的 TSL 自动生成卡片与控件。</p>
      </section>

      <section class="panel right" v-if="selected">
        <div class="panel-head">
          <span>物模型 · {{ selected.name }}（{{ selected.product_key }}）</span>
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
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <el-select v-model="row.type" size="small">
                <el-option v-for="t in types" :key="t" :value="t" :label="t" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="单位" width="90">
            <template #default="{ row }"><el-input v-model="row.unit" size="small" placeholder="℃" /></template>
          </el-table-column>
          <el-table-column label="读写" width="110">
            <template #default="{ row }">
              <el-select v-model="row.access" size="small">
                <el-option value="readonly" label="readonly 只读" />
                <el-option value="readwrite" label="readwrite 可写" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="" width="60">
            <template #default="{ $index }">
              <el-button size="small" text type="danger" @click="properties.splice($index, 1)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-button size="small" class="add-btn" @click="addProperty">+ 加一个属性</el-button>

        <h4 class="sec-title">事件 events / 服务 services（JSON）</h4>
        <div class="json-row">
          <div>
            <p class="json-label">events</p>
            <el-input v-model="eventsJson" type="textarea" :rows="4" />
          </div>
          <div>
            <p class="json-label">services</p>
            <el-input v-model="servicesJson" type="textarea" :rows="4" />
          </div>
        </div>
        <p class="hint">保存时会校验：key 全局唯一且仅字母/数字/下划线；type 仅 int/float/bool/string；access 仅 readonly/readwrite。</p>
      </section>

      <section class="panel right empty" v-else>
        <p class="muted-empty">← 先在左侧选中一个产品</p>
      </section>
    </div>

    <el-dialog v-model="createVisible" title="新建产品" width="460px">
      <el-form label-width="100px">
        <el-form-item label="产品名称" required>
          <el-input v-model="createForm.name" placeholder="如：CO2 传感器" />
        </el-form-item>
        <el-form-item label="product_key">
          <el-input v-model="createForm.product_key" placeholder="留空自动生成 p_xxxxxxxx" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Box } from '@element-plus/icons-vue'
import { listProducts, createProduct, getProductTsl, updateProductTsl } from '../api'

const types = ['int', 'float', 'bool', 'string']

const products = ref([])
const loading = ref(false)
const selected = ref(null)
const saving = ref(false)

const properties = ref([])
const eventsJson = ref('[]')
const servicesJson = ref('[]')

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '', product_key: '' })

// 选中的产品引用（用于保存时定位 product_key）
const selectedKey = computed(() => selected.value?.product_key)

async function load() {
  loading.value = true
  try {
    const res = await listProducts()
    products.value = res.data.items || []
    // 保持选中态：刷新后按 product_key 找回
    if (selectedKey.value) {
      selected.value = products.value.find((p) => p.product_key === selectedKey.value) || null
    }
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function handleSelect(row) {
  selected.value = row || null
  if (row) loadTsl(row.product_key)
}

async function loadTsl(productKey) {
  try {
    const res = await getProductTsl(productKey)
    const tsl = res.data || {}
    properties.value = (tsl.properties || []).map((p) => ({ ...p }))
    eventsJson.value = JSON.stringify(tsl.events || [], null, 2)
    servicesJson.value = JSON.stringify(tsl.services || [], null, 2)
  } catch (e) {
    properties.value = []
  }
}

function addProperty() {
  properties.value.push({ key: '', name: '', type: 'float', unit: '', access: 'readonly' })
}

function buildTsl() {
  let events, services
  try {
    events = JSON.parse(eventsJson.value || '[]')
    services = JSON.parse(servicesJson.value || '[]')
  } catch (e) {
    ElMessage.error('events / services 不是合法 JSON')
    return null
  }
  return {
    properties: properties.value.filter((p) => String(p.key || '').trim() !== ''),
    events,
    services
  }
}

async function save() {
  const tsl = buildTsl()
  if (!tsl) return
  saving.value = true
  try {
    await updateProductTsl(selectedKey.value, tsl)
    ElMessage.success('物模型已保存，控制台界面将按新 TSL 自动更新')
    await load()
  } catch (e) {
    // 校验错误信息由后端返回、拦截器已提示
  } finally {
    saving.value = false
  }
}

function openCreate() {
  createForm.name = ''
  createForm.product_key = ''
  createVisible.value = true
}

async function doCreate() {
  if (!createForm.name.trim()) {
    ElMessage.warning('请填写产品名称')
    return
  }
  creating.value = true
  try {
    const payload = { name: createForm.name.trim() }
    if (createForm.product_key.trim()) payload.product_key = createForm.product_key.trim()
    const res = await createProduct(payload)
    ElMessage.success(`产品 ${res.data.product_key} 已创建`)
    createVisible.value = false
    await load()
  } catch (e) {
    // 拦截器已提示
  } finally {
    creating.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.products-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.topbar {
  height: 52px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
}
.brand-logo {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  background: #409eff;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}
.layout {
  flex: 1;
  display: flex;
  gap: 12px;
  padding: 12px;
  align-items: flex-start;
}
.panel {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px;
}
.left {
  width: 380px;
  flex-shrink: 0;
}
.right {
  flex: 1;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  font-weight: 600;
}
.sec-title {
  margin: 14px 0 8px;
  font-size: 13px;
  color: #606266;
}
.add-btn {
  margin-top: 8px;
  width: 100%;
}
.json-row {
  display: flex;
  gap: 12px;
}
.json-row > div {
  flex: 1;
}
.json-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}
.hint {
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
.muted-empty {
  color: #909399;
  text-align: center;
  padding: 60px 0;
}
</style>
