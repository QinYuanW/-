<template>
  <div class="dev-cards">
    <div class="card-scroll">
      <div v-loading="loading" class="card-grid">
        <div
          v-for="d in devices"
          :key="d.device_key"
          class="device-card"
          :class="{ checked: checkedKeys.includes(d.device_key) }"
        >
          <div class="card-top">
            <el-checkbox
              class="card-check"
              :model-value="checkedKeys.includes(d.device_key)"
              @change="(v) => onCheck(d, v)"
            />
            <div class="icon-box" :class="d.online_status === 1 ? 'on' : 'off'">
              <el-icon><Connection /></el-icon>
            </div>
            <div class="name-box">
              <div class="dev-name" @click="$emit('select', d)">{{ d.device_name || d.device_key }}</div>
              <div class="status-line">
                <span class="pill" :class="d.online_status === 1 ? 'on' : 'off'">
                  <i class="dot"></i>{{ d.online_status === 1 ? '在线' : '离线' }}
                </span>
                <!-- F5：停用与在线是两个维度，并排展示 -->
                <span v-if="d.status === 0" class="pill disabled">
                  <i class="dot"></i>已停用
                </span>
                <span class="tag" :title="productName(d.product_key)">{{ productName(d.product_key) }}</span>
              </div>
            </div>
          </div>

          <div class="card-meta">
            <div class="meta-item">
              <span>编号</span>
              <b class="mono" :title="d.device_key">{{ d.device_key }}</b>
            </div>
            <div class="meta-item">
              <span>最后在线</span>
              <b>{{ formatTime(d.last_online_at) }}</b>
            </div>
            <div class="meta-item">
              <span>注册时间</span>
              <b>{{ d.created_at ? formatTime(d.created_at) : '-' }}</b>
            </div>
            <div class="meta-item">
              <span>接入产品</span>
              <b :title="d.product_key">{{ d.product_key }}</b>
            </div>
          </div>

          <div class="card-actions">
            <button class="action" @click="$emit('select', d)">查看详情</button>
            <i class="divider"></i>
            <button class="action" @click="goTelemetry(d)">实时数据</button>
          </div>
        </div>
        <el-empty v-if="!loading && !devices.length" description="暂无设备" :image-size="80" class="cards-empty" />
      </div>
    </div>

    <div class="dev-pager">
      <el-pagination
        background
        layout="total, prev, pager, next, sizes"
        :total="total"
        :page-size="size"
        :current-page="page"
        :page-sizes="[10, 20, 50, 100]"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Connection } from '@element-plus/icons-vue'
import { formatTime } from '../utils/export'

const props = defineProps({
  devices: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  size: { type: Number, default: 20 },
  products: { type: Array, default: () => [] }
})

const emit = defineEmits(['select', 'page-change', 'size-change', 'selection-change'])

const router = useRouter()
const checkedKeys = ref([])

function productName(key) {
  const p = props.products.find((x) => x.product_key === key)
  return p ? p.name : key || '-'
}

function onCheck(d, v) {
  const set = new Set(checkedKeys.value)
  if (v) set.add(d.device_key)
  else set.delete(d.device_key)
  checkedKeys.value = [...set]
  emit('selection-change', props.devices.filter((x) => checkedKeys.value.includes(x.device_key)))
}

function goTelemetry(d) {
  router.push(`/devices/${d.device_key}?tab=telemetry`)
}

function handlePageChange(p) {
  emit('page-change', p)
}

function handleSizeChange(s) {
  emit('size-change', s)
}

function clearSelection() {
  checkedKeys.value = []
}

defineExpose({ clearSelection })
</script>

<style scoped>
/* F5 停用标签：用琥珀色与「在线/离线」区分 —— 两者是两个维度 */
.pill.disabled {
  color: #ba7517;
  background: #faeeda;
}

.pill.disabled .dot {
  background: #ef9f27;
}

.dev-cards {
  background: #fff;
  border: 1px solid #e5eaf2;
  border-radius: var(--radius-md, 10px);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
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

.device-card {
  position: relative;
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 10px;
  padding: 14px;
  transition: box-shadow 0.25s, transform 0.25s, border-color 0.25s;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.device-card:hover {
  box-shadow: 0 6px 18px rgba(31, 41, 55, 0.08);
  transform: translateY(-2px);
}

.device-card.checked {
  border-color: #2563eb;
  box-shadow: 0 0 0 1px #2563eb;
}

.card-check {
  position: absolute;
  top: 10px;
  left: 10px;
}

.card-top {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-left: 22px;
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
  flex-shrink: 0;
}

.icon-box.on {
  background: linear-gradient(135deg, #10b981, #059669);
}

.icon-box.off {
  background: linear-gradient(135deg, #9ca3af, #6b7280);
}

.name-box {
  flex: 1;
  min-width: 0;
}

.dev-name {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dev-name:hover {
  color: #2563eb;
}

.status-line {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  min-width: 0;
}

.pill {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 999px;
  flex-shrink: 0;
  /* 标签内文字不折行（窄屏时避免显示成「已停 / 用」） */
  white-space: nowrap;
}

.pill .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.pill.on {
  background: #ecfdf5;
  color: #047857;
}

.pill.on .dot {
  background: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.15);
}

.pill.off {
  background: #f3f4f6;
  color: #6b7280;
}

.pill.off .dot {
  background: #d1d5db;
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

.meta-item .mono {
  font-family: Consolas, monospace;
  color: #4b5563;
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

.divider {
  width: 1px;
  height: 14px;
  background: #e5eaf2;
}

.cards-empty {
  grid-column: 1 / -1;
}

.dev-pager {
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 1200px) {
  .card-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 900px) {
  .card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
