<template>
  <div class="chart-wrap">
    <div class="chart-toolbar">
      <div class="chart-tabs">
        <button
          v-for="p in chartableProps"
          :key="p.key"
          :class="{ active: p.key === activeProp }"
          @click="switchProp(p.key)"
        >
          {{ p.name }}
        </button>
        <span v-if="!chartableProps.length" class="muted">无可绘图属性</span>
      </div>
      <el-radio-group v-model="range" size="small" @change="load">
        <el-radio-button value="1h">1h</el-radio-button>
        <el-radio-button value="6h">6h</el-radio-button>
        <el-radio-button value="24h">24h</el-radio-button>
      </el-radio-group>
    </div>
    <div ref="chartEl" class="chart"></div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts'
import { getHistoryTelemetry } from '../api'

const props = defineProps({
  deviceKey: { type: String, required: true },
  tsl: { type: Object, default: null },
  telemetry: { type: Object, default: () => ({}) }
})

const chartEl = ref()
const activeProp = ref('')
const range = ref('1h')
let chart = null

const chartableProps = computed(() =>
  (props.tsl?.properties || []).filter((p) => p.type === 'int' || p.type === 'float')
)

const rangeMs = { '1h': 3600_000, '6h': 6 * 3600_000, '24h': 24 * 3600_000 }

function initChart() {
  if (!chartEl.value) return
  chart = echarts.init(chartEl.value)
  chart.setOption({
    grid: { left: 44, right: 16, top: 24, bottom: 28 },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'time', axisLine: { lineStyle: { color: '#d1d5db' } } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: '#f1f4f9' } } },
    series: []
  })
  window.addEventListener('resize', onResize)
}

function onResize() {
  chart && chart.resize()
}

async function load() {
  if (!activeProp.value) return
  const end = Date.now()
  const start = end - rangeMs[range.value]
  try {
    const res = await getHistoryTelemetry(props.deviceKey, activeProp.value, start, end)
    const items = res.data.items || []
    const data = items.map((it) => [it.ts, Number(it.value)])
    const prop = chartableProps.value.find((p) => p.key === activeProp.value)
    chart.setOption({
      series: [{
        name: prop ? prop.name : activeProp.value,
        type: 'line',
        smooth: true,
        showSymbol: false,
        data,
        lineStyle: { width: 2, color: '#3b82f6' },
        itemStyle: { color: '#3b82f6' },
        areaStyle: { color: 'rgba(59,130,246,0.08)' }
      }]
    })
  } catch (e) {
    // 错误提示已由拦截器处理
  }
}

function switchProp(key) {
  activeProp.value = key
  load()
}

watch(() => props.deviceKey, () => {
  if (chartableProps.value.length) {
    activeProp.value = chartableProps.value[0].key
    load()
  }
})

onMounted(() => {
  initChart()
  if (chartableProps.value.length) {
    activeProp.value = chartableProps.value[0].key
    load()
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart && chart.dispose()
})
</script>

<style scoped>
.chart-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  flex-wrap: wrap;
  gap: 8px;
}

.chart-tabs {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.chart-tabs button {
  padding: 4px 12px;
  border: 1px solid #e5eaf2;
  border-radius: 4px;
  background: #fff;
  font-size: 12px;
  color: #4b5563;
  cursor: pointer;
}

.chart-tabs button.active {
  background: #3b82f6;
  color: #fff;
  border-color: #3b82f6;
}

.chart-tabs .muted {
  font-size: 12px;
  color: #9ca3af;
}

.chart {
  height: 220px;
}
</style>
