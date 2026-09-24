<template>
  <div class="tpl-page">
    <div class="page-head">
      <div>
        <div class="page-title">通用物模型</div>
        <div class="page-sub">
          内置常见设备的属性 / 事件 / 服务，一键套用到产品，省掉手敲十几个 key。
          你也可以把自己的物模型存成模板复用（存库，不存浏览器 —— 换机器也在）。
        </div>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建模板</el-button>
    </div>

    <div v-loading="loading">
      <!-- 我的模板（自定义，存库） -->
      <div class="sec-head">
        <span class="sec-title">我的模板</span>
        <span class="sec-count">{{ customTemplates.length }}</span>
      </div>
      <div v-if="!customTemplates.length" class="empty-sec">
        还没有自定义模板。点右上角「新建模板」，或者更快的路径：<b>打开某个产品的物模型页 → 点「存为模板」</b> —— 改完顺手存。
      </div>
      <div v-else class="tpl-grid">
        <div v-for="t in customTemplates" :key="t.id" class="tpl-card custom">
          <div class="card-head">
            <span class="tpl-name">{{ t.name }}</span>
            <span class="cat">{{ t.category || '未分类' }}</span>
          </div>
          <div class="tpl-desc">{{ t.description || '（无说明）' }}</div>
          <div class="stat-row"><span class="stat">{{ templateSummary(t) }}</span></div>
          <div class="kv-list">
            <div v-for="p in t.tsl.properties || []" :key="p.key" class="kv">
              <span class="k">{{ p.key }}</span>
              <span class="t">{{ p.type }}<template v-if="p.unit"> · {{ p.unit }}</template></span>
              <span v-if="p.access === 'readwrite'" class="rw">可写</span>
            </div>
            <div v-for="e in t.tsl.events || []" :key="e.key" class="kv">
              <span class="k">{{ e.key }}</span><span class="t">事件</span>
            </div>
            <div v-for="s in t.tsl.services || []" :key="s.key" class="kv">
              <span class="k">{{ s.key }}</span><span class="t">服务</span>
            </div>
          </div>
          <div class="card-actions">
            <el-button size="small" text type="danger" @click="removeTemplate(t)">删除</el-button>
            <el-button size="small" @click="openEdit(t)">编辑</el-button>
            <el-button size="small" type="primary" @click="applyTo(t)">套用到产品</el-button>
          </div>
        </div>
      </div>

      <!-- 内置模板（随版本走，不可编辑） -->
      <div class="sec-head">
        <span class="sec-title">内置模板</span>
        <span class="sec-count">{{ builtinTemplates.length }}</span>
        <span class="sec-note">随控制台版本提供，不可编辑</span>
      </div>
      <div class="tpl-grid">
        <div v-for="t in builtinTemplates" :key="t.id" class="tpl-card">
          <div class="card-head">
            <span class="tpl-name">{{ t.name }}</span>
            <span class="cat">{{ t.category }}</span>
          </div>
          <div class="tpl-desc">{{ t.description }}</div>
          <div class="stat-row"><span class="stat">{{ templateSummary(t) }}</span></div>
          <div class="kv-list">
            <div v-for="p in t.tsl.properties || []" :key="p.key" class="kv">
              <span class="k">{{ p.key }}</span>
              <span class="t">{{ p.type }}<template v-if="p.unit"> · {{ p.unit }}</template></span>
              <span v-if="p.access === 'readwrite'" class="rw">可写</span>
            </div>
            <div v-for="e in t.tsl.events || []" :key="e.key" class="kv">
              <span class="k">{{ e.key }}</span><span class="t">事件</span>
            </div>
            <div v-for="s in t.tsl.services || []" :key="s.key" class="kv">
              <span class="k">{{ s.key }}</span><span class="t">服务</span>
            </div>
          </div>
          <div class="card-actions">
            <el-button size="small" @click="openPreview(t)">查看详情</el-button>
            <!-- 内置模板不可编辑（随控制台版本走），但用户"想改两个属性"是真实需求。
                 与其让他绕「套用到产品 → 改 → 存为模板」三步，不如一键复制成自己的模板再改。 -->
            <el-button size="small" @click="forkTemplate(t)">复制为我的模板</el-button>
            <el-button size="small" type="primary" @click="applyTo(t)">套用到产品</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 套用对话框：内置 + 自定义一起可选 -->
    <TslTemplateDialog ref="dlgRef" :products="products" :custom-templates="rawCustom" @apply="onApply" />

    <!-- 新建 / 编辑模板 -->
    <el-dialog v-model="formVisible" :title="form.id ? '编辑模板' : '新建模板'" width="720px" top="6vh">
      <el-form label-width="92px">
        <el-form-item label="模板名称" required>
          <el-input v-model="form.name" maxlength="64" placeholder="如：办公室温湿度（带阈值事件）" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.category" placeholder="选一个或自己输入" filterable allow-create clearable style="width: 100%">
            <el-option v-for="c in CATEGORIES" :key="c" :value="c" :label="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" maxlength="255" placeholder="这个模板适合什么设备 / 用在哪" />
        </el-form-item>

        <el-form-item label="物模型">
          <div class="tsl-source">
            <div class="src-row">
              <span class="lbl">从产品复制</span>
              <el-select v-model="form.fromProduct" placeholder="选产品 → 取其物模型" clearable size="small" style="width: 240px" @change="pullFromProduct">
                <el-option
                  v-for="p in products"
                  :key="p.product_key"
                  :value="p.product_key"
                  :label="p.name + '（' + p.product_key + '）'"
                />
              </el-select>
              <span class="hint">选中即把该产品当前物模型填到下面</span>
            </div>
            <el-input v-model="form.tslText" type="textarea" :rows="9" spellcheck="false" placeholder="也可以直接粘贴物模型 JSON" />
            <div class="tsl-preview" :class="{ bad: !!tslError }">
              <template v-if="tslError">⚠️ {{ tslError }}</template>
              <template v-else-if="parsedTsl">
                属性 {{ parsedTsl.properties?.length || 0 }} · 事件 {{ parsedTsl.events?.length || 0 }} · 服务 {{ parsedTsl.services?.length || 0 }}
              </template>
              <template v-else>（还没有内容）</template>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!!tslError" @click="saveTemplate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { UNIFIED_BUILTIN, templateSummary, cloneTsl, mergeTsl, toUnifiedCustom } from '../constants/tslTemplates'
