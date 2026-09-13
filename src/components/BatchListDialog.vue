<template>
  <el-dialog v-model="visible" title="注册批次列表" width="720px">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="批次记录保存在浏览器本地（localStorage）"
      description="后端未建批次表，记录仅用于演示回顾；设备本身可随时在设备列表中查询。"
      style="margin-bottom: 12px"
    />
    <div v-if="!records.length" class="empty-wrap">
      <el-empty description="还没有批量注册记录" :image-size="80" />
    </div>
    <el-table v-else :data="records" size="small" max-height="400">
      <el-table-column label="注册时间" width="150">
        <template #default="{ row }">{{ row.time }}</template>
      </el-table-column>
      <el-table-column prop="product_key" label="产品" min-width="110" show-overflow-tooltip />
      <el-table-column prop="name_prefix" label="名称前缀" width="100" />
      <el-table-column prop="count" label="数量" width="70" />
      <el-table-column label="设备 keys" min-width="200">
        <template #default="{ row }">
          <span class="keys-text">{{ (row.keys || []).slice(0, 3).join(', ') }}{{ row.keys.length > 3 ? ` … 等 ${row.keys.length} 个` : '' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="" width="80" align="right">
        <template #default="{ row }">
          <el-button size="small" text type="danger" @click="removeRecord(row.id)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <el-button v-if="records.length" @click="clearAll">清空记录</el-button>
      <el-button type="primary" @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'

const STORAGE_KEY = 'iot_batch_records'

const visible = ref(false)
const records = ref([])

function load() {
  try {
    records.value = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]')
  } catch {
    records.value = []
  }
}

function open() {
  load()
  visible.value = true
}

async function removeRecord(id) {
  records.value = records.value.filter((r) => r.id !== id)
  save()
}

async function clearAll() {
  await ElMessageBox.confirm('确定清空全部批次记录吗？', '提示', { type: 'warning' })
  records.value = []
  save()
  ElMessage.success('批次记录已清空')
}

function save() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(records.value.slice(0, 50)))
}

defineExpose({ open })
onMounted(load)
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
</style>
