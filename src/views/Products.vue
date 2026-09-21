<template>
  <div class="products-page">
    <div class="products-head">
      <div class="products-title">
        <span>产品</span>
        <el-tag size="small" type="info" effect="plain">{{ products.length }} 个</el-tag>
      </div>
    </div>

    <div class="toolbar">
      <el-input
        v-model="productSearch"
        class="tool-search"
        placeholder="搜索产品名称 / product_key"
        clearable
        :prefix-icon="Search"
      />
      <div class="tool-spacer" />
      <el-button type="primary" :icon="Plus" @click="openCreate">新建产品</el-button>
    </div>

    <div class="card-scroll">
      <div v-loading="loading" class="card-grid">
        <div
          v-for="p in filteredProducts"
          :key="p.product_key"
          class="product-card"
          @click="goDetail(p)"
        >
          <div class="card-top">
            <div class="icon-box"><el-icon><Goods /></el-icon></div>
            <div class="name-box">
              <div class="prod-name">{{ p.name }}</div>
              <div class="status-line">
                <span class="tag" :title="p.product_key">{{ p.product_key }}</span>
                <span class="tag soft">{{ nodeTypeTextOf(p) }}</span>
                <span v-if="p.status === 0" class="tag danger">已停用</span>
              </div>
            </div>
          </div>

          <div class="card-meta">
            <div class="meta-item">
              <span>接入协议</span>
              <b>{{ p.protocol || 'MQTT' }}</b>
            </div>
            <div class="meta-item">
              <span>数据格式</span>
              <b>{{ p.data_format || 'JSON' }}</b>
            </div>
            <div class="meta-item">
              <span>物模型规模</span>
              <b>{{ (p.tsl?.properties || []).length }} 属性 · {{ (p.tsl?.events || []).length }} 事件 · {{ (p.tsl?.services || []).length }} 服务</b>
            </div>
            <div class="meta-item">
              <span>创建时间</span>
              <b>{{ formatTime(p.created_at) }}</b>
            </div>
          </div>

          <div class="card-actions">
            <button class="action" @click.stop="goDetail(p)">查看详情</button>
            <i class="divider"></i>
            <button class="action" @click.stop="goDevices(p)">设备列表</button>
            <i class="divider"></i>
            <button
              class="action"
              :class="p.status === 0 ? 'ok' : 'warn'"
              @click.stop="toggleStatus(p)"
            >
              {{ p.status === 0 ? '启用' : '停用' }}
            </button>
            <!-- 6.1：改名原先没有入口（名字写错只能改库）；删除原先也没有 -->
            <i class="divider"></i>
            <button class="action" @click.stop="openCopy(p)">复制</button>
            <i class="divider"></i>
            <button class="action" @click.stop="openRename(p)">重命名</button>
            <i class="divider"></i>
            <button class="action danger" @click.stop="askDelete(p)">删除</button>
          </div>
        </div>
        <el-empty v-if="!loading && !filteredProducts.length" description="暂无产品" :image-size="80" class="cards-empty" />
      </div>
    </div>

    <!-- 新建产品 -->
    <el-dialog v-model="createVisible" title="新建产品" width="460px">
      <el-form label-width="100px">
        <el-form-item label="产品名称" required>
          <el-input v-model="createForm.name" placeholder="如：CO2 传感器" />
        </el-form-item>
          <el-form-item label="product_key">
            <el-input v-model="createForm.product_key" placeholder="留空自动生成 p_xxxxxxxx" />
          </el-form-item>
          <!-- 通用物模型：创建时就能带上属性/事件/服务，省掉"先建空产品 → 进详情 → 再套模板"三步 -->
          <el-form-item label="初始物模型">
            <el-select v-model="createForm.template_id" placeholder="留空 = 空白物模型" clearable style="width: 100%">
              <el-option
                v-for="t in TSL_TEMPLATES"
                :key="t.id"
                :value="t.id"
                :label="t.name + '（' + templateSummary(t) + '）'"
              />
            </el-select>
          </el-form-item>
        </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">创建</el-button>
      </template>
    </el-dialog>
    <!-- 7.5：复制产品（连物模型一起带走，用于"再开一个同型号产品"这种复用场景） -->
    <el-dialog v-model="copyVisible" title="复制产品" width="460px">
      <div class="rename-tip">
        会连**物模型一起复制**（属性 / 事件 / 服务），但**不复制设备** ——
        新产品的设备要从零注册或另行迁移。
      </div>
      <el-form label-width="96px">
        <el-form-item label="源产品">
          <el-input :model-value="`${copyForm.source_name}（${copyForm.source_key}）`" disabled />
        </el-form-item>
        <el-form-item label="新产品名称" required>
          <el-input v-model="copyForm.name" placeholder="如：CO2 传感器（一楼）" maxlength="64" />
        </el-form-item>
        <el-form-item label="product_key">
          <el-input v-model="copyForm.product_key" placeholder="留空自动生成 p_xxxxxxxx" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="copyVisible = false">取消</el-button>
        <el-button type="primary" :loading="copying" @click="doCopy">创建</el-button>
      </template>
    </el-dialog>

    <!-- 6.1：重命名产品 -->
    <el-dialog v-model="renameVisible" title="重命名产品" width="440px">
      <div class="rename-tip">
        product_key <span class="mono">{{ renameForm.product_key }}</span> 不可更改 ——
        它会进入 MQTT 主题与影子 field，改了会切断已有设备的接入。
      </div>
      <el-input
        v-model="renameForm.name"
        placeholder="请输入新的产品名称"
        maxlength="64"
        show-word-limit
        @keyup.enter="doRename"
      />
      <template #footer>
        <el-button @click="renameVisible = false">取消</el-button>
        <el-button type="primary" :loading="renaming" @click="doRename">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Goods } from '@element-plus/icons-vue'
