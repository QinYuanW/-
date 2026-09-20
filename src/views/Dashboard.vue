<template>
  <div class="dashboard">
    <!-- 3.6：当前只有「物联概览」一个页签，就别再摆成 tab 的样子 —— 一个点了没反应的 tab
         只会暗示"还有别的"。等真的加了第二个页签，再换回 el-tabs。
         3.2：右侧给「更新于 + 刷新」，让"这份数据有多新"一眼可见。 -->
    <div class="page-tabs">
      <span class="page-heading">物联概览</span>
      <div class="tabs-right">
        <span class="updated-at">{{ updatedText }}</span>
        <el-button
          link
          type="primary"
          size="small"
          :icon="Refresh"
          :loading="loading"
          @click="manualRefresh"
        >
          刷新
        </el-button>
      </div>
    </div>

    <!-- 3.1：卡片与脚注都可下钻 —— 看到「33 个产品」本能地想点进去，之前点不动 -->
    <div class="stats-bar">
      <div
        v-for="c in statCards"
        :key="c.key"
        class="stat-card"
        :class="{ clickable: !!c.go }"
        :title="c.tip || ''"
        @click="c.go && c.go()"
      >
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
          <el-tooltip
            v-for="f in c.foot"
            :key="f.label"
            :content="f.tip"
            :disabled="!f.tip"
            placement="top"
          >
            <div
              class="foot-item"
              :class="{ clickable: !!f.go }"
              @click.stop="f.go && f.go()"
            >
              <el-icon :size="13" :color="f.color"><component :is="f.icon" /></el-icon>
              <span class="foot-label">{{ f.label }}</span>
              <b class="foot-value">{{ f.value }}</b>
            </div>
          </el-tooltip>
        </div>
      </div>
    </div>

    <div class="mid-grid">
      <section id="panel-message" class="panel">
        <div class="panel-title">设备消息</div>
        <div ref="trendEl" class="trend-chart"></div>
      </section>

      <section class="panel">
        <div class="panel-title">告警类型</div>
        <div v-if="alarmTypes.length" ref="typeEl" class="type-chart"></div>
        <div v-else class="panel-empty">
          <el-empty description="暂无告警数据" :image-size="70" />
        </div>
      </section>
    </div>

    <!-- 3.3：旧版有、本次重做按参照图删掉的两个模块。
         参照图没有不代表不需要 —— 缺了它们就失去了"一键进最近在用的设备"这条捷径。 -->
    <div class="low-grid">
      <section class="panel">
        <div class="panel-title">
          最近活跃设备
          <el-button link type="primary" size="small" @click="router.push('/')">全部设备</el-button>
        </div>
        <el-table :data="recentDevices" size="small">
          <el-table-column label="设备" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">
              <a class="dev-link" @click="router.push(`/devices/${row.device_key}`)">
                {{ row.device_name || row.device_key }}
              </a>
            </template>
          </el-table-column>
          <el-table-column label="产品" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ productName(row.product_key) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <span class="mini-status" :class="row.online_status === 1 ? 'on' : 'off'">
                {{ row.online_status === 1 ? '在线' : '离线' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="最后在线" width="160">
            <template #default="{ row }">{{ formatTime(row.last_online_at) }}</template>
          </el-table-column>
          <template #empty>
            <el-empty description="暂无设备" :image-size="60" />
          </template>
        </el-table>
      </section>

      <section class="panel">
        <div class="panel-title">
          产品概览
          <el-button link type="primary" size="small" @click="router.push('/products')">全部产品</el-button>
        </div>
        <ul class="prod-list">
          <li
            v-for="p in productOverview"
            :key="p.product_key"
            @click="router.push(`/products/${p.product_key}`)"
          >
            <div class="pl-top">
              <span class="pl-name">{{ p.name }}</span>
              <span class="pl-key">{{ p.product_key }}</span>
              <span v-if="p.status === 0" class="mini-status off">已停用</span>
            </div>
            <div class="pl-meta">
              <span>{{ p.property_count }} 属性 · {{ p.event_count }} 事件 · {{ p.service_count }} 服务</span>
              <span class="pl-dev">{{ p.device_count }} 台设备</span>
            </div>
          </li>
          <li v-if="!productOverview.length" class="pl-empty">暂无产品</li>
        </ul>
      </section>
    </div>

    <section id="panel-alarm" class="panel">
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
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import {
  Box,
  Monitor,
  ChatDotRound,
  Bell,
  CircleCheckFilled,
  VideoPause,
  Calendar,
  Clock,
  Warning,
  Refresh
} from '@element-plus/icons-vue'
import { getStatsOverview, getMessageTrend, listAlerts, listDevices, listProducts } from '../api'
import { formatTime } from '../utils/export'

// ===== 实时数据（E6 接入，mock 已下线）=====
// 刻意保持与 mock 相同的结构，这样模板与 statCards / initTrend 的引用方式无需改动。

const overview = reactive({
  product: { total: 0, enabled: 0, disabled: 0 },
  device: { total: 0, online: 0, offline: 0, enabled: 0, disabled: 0, unavailable: 0, blocked_by_product: 0 },
  message: { total: 0, month: 0, today: 0 },
  alarm: { total: 0, month: 0, today: 0 }
})

const messageTrend = reactive({ months: [], message: [], warn: [] })
const alarmList = ref([])
const alarmTypes = ref([])      // 告警类型分布（取自 /alerts 的 types 段）
const loading = ref(false)
const typeEl = ref()
let typeChart = null

const router = useRouter()

// ===== 3.2 自动刷新 =====
// 之前只在 onMounted 拉一次：开着页面盯数据，数字永远不动，用户会以为"系统卡了"。
// 现在 30s 轮询 + 显式「更新于」时间戳，让"这份数据有多新"一眼可见。
const REFRESH_MS = 30000
const lastUpdated = ref(null)
let refreshTimer = null

const updatedText = computed(() => {
  if (!lastUpdated.value) return '加载中…'
  const p = (n) => String(n).padStart(2, '0')
  const d = lastUpdated.value
  return `更新于 ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
})

/** 自动刷新走静默模式：不闪 loading，否则页面每 30s 抖一下 */
function refreshTick() {
  loadDashboard({ silent: true })
}

function manualRefresh() {
  loadDashboard()
}

function startAutoRefresh() {
  stopAutoRefresh()
  refreshTimer = setInterval(() => {
    // 标签页不可见时不刷新：省请求，也免得切回来看到的是"暂停那一刻"的旧值
    if (document.visibilityState === 'visible') refreshTick()
  }, REFRESH_MS)
}

function stopAutoRefresh() {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = null
}

function onVisibilityChange() {
  // 回到前台立刻补一次，别让用户对着几分钟前的数字做判断
  if (document.visibilityState === 'visible') refreshTick()
}

/** 3.1 下钻：滚到本页某个区块（消息卡 / 告警卡没有独立列表页，落到对应面板最合适） */
function scrollToPanel(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

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
    // 3.1 下钻：产品卡 → 产品列表
    go: () => router.push('/products'),
    tip: '点击查看产品列表',
    // 产品只有「配置态」维度 —— 它没有"在线"概念，正常/已停用就是它的真实状态
    // 术语与配色统一（2026-09-15）：产品卡片用「已停用」，仪表盘此前叫「禁用」+ 琥珀色 ——
    // 同一个概念三个叫法、两种颜色，用户得自己翻译。现统一为「已停用」+ 红（#dc2626）。
    foot: [
      { icon: CircleCheckFilled, color: '#16a34a', label: '正常', value: overview.product.enabled },
      { icon: VideoPause, color: '#dc2626', label: '已停用', value: overview.product.disabled }
    ]
  },
  {
    key: 'device',
    label: '在线设备',
    value: overview.device.online,
    icon: Monitor,
    gradient: 'linear-gradient(135deg, #818cf8, #6366f1)',
    // 3.1 下钻：整卡 → 设备列表（最常用）；脚注再精确到在线 / 离线
    // （设备列表页本来就有状态筛选控件，带 query 过去控件会显示选中态 —— 用户看得见筛了什么）
    go: () => router.push('/'),
    tip: '点击查看设备列表',
    // 设备卡片以「运行态」为主：在线/离线是打开仪表盘第一眼想知道、且时刻在变的指标。
    // 配置态异常只在**确实存在时**追加，不占常规位置 ——
    // 这样既对齐 JetLinks 的信息优先级，又不会让"我停过哪台设备"静默消失。
    //
    // 口径修正（2026-09-15）：原来这里用的是 device.disabled（只看设备**自身** status），
    // 于是「产品被停用 → 整条产品线设备全废」这半边完全隐身：那批设备自身 status 仍是 1，
    // 既不计入 disabled、也不在离线里，仪表盘看不到任何异常（已实测复现）。
    // 现改用 device.unavailable = 自身停用 + 被产品牵连，并悬停给出拆解
    // —— 保留「是谁拖的」这一处置线索（前者去设备页启用，后者去产品页启用）。
    foot: [
      { icon: CircleCheckFilled, color: '#16a34a', label: '在线', value: overview.device.online,
        go: () => router.push({ path: '/', query: { status: '1' } }), tip: '只看在线设备' },
      { icon: VideoPause, color: '#f59e0b', label: '离线', value: overview.device.offline,
        go: () => router.push({ path: '/', query: { status: '2' } }), tip: '只看离线设备' },
      // 「不可用」刻意不给下钻：设备列表的状态筛选只支持在线/离线，
      // 而这里的不可用 = 自身停用 + 被产品牵连，跳过去反而筛不出来（点不动比点错好）
      ...(overview.device.unavailable > 0
        ? [{
            icon: Warning,
            color: '#dc2626',
            label: '不可用',
            value: overview.device.unavailable,
            tip: `设备自身停用 ${overview.device.disabled} 台 · 因产品停用牵连 ${overview.device.blocked_by_product} 台`
          }]
        : [])
    ]
  },
  {
    key: 'message',
    label: '设备消息',
    value: formatWan(overview.message.total),
    icon: ChatDotRound,
    gradient: 'linear-gradient(135deg, #fba97a, #f97316)',
    // 3.1 下钻：消息没有独立列表页，落到本页「设备消息」趋势图最合适
    go: () => scrollToPanel('panel-message'),
    tip: '定位到下方「设备消息」趋势',
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
    // 3.1 下钻：告警没有独立列表页，落到本页「告警消息」表
    go: () => scrollToPanel('panel-alarm'),
    tip: '定位到下方「告警消息」列表',
    foot: [
      { icon: Calendar, color: '#3b82f6', label: '本月', value: overview.alarm.month },
      { icon: Clock, color: '#fbbf24', label: '今日', value: overview.alarm.today }
    ]
  }
])

function initTrend() {
  if (!trendEl.value) return
  if (!chart) {
    chart = echarts.init(trendEl.value)
    window.addEventListener('resize', onResize)   // 只注册一次
  }
  // notMerge=true：桶数会随刷新变化，必须整体替换配置，否则新旧 series 会合并出错
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
  }, true)
}

function onResize() {
  chart && chart.resize()
  typeChart && typeChart.resize()
}

async function loadDashboard({ silent = false } = {}) {
  if (!silent) loading.value = true
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
    alarmTypes.value = alarms.data?.types || []
    trendReady.value = true
    lastUpdated.value = new Date()
    await renderCharts()
    // 3.3：最近活跃设备跟着 30s 刷新（会上下线、顺序会变）；
    // 产品概览只在进页面/手动刷新时取，理由见 loadProductOverview 的注释
    loadRecentDevices()
    if (!silent) loadProductOverview()
  } catch (e) {
    // 拦截器已提示；保留空态渲染，不阻塞页面
  } finally {
    if (!silent) loading.value = false
  }
}

/**
 * 图表渲染。抽出来是为了让「首次加载」与「30s 刷新」走同一条路径 ——
 * 否则刷新只更新数字、图还是旧的（这正是之前没有自动刷新时没人发现的原因）。
 */
async function renderCharts() {
  // v-if 的容器要等 DOM 更新后才存在（告警类型图在无数据时压根不渲染）
  await nextTick()
  initTrend()
  initTypeChart()
}

// ===== 3.3 补回旧版被删掉的两个模块 =====
// 本次仪表盘重做按参照图删掉了「最近活跃设备」和「产品概览」，用户因此失去了
// "一键进最近在用的设备"这条捷径 —— 参照图没有不代表不需要。
const recentDevices = ref([])
const productOverview = ref([])
/** 产品列表（供设备行显示产品名，避免每行再查一次） */
const productsForName = ref([])

const productName = (key) => {
  const p = productsForName.value.find((x) => x.product_key === key)
  return p ? p.name : key || '-'
}

async function loadRecentDevices() {
  try {
    // 用后端排序（评审 4.2 刚加的 sort 参数）—— 前端排的话只能排当前页
    const res = await listDevices(1, 5, '', '', 0, null, 'last_online_at', 'desc')
    recentDevices.value = res.data.items || []
  } catch {
    recentDevices.value = []
  }
}

/**
 * 产品概览。
 *
 * ⚠️ 只在**进页面 / 手动刷新**时取，不挂进 30s 自动刷新：每个产品都要单独查一次设备数
 * （后端没有"按产品聚合计数"的接口），跟着 30s 键太勤就是白打 N 个请求。
 * 而产品与物模型的构成本来也不会每 30 秒变一次 —— 真正需要实时的是「最近活跃设备」，那个挂了自动刷新。
 *
 * 设备数用 size=1 只读 total，避免把整页设备拉回来只为数个数。
 */
async function loadProductOverview() {
  try {
    const prods = (await listProducts()).data.items || []
    productsForName.value = prods
    const counts = await Promise.all(prods.map((p) =>
      listDevices(1, 1, '', p.product_key, 0, null)
        .then((r) => r.data.total || 0)
        .catch(() => 0)
    ))
    productOverview.value = prods.map((p, i) => ({
      ...p,
      property_count: (p.tsl?.properties || []).length,
      event_count: (p.tsl?.events || []).length,
      service_count: (p.tsl?.services || []).length,
      device_count: counts[i]
    }))
  } catch {
    productOverview.value = []
  }
}

/** 告警类型分布（环形图）—— 数据取自 /alerts 的 types 段 */
function initTypeChart() {
  if (!typeEl.value || !alarmTypes.value.length) return
  if (!typeChart) typeChart = echarts.init(typeEl.value)
  typeChart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
    legend: { bottom: 0, icon: 'circle', textStyle: { color: '#6b7280', fontSize: 12 } },
    color: ['#f09595', '#ef9f27', '#85b7eb', '#97c459'],
    series: [{
      type: 'pie',
      radius: ['46%', '68%'],
      center: ['50%', '44%'],
      avoidLabelOverlap: true,
      itemStyle: { borderColor: '#fff', borderWidth: 2 },
      label: { formatter: '{b} {c}', color: '#6b7280', fontSize: 11 },
      data: alarmTypes.value.map((t) => ({ name: t.name, value: Number(t.count) }))
    }]
  }, true)
}

// 必须先取数再画图：loadDashboard 内部取数后会 await renderCharts()，
// 否则 xAxis/series 拿到的是空数组，图表会是空白
onMounted(async () => {
  await loadDashboard()
  startAutoRefresh()
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  stopAutoRefresh()
  document.removeEventListener('visibilitychange', onVisibilityChange)
  window.removeEventListener('resize', onResize)
  chart && chart.dispose()
  typeChart && typeChart.dispose()
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
  align-items: center;
  justify-content: space-between;
}

/* 3.6：单页签时用普通标题，而不是"tab 外观" —— 一个点了没反应的 tab 只会暗示"还有别的" */
.page-heading {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
  padding: 6px 0;
}

.tabs-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.updated-at {
  font-size: 12px;
  color: #9ca3af;
  font-variant-numeric: tabular-nums; /* 秒数跳动时不左右晃 */
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

/* 3.1：只给"真有去处"的卡片加可点样式 —— 不可点的卡片保持原样，
   否则用户学会"卡片都能点"之后，点到不响应的卡片会以为坏了 */
.stat-card.clickable {
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.stat-card.clickable:hover {
  border-color: #c7d7f5;
  box-shadow: 0 4px 14px rgba(37, 99, 235, 0.1);
}

.foot-item.clickable {
  cursor: pointer;
  border-radius: 4px;
  transition: background 0.15s;
}

.foot-item.clickable:hover {
  background: #f3f7ff;
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
  /* 放不下时**整项**换到下一行，而不是把项内文字拦腰折断 —— 见 .foot-item 的 nowrap */
  flex-wrap: wrap;
  margin-top: 14px;
}

.foot-item {
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  gap: 5px;
  background: #f8fafc;
  border-radius: 6px;
  padding: 6px 8px;
  min-width: 0;
  /* 项内文字不折行：设备卡片可能有三项（在线/离线/已停用），窄了就折成两行很难看 */
  white-space: nowrap;
}

.foot-label {
  font-size: 12px;
  color: #9ca3af;
  /* 双保险：标签永不折行（曾出现「离\n线」「已停\n用」） */
  white-space: nowrap;
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

/* 告警类型环形图：与左侧趋势图同高，两栏视觉对齐 */
.type-chart {
  height: 260px;
}

.panel-empty {
  height: 260px;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* ===== 3.3：最近活跃设备 + 产品概览 ===== */
.low-grid {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: 12px;
}

/* 这两个面板的标题右侧各有一个「全部…」跳转入口 —— 只在 .low-grid 内改成两端对齐，
   不动上面「设备消息 / 告警类型」那两个纯文字标题 */
.low-grid .panel-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.dev-link {
  color: #2563eb;
  cursor: pointer;
}

.dev-link:hover {
  text-decoration: underline;
}

.mini-status {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 10px;
}

.mini-status.on {
  color: #047857;
  background: #ecfdf5;
}

.mini-status.off {
  color: #6b7280;
  background: #f1f5f9;
}

.prod-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.prod-list li {
  border: 1px solid #eef1f6;
  border-radius: 8px;
  padding: 9px 12px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.prod-list li:hover {
  border-color: #c7d7f5;
  background: #f8fbff;
}

.pl-top {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pl-name {
  font-size: 13px;
  font-weight: 600;
  color: #1f2937;
}

.pl-key {
  font-size: 12px;
  color: #9ca3af;
  font-family: Consolas, monospace;
}

.pl-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 4px;
  font-size: 12px;
  color: #6b7280;
}

.pl-dev {
  color: #2563eb;
  flex-shrink: 0;
}

.pl-empty {
  cursor: default;
  color: #9ca3af;
  font-size: 13px;
  text-align: center;
  padding: 18px 0;
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

  /* 3.3：窄屏时两个新面板也各占一整行 */
  .low-grid {
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

  .low-grid {
    grid-template-columns: 1fr;
  }
}
</style>
