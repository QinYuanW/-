<template>
  <div v-loading="loading" class="card-grid">
    <div v-for="prop in properties" :key="prop.key" class="metric">
      <div class="metric-label">
        {{ prop.name }}
        <el-tag size="small" effect="plain" :type="prop.access === 'readwrite' ? 'warning' : 'primary'">
          {{ prop.access === 'readwrite' ? '可写' : '只读' }}
        </el-tag>
      </div>
      <div class="metric-value">
        <template v-if="prop.type === 'bool'">
          {{ boolValue(prop.key) ? '开' : '关' }}
        </template>
        <template v-else-if="hasValue(prop.key)">
          {{ formatValue(prop.key) }}<span class="unit">{{ prop.unit || '' }}</span>
        </template>
        <template v-else>
          <span class="no-data">--</span>
        </template>
      </div>
      <div class="metric-src">{{ prop.key }} · {{ prop.type }}</div>
    </div>
    <el-empty
      v-if="!loading && !properties.length"
      description="该产品未配置属性"
      :image-size="50"
      style="grid-column: 1 / -1"
    />
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  tsl: { type: Object, default: null },
  telemetry: { type: Object, default: () => ({}) },
  loading: { type: Boolean, default: false }
})

const properties = computed(() => props.tsl?.properties || [])

function hasValue(key) {
  return props.telemetry[key] !== undefined && props.telemetry[key] !== null
}

function boolValue(key) {
  const v = props.telemetry[key]
  return v === true || v === 'true' || v === 1 || v === '1'
}

function formatValue(key) {
  const v = props.telemetry[key]
  const prop = properties.value.find((p) => p.key === key)
  if (prop && prop.type === 'enum') {
    const hit = (prop.elements || []).find((e) => String(e.value) === String(v))
    return hit ? hit.key : String(v)
  }
  if (prop && (prop.type === 'int' || prop.type === 'float')) {
    const n = Number(v)
    if (!Number.isNaN(n)) {
      return prop.type === 'int' ? String(Math.round(n)) : n.toFixed(1)
    }
  }
  return String(v)
}
</script>

<style scoped>
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 12px;
  min-height: 60px;
}

.metric {
  border: 1px solid #e5eaf2;
  border-radius: 8px;
  padding: 14px 12px;
  background: #fbfcfe;
  text-align: center;
}

.metric-label {
  font-size: 12px;
  color: #6b7280;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}

.metric-value {
  font-size: 24px;
  font-weight: 700;
  color: #1f2937;
  margin-top: 6px;
}

.metric-value .unit {
  font-size: 12px;
  font-weight: 400;
  color: #9ca3af;
  margin-left: 2px;
}

.metric-value .no-data {
  color: #d1d5db;
}

.metric-src {
  font-size: 11px;
  color: #b0b8c4;
  margin-top: 6px;
  font-family: Consolas, monospace;
}
</style>