import {
  listProducts,
  createProduct,
  updateProductStatus,
  renameProduct,
  deleteProduct,
  listDevices
} from '../api'
import { formatTime } from '../utils/export'
import { TSL_TEMPLATES, templateSummary, findTemplate, cloneTsl } from '../constants/tslTemplates'

const router = useRouter()

const products = ref([])
const loading = ref(false)

// 6.1：重命名弹窗
const renameVisible = ref(false)
const renaming = ref(false)
const renameForm = reactive({ product_key: '', name: '' })
const productSearch = ref('')

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '', product_key: '', template_id: '' })

const filteredProducts = computed(() => {
  const kw = productSearch.value.trim().toLowerCase()
  if (!kw) return products.value
  return products.value.filter(
    (p) =>
      String(p.name || '').toLowerCase().includes(kw) ||
      String(p.product_key || '').toLowerCase().includes(kw)
  )
})

function nodeTypeTextOf(p) {
  const m = { 1: '直连设备', 2: '网关设备', 3: '子设备' }
  return m[p.node_type] || '直连设备'
}

async function load() {
  loading.value = true
  try {
    const res = await listProducts()
    products.value = res.data.items || []
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function goDetail(p) {
  router.push(`/products/${p.product_key}`)
}

function goDevices(p) {
  router.push(`/products/${p.product_key}?tab=devices`)
}

/**
 * F2 启停产品。
 *
 * 停用是**级联软禁用**：该产品下所有设备会被拒绝 MQTT 接入，但**不修改子设备自身的启停状态** ——
 * 这样产品恢复后，每台设备的原状态能准确复现（哪些是停用的、哪些是本就好着的）。
 */
async function toggleStatus(p) {
  const isDisable = p.status !== 0

  // 影响面：停用是**级联**的，只说"其下所有设备"运维无法预估这一下捅了多大篓子。
  // 取台数用 size=1（只为拿 total），失败时不阻断主流程，退回模糊表述。
  let affected = null
  if (isDisable) {
    try {
      const res = await listDevices(1, 1, '', p.product_key)
      affected = res.data.total
    } catch (e) {
      // 拦截器已提示；台数拿不到就不显示
    }
  }
  const scope = affected == null ? '该产品下所有设备' : `该产品下 ${affected} 台设备`

  try {
    await ElMessageBox.confirm(
      isDisable
        ? `停用产品「${p.name}」后，${scope}将被拒绝 MQTT 接入与数据上报（历史数据与配置全部保留）。各设备自身的启停状态不受影响，恢复产品即一并恢复。`
        : `启用产品「${p.name}」，其下设备恢复正常接入能力。`,
      isDisable ? '停用产品' : '启用产品',
      { type: 'warning', confirmButtonText: isDisable ? '停用' : '启用' }
    )
  } catch {
    return
  }
  try {
    await updateProductStatus(p.product_key, isDisable ? 0 : 1)
    ElMessage.success(isDisable ? '已停用' : '已启用')
    await load()
  } catch (e) {
    // 拦截器已提示
  }
}

// ===== 6.1 重命名 / 删除 =====

// ===== 7.5 复制产品 =====
// 物模型复用（"再开一个同型号产品"）很常见，原先只能新建一个空产品再手敲一遍 TSL。
// 后端 `POST /products` 本来就接受 tsl 字段，所以这里是纯前端补齐入口，不用改后端。
const copyVisible = ref(false)
const copying = ref(false)
const copyForm = reactive({ source_key: '', source_name: '', name: '', product_key: '', tsl: null })

function openCopy(p) {
  copyForm.source_key = p.product_key
  copyForm.source_name = p.name || p.product_key
  copyForm.name = `${p.name || p.product_key} 副本`
  copyForm.product_key = ''
  // 存下源物模型（列表接口已经返回解析后的 tsl 对象）
  copyForm.tsl = p.tsl || { properties: [], events: [], services: [] }
  copyVisible.value = true
}

async function doCopy() {
  const name = copyForm.name.trim()
  if (!name) {
    ElMessage.warning('请填写新产品名称')
    return
  }
  copying.value = true
  try {
    const res = await createProduct({
      name,
      product_key: copyForm.product_key.trim() || undefined,
      tsl: copyForm.tsl
    })
    ElMessage.success(`已创建 ${res.data?.product_key || '新产品'}（含物模型，设备需另行注册）`)
    copyVisible.value = false
    load()
  } catch (e) {
    // 拦截器已提示（如 product_key 重复 / 物模型校验失败）
  } finally {
    copying.value = false
  }
}

async function openRename(p) {
  renameForm.product_key = p.product_key
  renameForm.name = p.name || ''
  renameVisible.value = true
}

async function doRename() {
  const name = renameForm.name.trim()
  if (!name) {
    ElMessage.warning('产品名称不能为空')
    return
  }
  renaming.value = true
  try {
    await renameProduct(renameForm.product_key, name)
    ElMessage.success('名称已更新')
    renameVisible.value = false
    load()
  } catch (e) {
    // 拦截器已提示
  } finally {
    renaming.value = false
  }
}

/**
 * 删除产品。
 *
 * 后端有护栏「名下还有设备就拒绝删除」—— 这里先把设备数查出来告诉用户，
 * 而不是等他点下去才吃一个报错：**能提前讲清后果的，就别让用户去撞**。
 */
async function askDelete(p) {
  let count = 0
  try {
    const res = await listDevices(1, 1, '', p.product_key, 0, null)
    count = res.data?.total || 0
  } catch {
    // 查不到就按未知处理，交给后端护栏兜底
  }
  if (count > 0) {
    ElMessageBox.alert(
      `该产品下还有 ${count} 台设备，不能删除。请先删除这些设备，或把它们迁到其它产品。`,
      '无法删除',
      { type: 'warning', confirmButtonText: '知道了' }
    )
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定删除产品「${p.name}」吗？\n物模型与配置会一并移除（逻辑删除，数据仍在库中）。`,
      '删除产品',
      { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }
  try {
    await deleteProduct(p.product_key)
    ElMessage.success('产品已删除')
    load()
  } catch (e) {
    // 拦截器已提示
  }
}

  function openCreate() {
    createForm.name = ''
    createForm.product_key = ''
    createForm.template_id = ''
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
      // 选了通用物模型就一并带上（后端 POST /products 本来就接受 tsl）——
      // 省掉"先建空产品 → 进详情 → 再套模板"三步
      const tpl = createForm.template_id ? findTemplate(createForm.template_id) : null
      if (tpl) payload.tsl = cloneTsl(tpl.tsl)
      const res = await createProduct(payload)
      ElMessage.success(
        tpl
          ? `产品 ${res.data.product_key} 已创建（含「${tpl.name}」物模型）`
          : `产品 ${res.data.product_key} 已创建`
      )
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
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 24px;
  overflow: hidden;
}

.products-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.products-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.tool-search {
  width: 280px;
}

.tool-spacer {
  flex: 1;
}

.card-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}

.product-card {
  position: relative;
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 10px;
  padding: 14px;
  transition: box-shadow 0.25s, transform 0.25s, border-color 0.25s;
  display: flex;
  flex-direction: column;
  gap: 12px;
  cursor: pointer;
}

.product-card:hover {
  box-shadow: 0 6px 18px rgba(31, 41, 55, 0.08);
  transform: translateY(-2px);
  border-color: #c7d8f0;
}

.card-top {
  display: flex;
  align-items: center;
  gap: 12px;
}

.icon-box {
  width: 46px;
  height: 46px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  color: #fff;
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  flex-shrink: 0;
}

.name-box {
  flex: 1;
  min-width: 0;
}

.prod-name {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.status-line {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  min-width: 0;
}

.tag {
  font-size: 11px;
  background: #eff6ff;
  color: #2563eb;
  padding: 1px 8px;
  border-radius: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 60%;
}

.tag.soft {
  background: #f3f4f6;
  color: #6b7280;
}

/*
 * 「已停用」标签：红底红字。
 * 此前模板写了 class="tag danger"，但 .danger 的样式**从未定义** ——
 * 于是它退回成基础 .tag 的蓝底蓝字，和旁边的 product_key 标签长得一模一样，
 * 「这个产品挂了」这条信息被彻底吞掉。2026-09-15 修复。
 */
.tag.danger {
  background: #fef2f2;
  color: #dc2626;
  font-weight: 500;
}

.card-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 12px;
  padding: 10px 12px;
  background: #fafbfc;
  border-radius: 8px;
}

.meta-item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.meta-item span {
  font-size: 11px;
  color: #9ca3af;
}

.meta-item b {
  font-size: 12px;
  font-weight: 500;
  color: #374151;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-actions {
  display: flex;
  align-items: center;
  border-top: 1px solid #f0f2f5;
  padding-top: 10px;
}

.action {
  flex: 1;
  border: none;
  background: none;
  font-size: 13px;
  color: #6b7280;
  cursor: pointer;
  padding: 4px 0;
  transition: color 0.2s;
}

.action:hover {
  color: #2563eb;
}

/*
 * 「启用 / 停用」两个动作的语义色：启用绿、停用红，与卡片上的「已停用」标签呼应。
 * 模板里写了 :class="p.status === 0 ? 'ok' : 'warn'"，但 .ok / .warn 同样**没有样式** ——
 * 两个动作此前都是灰字，用户得读完文字才知道哪个是"危险操作"。2026-09-15 补齐。
 * 注意顺序：.action.ok:hover 特异性高于 .action:hover，能稳定压过默认蓝。
 */
.action.ok {
  color: #16a34a;
}

.action.ok:hover {
  color: #15803d;
}

.action.warn {
  color: #dc2626;
}

.action.warn:hover {
  color: #b91c1c;
}

/* 6.1：删除是**不可逆**动作，用危险色与「停用」（可一键恢复）区分开 ——
   这两个动作后果完全不同，长得一样会误导。 */
.action.danger {
  color: #dc2626;
}

.action.danger:hover {
  color: #b91c1c;
  background: #fef2f2;
}

/* 6.1：重命名弹窗里的说明 */
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

.divider {
  width: 1px;
  height: 14px;
  background: #e5eaf2;
}

.cards-empty {
  grid-column: 1 / -1;
}

@media (max-width: 1200px) {
  .card-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 900px) {
  .products-page {
    padding: 12px;
  }

  .card-grid {
    grid-template-columns: 1fr;
  }

  .tool-search {
    width: 100%;
  }
}
</style>
