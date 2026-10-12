<template>
  <div class="debug-log">
    <div class="dl-head">
      <span class="dl-title">调试日志</span>
      <el-button size="small" text :icon="Delete" :disabled="!logs.length" @click="$emit('clear')">
        清空
      </el-button>
    </div>

    <div class="dl-body">
      <el-empty v-if="!logs.length" description="暂无调试记录" :image-size="60" />
      <div v-for="l in logs" :key="l.id" class="dl-item">
        <div class="dl-meta">
          <span class="dl-time">{{ l.time }}</span>
          <el-tag size="small" effect="plain" :type="l.actionType || 'info'">{{ l.action }}</el-tag>
          <el-tag v-if="l.status" size="small" effect="plain" :type="l.statusType || 'info'">
            {{ l.status }}
          </el-tag>
        </div>
        <pre v-if="l.request" class="dl-json">{{ fmt(l.request) }}</pre>
        <pre v-if="l.response" class="dl-json dl-resp">{{ fmt(l.response) }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup>
import { Delete } from '@element-plus/icons-vue'

defineProps({
  // 条目：{ id, time, action, actionType, status, statusType, request, response }
  // request / response 传对象或字符串均可，组件只负责展示报文原文
  logs: { type: Array, default: () => [] }
})

defineEmits(['clear'])

function fmt(obj) {
  try {
    return typeof obj === 'string' ? obj : JSON.stringify(obj, null, 2)
  } catch {
    return String(obj)
  }
}
</script>

<style scoped>
.debug-log {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.dl-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  flex-shrink: 0;
}

.dl-title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
}

.dl-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.dl-item {
  border: 1px solid #eef1f6;
  border-radius: 8px;
  padding: 10px 12px;
  background: #fbfcfe;
}

.dl-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.dl-time {
  font-size: 12px;
  color: #9ca3af;
  font-family: Consolas, monospace;
}

.dl-json {
  margin: 0;
  font-family: Consolas, Monaco, monospace;
  font-size: 11.5px;
  line-height: 1.6;
  color: #374151;
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 6px;
  padding: 8px 10px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 180px;
  overflow: auto;
}

.dl-resp {
  margin-top: 6px;
  background: #f8fafc;
}
</style>
