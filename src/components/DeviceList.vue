<template>
  <div class="dev-list">
    <div class="dev-list-header">
      <span>设备列表</span>
      <el-tag size="small" type="info" effect="plain">{{ total }}</el-tag>
    </div>
    <div class="dev-search">
      <el-input
        v-model="keyword"
        size="small"
        placeholder="搜索 device_key / 名称"
        clearable
        :prefix-icon="Search"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      >
        <template #append>
          <el-button :icon="Search" @click="handleSearch" />
        </template>
      </el-input>
    </div>
    <div class="dev-filter">
      <el-select
        v-model="productKey"
        size="small"
        clearable
        placeholder="按产品筛选（E5）"
        @change="handleFilter"
      >
        <el-option
          v-for="p in products"
          :key="p.product_key"
          :label="`${p.name}（${p.product_key}）`"
          :value="p.product_key"
        />
      </el-select>
    </div>
    <el-scrollbar class="dev-scroll">
      <div
        v-for="dev in devices"
        :key="dev.device_key"
        class="dev-item"
        :class="{ selected: dev.device_key === selectedKey }"
        @click="$emit('select', dev)"
      >
        <div class="dev-name">
          <span class="dot" :class="dev.online_status === 1 ? 'on' : ''"></span>
          {{ dev.device_name || dev.device_key }}
        </div>
        <div class="dev-meta">
          {{ dev.device_key }} · {{ productName(dev.product_key) }} ·
          {{ dev.online_status === 1 ? '在线' : '离线' }}
        </div>
      </div>
      <el-empty v-if="!loading && !devices.length" description="暂无设备" :image-size="60" />
    </el-scrollbar>
    <div class="dev-pager">
      <el-pagination
        small
        background
        layout="prev, pager, next"
        :total="total"
        :page-size="size"
        :current-page="page"
        @current-change="handlePageChange"
      />
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { Search } from '@element-plus/icons-vue'

const props = defineProps({
  devices: { type: Array, default: () => [] },
  selectedKey: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  size: { type: Number, default: 20 },
  // E5：产品列表（用于筛选下拉与设备行上的产品名显示）
  products: { type: Array, default: () => [] }
})

const emit = defineEmits(['select', 'search', 'page-change', 'filter'])

const keyword = ref('')
const productKey = ref('')

// product_key → 产品名（未匹配时退回 key 本身）
function productName(key) {
  const p = props.products.find((x) => x.product_key === key)
  return p ? p.name : key || '-'
}

function handleSearch() {
  emit('search', keyword.value.trim())
}

function handleFilter() {
  emit('filter', productKey.value || '')
}

function handlePageChange(p) {
  emit('page-change', p)
}
</script>

<style scoped>
.dev-list {
  background: #fff;
  border: 1px solid #e5eaf2;
  border-radius: 10px;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.dev-list-header {
  padding: 16px;
  border-bottom: 1px solid #f1f4f9;
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dev-search {
  padding: 10px 12px 0;
}

.dev-filter {
  padding: 8px 12px 0;
}

.dev-scroll {
  flex: 1;
  padding: 10px;
}

.dev-item {
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.15s;
  background: #fff;
}

.dev-item:hover {
  border-color: #bcd0f7;
}

.dev-item.selected {
  border-color: #3b82f6;
  background: #eff5ff;
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.12);
}

.dev-name {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  display: flex;
  align-items: center;
}

.dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #cbd5e1;
  margin-right: 8px;
  flex-shrink: 0;
}

.dot.on {
  background: #16a34a;
}

.dev-meta {
  font-size: 12px;
  color: #9ca3af;
  margin-top: 4px;
  padding-left: 16px;
}

.dev-pager {
  padding: 8px 12px;
  border-top: 1px solid #f1f4f9;
  display: flex;
  justify-content: center;
}
</style>
