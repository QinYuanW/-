// ============================================================
// 仪表盘演示数据（MOCK）
// ------------------------------------------------------------
// ⚠️ 临时数据源，仅用于前端先行验证「物联概览」版式。
//    后端落地后删除本文件，改接（契约见 docs 前端规划 3.10）：
//      GET /api/v1/stats/overview       → overview
//      GET /api/v1/stats/message-trend  → messageTrend
//      GET /api/v1/alerts               → alarmList
//    真数据口径：
//      product.total/enabled/disabled → product.status 计数
//      device.online/enabled/disabled → device.online_status / status 计数
//      message.total/month/today      → device_telemetry 按 ts 计数
//      alarm.*                        → device_log.log_type = 5
// ============================================================

export const overview = {
  product: { total: 33, enabled: 15, disabled: 18 },
  device: { total: 4, online: 4, enabled: 12, disabled: 11 },
  message: { total: 12079200, month: 592800, today: 39900 },
  alarm: { total: 0, month: 0, today: 0 }
}

export const messageTrend = {
  months: ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12'],
  message: [1150000, 1120000, 1290000, 1420000, 1560000, 1350000, 1514209, 620000, 98000, 0, 0, 0],
  warn: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
}

export const alarmList = [
  {
    id: 1,
    alarmType: '规则告警',
    level: '严重',
    ruleName: '客厅温度超限告警',
    productKey: 'p_demo_001',
    deviceKey: 'dev_demo_001',
    status: '已处理',
    alarmAt: '2026-09-12 14:23:18'
  },
  {
    id: 2,
    alarmType: '规则告警',
    level: '一般',
    ruleName: '设备离线超 5 分钟',
    productKey: 'p_demo_001',
    deviceKey: 'dev_demo_007',
    status: '未处理',
    alarmAt: '2026-09-12 16:05:44'
  },
  {
    id: 3,
    alarmType: '系统告警',
    level: '提示',
    ruleName: '属性上报频率异常',
    productKey: 'p_demo_001',
    deviceKey: 'dev_demo_012',
    status: '已处理',
    alarmAt: '2026-09-11 09:41:02'
  }
]