import {
  listProducts,
  getProductTsl,
  updateProductTsl,
  listTslTemplates,
  createTslTemplate,
  updateTslTemplate,
  deleteTslTemplate
} from '../api'
import TslTemplateDialog from '../components/TslTemplateDialog.vue'

const CATEGORIES = ['环境监测', '控制类', '安防类', '计量类', '网关/中继']

const dlgRef = ref()
const products = ref([])
/** 后端原始 VO —— 传给套用对话框（它内部会转成统一形状） */
const rawCustom = ref([])
const loading = ref(false)

const builtinTemplates = UNIFIED_BUILTIN
/** 卡片展示用：统一形状的自定义模板 */
const customTemplates = computed(() => rawCustom.value.map(toUnifiedCustom))

async function loadProducts() {
  try {
    const res = await listProducts()
    products.value = res.data?.items || []
  } catch {
    products.value = []
  }
}

async function loadTemplates() {
  loading.value = true
  try {
    const res = await listTslTemplates()
    rawCustom.value = res.data?.items || []
  } catch {
    rawCustom.value = []
  } finally {
    loading.value = false
  }
}

// ===== 新建 / 编辑 =====
const formVisible = ref(false)
const saving = ref(false)
const form = ref({ id: null, name: '', category: '', description: '', tslText: '', fromProduct: '' })

const parsedTsl = computed(() => {
  const t = form.value.tslText.trim()
  if (!t) return null
  try {
    return JSON.parse(t)
  } catch {
    return null
  }
})

/** 只做本地即时反馈；真正的结构校验（key 唯一、type 白名单…）在后端 TslValidator */
const tslError = computed(() => {
  const t = form.value.tslText.trim()
  if (!t) return '物模型不能为空'
  if (!parsedTsl.value) return 'JSON 解析失败：请检查括号、引号与逗号'
  if (!Array.isArray(parsedTsl.value.properties)) return 'properties 必须是数组（可为空）'
  return ''
})

function openCreate() {
  form.value = { id: null, name: '', category: '', description: '', tslText: '', fromProduct: '' }
  formVisible.value = true
}

function openEdit(t) {
  form.value = {
    id: t.rawId,
    name: t.name,
    category: t.category === '我的模板' ? '' : (t.category || ''),
    description: t.description === '（无说明）' ? '' : (t.description || ''),
    tslText: JSON.stringify(t.tsl, null, 2),
    fromProduct: ''
  }
  formVisible.value = true
}

/** 从产品取当前物模型 —— "改完产品顺手存成模板"是最常见的用法 */
async function pullFromProduct(productKey) {
  if (!productKey) return
  try {
    const res = await getProductTsl(productKey)
    form.value.tslText = JSON.stringify(res.data || {}, null, 2)
    if (!form.value.name.trim()) {
      const p = products.value.find((x) => x.product_key === productKey)
      if (p) form.value.name = p.name + '（模板）'
    }
  } catch (e) {
    // 拦截器已提示
  }
}

async function saveTemplate() {
  if (!form.value.name.trim()) {
    ElMessage.warning('请填写模板名称')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name.trim(),
      category: form.value.category || null,
      description: form.value.description || null,
      tsl: parsedTsl.value
    }
    if (form.value.id) {
      await updateTslTemplate(form.value.id, payload)
      ElMessage.success('模板已更新')
    } else {
      await createTslTemplate(payload)
      ElMessage.success('模板已创建')
    }
    formVisible.value = false
    await loadTemplates()
  } catch (e) {
    // 拦截器已提示（后端会带具体位置，如 properties[2].type ...）
  } finally {
    saving.value = false
  }
}

