<template>
  <div class="dev-list">
    <div class="dev-scroll">
      <el-table
        ref="tableRef"
        :data="devices"
        v-loading="loading"
        size="default"
        class="dev-table"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="44" :selectable="() => true" />
        <el-table-column label="设备名称" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <a class="dev-name-link" @click="$emit('select', row)">{{ row.device_name || row.device_key }}</a>
          </template>
        </el-table-column>
        <el-table-column prop="device_key" label="device_key" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.device_key }}</span>
          </template>
        </el-table-column>
        <el-table-column label="所属产品" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ productName(row.product_key) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="status-tag" :class="row.online_status === 1 ? 'on' : 'off'">
              <i class="dot"></i>{{ row.online_status === 1 ? '在线' : '离线' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="最后在线时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.last_online_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="$emit('select', row)">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="loading ? '加载中…' : '暂无设备'" :image-size="80" />
        </template>
      </el-table>
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

const tableRef = ref()

function productName(key) {
  const p = props.products.find((x) => x.product_key === key)
  return p ? p.name : key || '-'
}

function handleSelectionChange(rows) {
  emit('selection-change', rows)
}

function handlePageChange(p) {
  emit('page-change', p)
}

function handleSizeChange(s) {
  emit('size-change', s)
}

function clearSelection() {
  tableRef.value?.clearSelection()
}

defineExpose({ clearSelection })
</script>

<style scoped>
.dev-list {
  background: #fff;
  border: 1px solid #e5eaf2;
  border-radius: var(--radius-md, 10px);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 0;
}

.dev-table {
  width: 100%;
}

.dev-name-link {
  color: var(--el-color-primary);
  cursor: pointer;
  font-weight: 500;
  text-decoration: none;
}

.dev-name-link:hover {
  text-decoration: underline;
}

.mono {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: #4b5563;
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
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.15);
}

.status-tag.off {
  background: #f3f4f6;
  color: #6b7280;
}

.status-tag.off .dot {
  background: #d1d5db;
}

.dev-pager {
  display: flex;
  justify-content: flex-end;
}
</style>
