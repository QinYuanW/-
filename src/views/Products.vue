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
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Goods } from '@element-plus/icons-vue'
import { listProducts, createProduct, updateProductStatus, listDevices } from '../api'
import { formatTime } from '../utils/export'

const router = useRouter()

const products = ref([])
const loading = ref(false)
const productSearch = ref('')

const createVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ name: '', product_key: '' })

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
