<template>
  <div class="dashboard">
    <div class="page-tabs">
      <span class="tab active">物联概览</span>
    </div>

    <div class="stats-bar">
      <div v-for="c in statCards" :key="c.key" class="stat-card">
        <div class="stat-main">
          <div class="stat-icon" :style="{ background: c.gradient }">
            <el-icon :size="22"><component :is="c.icon" /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-num">{{ c.value }}</div>
            <div class="stat-label">{{ c.label }}</div>
          </div>
        </div>
        <div class="stat-foot">
          <div v-for="f in c.foot" :key="f.label" class="foot-item">
            <el-icon :size="13" :color="f.color"><component :is="f.icon" /></el-icon>
            <span class="foot-label">{{ f.label }}</span>
            <b class="foot-value">{{ f.value }}</b>
          </div>
        </div>
      </div>
    </div>

    <div class="mid-grid">
      <section class="panel">
        <div class="panel-title">设备消息</div>
        <div ref="trendEl" class="trend-chart"></div>
      </section>

      <section class="panel">
        <div class="panel-title">告警类型</div>
        <div class="panel-empty">
          <el-empty description="暂无告警数据" :image-size="70" />
        </div>
      </section>
    </div>

    <section class="panel">
      <div class="panel-title">告警消息</div>
      <el-table :data="alarmList" size="small">
        <el-table-column prop="alarmType" label="告警类型" width="110" />
        <el-table-column prop="level" label="规则级别" width="100" />
        <el-table-column prop="ruleName" label="规则名称" min-width="170" show-overflow-tooltip />
        <el-table-column prop="productKey" label="产品标识" min-width="160" show-overflow-tooltip />
        <el-table-column prop="deviceKey" label="设备标识" min-width="160" show-overflow-tooltip />
        <el-table-column label="告警状态" width="110">
          <template #default="{ row }">
            <span class="alarm-status" :class="row.status === '已处理' ? 'done' : 'todo'">{{ row.status }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="alarmAt" label="告警时间" width="170" />
        <el-table-column label="操作" width="80">
          <template #default>
            <el-button link type="primary" size="small">详情</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无告警消息" :image-size="70" />
        </template>
      </el-table>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import {
  Box,
  Monitor,
  ChatDotRound,
  Bell,
  CircleCheckFilled,
  VideoPause,
  Calendar,
  Clock
} from '@element-plus/icons-vue'
import { getStatsOverview, getMessageTrend, listAlerts } from '../api'

// ===== 实时数据（E6 接入，mock 已下线）=====
// 刻意保持与 mock 相同的结构，这样模板与 statCards / initTrend 的引用方式无需改动。

const overview = reactive({
  product: { total: 0, enabled: 0, disabled: 0 },
  device: { total: 0, online: 0, offline: 0, enabled: 0, disabled: 0 },
  message: { total: 0, month: 0, today: 0 },
  alarm: { total: 0, month: 0, today: 0 }
})

const messageTrend = reactive({ months: [], message: [], warn: [] })
const alarmList = ref([])
const loading = ref(false)

/** 通知趋势图重绘（数据是异步到的，initTrend 时可能还是空的） */
const trendReady = ref(false)

const trendEl = ref()
let chart = null

function formatWan(n) {
  if (n == null) return '0'
  return n >= 10000 ? `${(n / 10000).toFixed(2)}万` : String(n)
}

const statCards = computed(() => [
  {
    key: 'product',
    label: '产品',
    value: overview.product.total,
    icon: Box,
    gradient: 'linear-gradient(135deg, #60a5fa, #2563eb)',
    foot: [
      { icon: CircleCheckFilled, color: '#16a34a', label: '启用', value: overview.product.enabled },
      { icon: VideoPause, color: '#f59e0b', label: '停用', value: overview.product.disabled }
    ]
  },
  {
    key: 'device',
    label: '在线设备',
    value: overview.device.online,
    icon: Monitor,
    gradient: 'linear-gradient(135deg, #818cf8, #6366f1)',
    foot: [
      { icon: CircleCheckFilled, color: '#16a34a', label: '启用', value: overview.device.enabled },
      { icon: VideoPause, color: '#f59e0b', label: '停用', value: overview.device.disabled }
    ]
  },
  {
    key: 'message',
    label: '设备消息',
    value: formatWan(overview.message.total),
    icon: ChatDotRound,
    gradient: 'linear-gradient(135deg, #fba97a, #f97316)',
    foot: [
      { icon: Calendar, color: '#3b82f6', label: '本月', value: formatWan(overview.message.month) },
      { icon: Clock, color: '#fbbf24', label: '今日', value: formatWan(overview.message.today) }
    ]
  },
  {
    key: 'alarm',
    label: '设备告警',
    value: overview.alarm.total,
    icon: Bell,
    gradient: 'linear-gradient(135deg, #c4b5fd, #a78bfa)',
    foot: [
      { icon: Calendar, color: '#3b82f6', label: '本月', value: overview.alarm.month },
      { icon: Clock, color: '#fbbf24', label: '今日', value: overview.alarm.today }
    ]
  }
])

function initTrend() {
  if (!trendEl.value) return
  chart = echarts.init(trendEl.value)
  chart.setOption({
    grid: { left: 76, right: 24, top: 24, bottom: 36 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#fff',
      borderColor: '#e5eaf2',
      padding: [8, 12],
      textStyle: { color: '#374151', fontSize: 12 },
      extraCssText: 'box-shadow: 0 8px 24px rgba(16,24,40,0.12); border-radius: 8px;',
      formatter: (params) => {
        const total = params.reduce((s, p) => s + (Number(p.value) || 0), 0)
        const head = `<div style="font-weight:600;color:#1f2937">${params[0].axisValue}</div>`
        const lines = params
          .map((p) => {
            const pct = total ? ((Number(p.value) / total) * 100).toFixed(0) : 0
            return `<div style="display:flex;align-items:center;gap:6px;margin-top:5px">${p.marker}<span>${p.seriesName}: ${Number(p.value).toLocaleString()} (${pct}%)</span></div>`
          })
          .join('')
        return head + lines
      }
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: messageTrend.months,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#e5eaf2' } },
      axisLabel: { color: '#9ca3af', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      // 原先硬编码 max: 2300000（照 mock 量级写的）——真实数据量级不同会把曲线压扁，交给 ECharts 自适应
      splitLine: { lineStyle: { type: 'dashed', color: '#eef1f6' } },
      axisLabel: { color: '#9ca3af', fontSize: 11, formatter: (v) => v.toLocaleString() }
    },
    series: [
      {
        name: '消息量',
        type: 'line',
        data: messageTrend.message,
        symbol: 'circle',
        symbolSize: 5,
        lineStyle: { color: '#6366f1', width: 2 },
        itemStyle: { color: '#6366f1', borderColor: '#fff', borderWidth: 1 },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(99,102,241,0.28)' },
            { offset: 1, color: 'rgba(99,102,241,0.02)' }
          ])
        }
      },
      {
        name: '预警量',
        type: 'line',
        data: messageTrend.warn,
        symbol: 'circle',
        symbolSize: 4,
        lineStyle: { color: '#f59e0b', width: 1.5, type: 'dashed' },
        itemStyle: { color: '#f59e0b' }
      }
    ]
  })
  window.addEventListener('resize', onResize)
}

