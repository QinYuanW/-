<template>
  <div class="tpl-page">
    <div class="page-head">
      <div>
        <div class="page-title">通用物模型</div>
        <div class="page-sub">
          内置常见设备的属性 / 事件 / 服务，一键套用到产品，省掉手敲十几个 key。
          模板是「起点」不是「终点」—— 套用后仍可去产品详情继续改。
        </div>
      </div>
    </div>

    <div class="tpl-grid">
      <div v-for="t in TSL_TEMPLATES" :key="t.id" class="tpl-card">
        <div class="card-head">
          <span class="tpl-name">{{ t.name }}</span>
          <span class="cat">{{ t.category }}</span>
        </div>
        <div class="tpl-desc">{{ t.description }}</div>

        <div class="stat-row">
          <span class="stat">{{ templateSummary(t) }}</span>
        </div>

        <div class="kv-list">
          <div v-for="p in t.tsl.properties || []" :key="p.key" class="kv">
            <span class="k">{{ p.key }}</span>
            <span class="t">{{ p.type }}<template v-if="p.unit"> · {{ p.unit }}</template></span>
            <span v-if="p.access === 'readwrite'" class="rw">可写</span>
          </div>
          <div v-for="e in t.tsl.events || []" :key="e.key" class="kv">
            <span class="k">{{ e.key }}</span>
            <span class="t">事件</span>
          </div>
          <div v-for="s in t.tsl.services || []" :key="s.key" class="kv">
            <span class="k">{{ s.key }}</span>
            <span class="t">服务</span>
          </div>
        </div>

        <div class="card-actions">
          <el-button size="small" @click="openPreview(t)">查看详情</el-button>
          <el-button size="small" type="primary" @click="applyTo(t)">套用到产品</el-button>
        </div>
      </div>
    </div>

    <TslTemplateDialog ref="dlgRef" :products="products" @apply="onApply" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { TSL_TEMPLATES, templateSummary, cloneTsl, mergeTsl } from '../constants/tslTemplates'
import { listProducts, getProductTsl, updateProductTsl } from '../api'
import TslTemplateDialog from '../components/TslTemplateDialog.vue'

const dlgRef = ref()
const products = ref([])
const pending = ref(null)

async function loadProducts() {
  try {
    const res = await listProducts()
    products.value = res.data?.items || []
  } catch {
    products.value = []
  }
}

/** 看详情：复用同一个弹窗（只是打开时预选这个模板） */
function openPreview(t) {
  dlgRef.value?.open(t.id)
}

/** 套用到产品：预选模板并打开，进去再挑目标产品 */
function applyTo(t) {
  dlgRef.value?.open(t.id)
}

/**
 * 落库。
 *
 * 追加模式下必须先取回目标产品**当前**的物模型再合并 ——
 * 直接发模板内容等于把人家已有的定义删了（那就是覆盖，不是追加）。
 */
async function onApply({ tsl, mode, productKey, templateName }) {
  if (!productKey) return
  const target = products.value.find((p) => p.product_key === productKey)

  if (mode === 'replace') {
    try {
      await ElMessageBox.confirm(
        `将用「${templateName}」**整体替换**产品 ${target?.name || productKey} 现有的物模型（属性/事件/服务）。设备上报的旧属性 key 可能因此对不上，确定继续？`,
        '确认覆盖物模型',
        { type: 'warning', confirmButtonText: '覆盖', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }

  try {
    let finalTsl = cloneTsl(tsl)
    if (mode === 'append') {
      const cur = await getProductTsl(productKey)
      const curTsl = cur.data || {}
      const merged = mergeTsl(curTsl, tsl, 'append')
      finalTsl = { properties: merged.properties, events: merged.events, services: merged.services }
    }
    await updateProductTsl(productKey, finalTsl)
    ElMessage.success(
      mode === 'replace'
        ? `已用「${templateName}」覆盖 ${target?.name || productKey} 的物模型`
        : `已向 ${target?.name || productKey} 追加「${templateName}」`
    )
  } catch (e) {
    // 拦截器已提示（如后端物模型校验失败）
  }
}

onMounted(loadProducts)
</script>

<style scoped>
.tpl-page {
  padding: 4px;
}

.page-head {
  margin-bottom: 16px;
}

.page-title {
  font-size: 17px;
  font-weight: 600;
  color: #1f2937;
}

.page-sub {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #6b7280;
  max-width: 780px;
}

.tpl-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}

.tpl-card {
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e5eaf2;
  border-radius: 10px;
  padding: 14px 16px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.tpl-name {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
}

.cat {
  font-size: 11px;
  color: #6b7280;
  background: #f3f6fb;
  border-radius: 4px;
  padding: 2px 6px;
}

.tpl-desc {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.7;
  color: #6b7280;
  min-height: 40px;
}

.stat-row {
  margin-top: 8px;
}

.stat {
  font-size: 11px;
  color: #185fa5;
  background: #e6f1fb;
  border-radius: 4px;
  padding: 2px 6px;
}

.kv-list {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #eef1f6;
  flex: 1;
  max-height: 150px;
  overflow-y: auto;
}

.kv {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  line-height: 1.9;
}

.kv .k {
  font-family: Consolas, monospace;
  color: #374151;
  flex: 1;
}

.kv .t {
  color: #9ca3af;
}

.kv .rw {
  color: #d85a30;
  font-size: 11px;
}

.card-actions {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
