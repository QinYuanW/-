<template>
  <el-dialog v-model="visible" title="注册批次列表" width="780px">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="批次由后端从设备表推导（device_key = 前缀_时间戳_序号）"
      description="不再依赖浏览器本地存储 —— 换浏览器或清缓存都不会丢。设备被删除后，对应批次的台数会跟着变，所以这里显示的永远是「现在真实存在的设备」。"
      style="margin-bottom: 12px"
    />

    <div v-if="loading" class="empty-wrap">
      <el-empty description="加载中…" :image-size="70" />
    </div>
    <div v-else-if="!records.length" class="empty-wrap">
      <el-empty description="还没有批量注册记录" :image-size="80" />
    </div>
    <template v-else>
      <el-table :data="records" size="small" max-height="400">
        <el-table-column label="注册时间" width="160">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column prop="product_key" label="产品" min-width="110" show-overflow-tooltip />
        <el-table-column prop="name_prefix" label="名称前缀" width="110" />
        <el-table-column prop="count" label="数量" width="70" />
        <el-table-column label="设备 keys" min-width="200">
          <template #default="{ row }">
            <span class="keys-text">
              {{ (row.device_keys || []).slice(0, 2).join(', ') }}{{ row.device_keys.length > 2 ? ` … 等 ${row.device_keys.length} 个` : '' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="" width="90" align="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="copyKeys(row)">复制 keys</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 如实告知"没被算进批次"的设备数：否则用户拿总数一对，会发现少了几台却不知道去哪了 -->
      <div v-if="ungrouped > 0" class="ungrouped-hint">
        另有 <b>{{ ungrouped }}</b> 台设备是手工指定 device_key 注册的（不符合「前缀_时间戳_序号」命名），
        不会出现在批次里；设备总数 {{ deviceTotal }} 台。
      </div>
    </template>

    <template #footer>
      <el-button :loading="loading" @click="load">刷新</el-button>
      <el-button type="primary" @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listBatches } from '../api'
import { formatTime } from '../utils/export'

const visible = ref(false)
const loading = ref(false)
const records = ref([])
const ungrouped = ref(0)
const deviceTotal = ref(0)

async function load() {
  loading.value = true
  try {
    const res = await listBatches()
    const d = res.data || {}
    records.value = d.batches || []
    ungrouped.value = d.ungrouped || 0
    deviceTotal.value = d.deviceTotal || 0
  } catch (e) {
    records.value = []
  } finally {
    loading.value = false
  }
}

function open() {
  visible.value = true
  load()
}

/** 复制这一批的 device_keys —— 烧录/对比时真正要带走的就是这串 */
async function copyKeys(row) {
  const text = (row.device_keys || []).join('\n')
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success(`已复制 ${(row.device_keys || []).length} 个 device_key`)
  } catch {
    ElMessage.warning('剪贴板不可用，请手动选取表格中的 keys')
  }
}

defineExpose({ open })
</script>

<style scoped>
.empty-wrap {
  padding: 10px 0;
}

.keys-text {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: #4b5563;
}

.ungrouped-hint {
  margin-top: 10px;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.7;
}
</style>
