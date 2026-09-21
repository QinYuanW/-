<template>
  <el-dialog v-model="visible" title="套用通用物模型" width="880px" top="6vh">
    <div class="tpl-body">
      <div class="tpl-list">
        <div
          v-for="t in TSL_TEMPLATES"
          :key="t.id"
          class="tpl-item"
          :class="{ active: t.id === selectedId }"
          @click="selectedId = t.id"
        >
          <div class="tpl-name">{{ t.name }}</div>
          <div class="tpl-meta">{{ t.category }} · {{ templateSummary(t) }}</div>
        </div>
      </div>

      <div class="tpl-detail">
        <div class="tpl-desc">{{ selected.description }}</div>

        <template v-if="selected.tsl.properties?.length">
          <h4 class="sec">属性 properties（{{ selected.tsl.properties.length }}）</h4>
          <el-table :data="selected.tsl.properties" size="small" max-height="180">
            <el-table-column prop="key" label="key" min-width="110" />
            <el-table-column prop="name" label="名称" min-width="90" />
            <el-table-column prop="type" label="类型" width="70" />
            <el-table-column label="单位" width="70">
              <template #default="{ row }">{{ row.unit || '-' }}</template>
            </el-table-column>
            <el-table-column label="读写" width="90">
              <template #default="{ row }">
                <span :class="{ rw: row.access === 'readwrite' }">{{ row.access === 'readwrite' ? '可写' : '只读' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>

        <template v-if="selected.tsl.events?.length">
          <h4 class="sec">事件 events（{{ selected.tsl.events.length }}）</h4>
          <el-table :data="selected.tsl.events" size="small">
            <el-table-column prop="key" label="key" min-width="140" />
            <el-table-column prop="name" label="名称" min-width="110" />
            <el-table-column label="级别" width="80">
              <template #default="{ row }">{{ eventLabel(row.type) }}</template>
            </el-table-column>
          </el-table>
        </template>

        <template v-if="selected.tsl.services?.length">
          <h4 class="sec">服务 services（{{ selected.tsl.services.length }}）</h4>
          <el-table :data="selected.tsl.services" size="small">
            <el-table-column prop="key" label="key" min-width="140" />
            <el-table-column prop="name" label="名称" min-width="110" />
            <el-table-column label="调用" width="70">
              <template #default="{ row }">{{ row.callType === 'sync' ? '同步' : '异步' }}</template>
            </el-table-column>
            <el-table-column label="参数" min-width="120">
              <template #default="{ row }">
                <span class="svc-params">{{ (row.inputData || []).map((x) => x.key).join(', ') || '无' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
    </div>

    <template #footer>
      <div class="tpl-footer">
        <div class="tpl-opts">
          <!-- 覆盖 vs 追加：默认覆盖更符合"套模板"的直觉；追加用于"在现有基础上补一组标准属性" -->
          <el-radio-group v-model="mode" size="small">
            <el-radio-button value="replace">覆盖当前物模型</el-radio-button>
            <el-radio-button value="append">追加（同名 key 跳过）</el-radio-button>
          </el-radio-group>

          <!-- 只有从「物模型」页进来才需要选目标产品；从产品详情进来时目标已确定 -->
          <div v-if="products.length" class="tpl-target">
            <span class="lbl">套用到产品</span>
            <el-select v-model="productKey" size="small" placeholder="选择产品" style="width: 200px">
              <el-option v-for="p in products" :key="p.product_key" :value="p.product_key" :label="`${p.name}（${p.product_key}）`" />
            </el-select>
          </div>
        </div>
        <div>
          <el-button @click="visible = false">取消</el-button>
          <el-button type="primary" :disabled="products.length > 0 && !productKey" @click="confirm">套用</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { TSL_TEMPLATES, templateSummary, cloneTsl } from '../constants/tslTemplates'

const props = defineProps({
  /** 传入产品列表 → 变成「直接套用到某产品」模式；不传则只把 tsl 交给父组件填编辑区 */
  products: { type: Array, default: () => [] }
})
const emit = defineEmits(['apply'])

const visible = ref(false)
const selectedId = ref(TSL_TEMPLATES[0].id)
const mode = ref('replace')
const productKey = ref('')

const selected = computed(() => TSL_TEMPLATES.find((t) => t.id === selectedId.value) || TSL_TEMPLATES[0])
const eventLabel = (t) => ({ info: '信息', alert: '告警', fault: '故障' }[t] || t)

/** @param templateId 可选：从模板卡片进来时预选该模板 */
function open(templateId) {
  if (templateId && TSL_TEMPLATES.some((t) => t.id === templateId)) {
    selectedId.value = templateId
  }
  mode.value = 'replace'
  productKey.value = ''
  visible.value = true
}

function confirm() {
  emit('apply', {
    tsl: cloneTsl(selected.value.tsl),
    mode: mode.value,
    productKey: productKey.value || null,
    templateName: selected.value.name
  })
  visible.value = false
}

defineExpose({ open })
</script>

<style scoped>
.tpl-body {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.tpl-list {
  width: 210px;
  flex: none;
  max-height: 420px;
  overflow-y: auto;
  border: 1px solid #e5eaf2;
  border-radius: 8px;
}

.tpl-item {
  padding: 9px 12px;
  cursor: pointer;
  border-bottom: 1px solid #f1f4f9;
  transition: background 0.15s;
}

.tpl-item:last-child {
  border-bottom: none;
}

.tpl-item:hover {
  background: #f7fafd;
}

.tpl-item.active {
  background: #eef4fe;
  box-shadow: inset 3px 0 0 #378add;
}

.tpl-name {
  font-size: 13px;
  color: #1f2937;
  font-weight: 500;
}

.tpl-meta {
  margin-top: 2px;
  font-size: 11px;
  color: #8a94a6;
}

.tpl-detail {
  flex: 1;
  min-width: 0;
}

.tpl-desc {
  font-size: 12px;
  line-height: 1.7;
  color: #6b7280;
  margin-bottom: 6px;
}

.sec {
  margin: 12px 0 6px;
  font-size: 12px;
  font-weight: 600;
  color: #374151;
}

.rw {
  color: #d85a30;
}

.svc-params {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: #4b5563;
}

.tpl-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.tpl-opts {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.tpl-target {
  display: flex;
  align-items: center;
  gap: 6px;
}

.tpl-target .lbl {
  font-size: 12px;
  color: #6b7280;
}
</style>