async function removeTemplate(t) {
  try {
    await ElMessageBox.confirm(
      '删除模板「' + t.name + '」？仅删模板本身，已套用到产品的物模型不受影响。',
      '确认删除',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await deleteTslTemplate(t.rawId)
    ElMessage.success('模板已删除')
    await loadTemplates()
  } catch (e) {
    // 拦截器已提示
  }
}

// ===== 套用 =====
function openPreview(t) {
  dlgRef.value?.open(t.id)
}

function applyTo(t) {
  dlgRef.value?.open(t.id)
}

/**
 * 把内置模板复制成「我的模板」。
 *
 * <p>为什么要它：内置模板随控制台版本走、**不可编辑**，但"想改两个属性/换个量程"是真实需求。
 * 原先只能绕「套用到产品 → 改 → 存为模板」三步 —— 既慢，又容易在中途丢改动
 * （用户可能改完忘了存为模板，或先存了别的东西）。
 *
 * <p>复制出来的是**全新的自定义模板**（独立一行、可编辑可删除），与内置那份互不影响。
 */
async function forkTemplate(t) {
  let name = ''
  try {
    const r = await ElMessageBox.prompt(
      '内置模板不可直接编辑（随控制台版本提供）。复制一份成你自己的模板，之后就能随意改属性与量程。',
      '复制为我的模板',
      {
        confirmButtonText: '复制',
        cancelButtonText: '取消',
        inputValue: `${t.name}（副本）`,
        inputPlaceholder: '新模板名称',
        inputValidator: (v) => (v && String(v).trim() ? true : '模板名称不能为空')
      }
    )
    name = String(r.value || '').trim()
  } catch (e) {
    return // 用户取消
  }
  try {
    await createTslTemplate({
      name,
      category: t.category || null,
      description: `从内置模板「${t.name}」复制`,
      tsl: cloneTsl(t.tsl)
    })
    ElMessage.success(`已复制为「${name}」—— 在「我的模板」里可直接编辑`)
    await loadTemplates()
    // 复制完把视线带到自己的模板上（否则用户还在内置区里找"我刚建的那个"）
    window.scrollTo({ top: 0, behavior: 'smooth' })
  } catch (e) {
    // 拦截器已提示
  }
}

/**
 * 落库。追加模式必须先取回目标产品**当前**物模型再合并 ——
 * 直接发模板内容等于把人家已有的定义删了（那是覆盖，不是追加）。
 */
async function onApply({ tsl, mode, productKey, templateName }) {
  if (!productKey) return
  const target = products.value.find((p) => p.product_key === productKey)

  if (mode === 'replace') {
    try {
      await ElMessageBox.confirm(
        '将用「' + templateName + '」整体替换产品 ' + (target?.name || productKey) +
        ' 现有的物模型（属性/事件/服务）。设备上报的旧属性 key 可能因此对不上，确定继续？',
        '确认覆盖物模型',
        { type: 'warning', confirmButtonText: '覆盖', cancelButtonText: '取消' }
      )
    } catch (e) {
      return
    }
  }

  try {
    let finalTsl = cloneTsl(tsl)
    if (mode === 'append') {
      const cur = await getProductTsl(productKey)
      const merged = mergeTsl(cur.data || {}, tsl, 'append')
      finalTsl = { properties: merged.properties, events: merged.events, services: merged.services }
    }
    await updateProductTsl(productKey, finalTsl)
    ElMessage.success(
      mode === 'replace'
        ? '已用「' + templateName + '」覆盖 ' + (target?.name || productKey) + ' 的物模型'
        : '已向 ' + (target?.name || productKey) + ' 追加「' + templateName + '」'
    )
  } catch (e) {
    // 拦截器已提示
  }
}

onMounted(() => {
  loadProducts()
  loadTemplates()
})
</script>

<style scoped>
.tpl-page {
  padding: 4px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
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
  max-width: 720px;
}

.sec-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 16px 0 10px;
}

.sec-title {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
}

.sec-count {
  font-size: 11px;
  color: #6b7280;
  background: #f3f6fb;
  border-radius: 4px;
  padding: 1px 6px;
}

.sec-note {
  font-size: 11px;
  color: #9ca3af;
}

.empty-sec {
  font-size: 12px;
  line-height: 1.8;
  color: #9ca3af;
  background: #fafbfd;
  border: 1px dashed #e5eaf2;
  border-radius: 8px;
  padding: 12px 14px;
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

.tpl-card.custom {
  border-color: #cfdff5;
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
  white-space: nowrap;
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

.tsl-source {
  width: 100%;
}

.src-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.src-row .lbl {
  font-size: 12px;
  color: #6b7280;
}

.src-row .hint {
  font-size: 11px;
  color: #9ca3af;
}

.tsl-preview {
  margin-top: 6px;
  font-size: 12px;
  color: #185fa5;
}

.tsl-preview.bad {
  color: #a32d2d;
}
</style>
