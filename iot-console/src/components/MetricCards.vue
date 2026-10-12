<template>
  <div v-loading="loading" class="metric-wrap">
    <div class="card-grid">
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

    <!-- 5.3：数据时间 —— 只看到 26.5℃ 无法判断是刚上报的还是几小时前的。
         放在网格下方而不是每张卡上：设备一次上报就带全部属性（同一个 payload、同一个 ts），
         逐卡重复同一个时间只是噪音，反而让人以为它们是各自独立的。 -->
    <div v-if="properties.length" class="grid-foot">
      <template v-if="ts">
        数据时间 <b>{{ absText }}</b>
        <span class="rel" :class="{ stale: isStale }">{{ relText }}</span>
      </template>
      <template v-else>
        <span class="rel stale">暂无数据</span>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  tsl: { type: Object, default: null },
  telemetry: { type: Object, default: () => ({}) },
  /** 5.3：最新遥测的数据时间（epoch 毫秒；0 = 还没有数据） */
  ts: { type: Number, default: 0 },
  loading: { type: Boolean, default: false }
})

const properties = computed(() => props.tsl?.properties || [])

/** 超过这个时长没有新数据就标红提示（设备正常是 5s 一报，5 分钟已属异常） */
const STALE_MS = 5 * 60 * 1000

const isStale = computed(() => props.ts > 0 && Date.now() - props.ts > STALE_MS)

const absText = computed(() => {
  if (!props.ts) return ''
  const d = new Date(props.ts)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
})

const relText = computed(() => {
  if (!props.ts) return ''
  const diff = Date.now() - props.ts
  if (diff < 0) return '（时间在未来？请检查设备时钟）'
  const s = Math.floor(diff / 1000)
  if (s < 10) return '刚刚'
  if (s < 60) return `${s} 秒前`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} 分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h} 小时前`
  return `${Math.floor(h / 24)} 天前`
})

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
/* 5.3：网格 + 底部「数据时间」行 */
.metric-wrap {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.grid-foot {
  font-size: 12px;
  color: #9ca3af;
  display: flex;
  align-items: center;
  gap: 8px;
}

.grid-foot b {
  color: #6b7280;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

.grid-foot .rel {
  padding: 1px 8px;
  border-radius: 10px;
  background: #f1f5f9;
  color: #64748b;
}

/* 超过 5 分钟没有新数据 → 标红：设备正常是秒级上报，静默这么久本身就是个信号 */
.grid-foot .rel.stale {
  background: #fef2f2;
  color: #dc2626;
}

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