function onResize() {
  chart && chart.resize()
}

async function loadDashboard() {
  loading.value = true
  try {
    const [ov, trend, alarms] = await Promise.all([
      getStatsOverview(),
      getMessageTrend('day', 30),
      listAlerts(1, 10)
    ])

    Object.assign(overview, ov.data)

    // 趋势：后端只返回「有数据的桶」，X 轴标签截成 MM-DD 便于阅读
    const items = trend.data?.items || []
    messageTrend.months = items.map((i) => (i.bucket?.length === 10 ? i.bucket.slice(5) : i.bucket))
    messageTrend.message = items.map((i) => i.message)
    messageTrend.warn = items.map((i) => i.alarm)

    // 告警：后端字段为 snake_case，表格按 camelCase 绑定，这里做一次映射
    alarmList.value = (alarms.data?.items || []).map((a) => ({
      id: a.id,
      alarmType: a.alarm_type,
      level: a.level,
      ruleName: a.rule_name,
      productKey: a.product_key,
      deviceKey: a.device_key,
      status: a.status,
      alarmAt: a.alarm_at
    }))
    trendReady.value = true
  } catch (e) {
    // 拦截器已提示；保留空态渲染，不阻塞页面
  } finally {
    loading.value = false
  }
}

// 必须先取数再画图：否则 xAxis/series 拿到的是空数组，图表会是空白
onMounted(async () => {
  await loadDashboard()
  initTrend()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart && chart.dispose()
})
</script>

<style scoped>
.dashboard {
  height: 100%;
  overflow-y: auto;
  padding: 16px 24px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.page-tabs {
  display: flex;
}

.tab {
  font-size: 14px;
  padding: 6px 16px;
  border-radius: 6px;
  color: #6b7280;
  cursor: pointer;
}

.tab.active {
  background: #e6f4ff;
  color: #2563eb;
  font-weight: 500;
}

.stats-bar {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}

.stat-card {
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 10px;
  padding: 16px 18px;
}

.stat-main {
  display: flex;
  align-items: center;
  gap: 14px;
}

.stat-icon {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
}

.stat-num {
  font-size: 26px;
  font-weight: 600;
  color: #2563eb;
  line-height: 1.1;
}

.stat-label {
  font-size: 13px;
  color: #9ca3af;
  margin-top: 4px;
}

.stat-foot {
  display: flex;
  gap: 8px;
  margin-top: 14px;
}

.foot-item {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 5px;
  background: #f8fafc;
  border-radius: 6px;
  padding: 6px 10px;
  min-width: 0;
}

.foot-label {
  font-size: 12px;
  color: #9ca3af;
}

.foot-value {
  font-size: 13px;
  font-weight: 500;
  color: #374151;
}

.mid-grid {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 12px;
}

.panel {
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 10px;
  padding: 16px 18px;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  margin-bottom: 12px;
}

.trend-chart {
  height: 260px;
}

.panel-empty {
  height: 260px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.alarm-status {
  display: inline-block;
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 999px;
}

.alarm-status.done {
  background: #ecfdf5;
  color: #047857;
}

.alarm-status.todo {
  background: #fef3c7;
  color: #b45309;
}

@media (max-width: 1200px) {
  .stats-bar {
    grid-template-columns: repeat(2, 1fr);
  }

  .mid-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 768px) {
  .dashboard {
    padding: 12px;
  }

  .stats-bar {
    grid-template-columns: 1fr;
  }
}
</style>
