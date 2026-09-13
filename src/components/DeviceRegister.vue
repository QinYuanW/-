<template>
  <div class="device-register" :class="{ inline: !showButton }">
    <el-button v-if="showButton" type="primary" class="register-btn" :icon="Plus" @click="openDialog">
      注册设备
    </el-button>

    <el-dialog v-model="dialogVisible" title="注册设备" width="520px" :close-on-click-modal="false">
      <el-form :model="form" label-width="90px" @submit.prevent>
        <el-form-item label="注册方式">
          <el-radio-group v-model="mode">
            <el-radio-button value="single">单个注册</el-radio-button>
            <el-radio-button value="batch">批量注册</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="产品" required>
          <el-select
            v-model="form.product_key"
            filterable
            allow-create
            default-first-option
            placeholder="选择产品，或输入 product_key"
            style="width: 100%"
          >
            <el-option
              v-for="p in products"
              :key="p.product_key"
              :label="`${p.name}（${p.product_key}）`"
              :value="p.product_key"
            />
          </el-select>
        </el-form-item>

        <template v-if="mode === 'single'">
          <el-form-item label="设备标识">
            <el-input v-model="form.device_key" placeholder="留空自动生成" />
          </el-form-item>
          <el-form-item label="设备名称">
            <el-input v-model="form.device_name" placeholder="留空取设备标识" />
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="名称前缀">
            <el-input v-model="form.name_prefix" placeholder="如 batch，留空 dev" />
          </el-form-item>
          <el-form-item label="数量">
            <el-input-number v-model="form.count" :min="1" :max="1000" />
          </el-form-item>
        </template>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">注册</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="resultVisible" title="注册成功" width="640px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="device_secret 仅本次显示，请立即保存！"
        description="设备连接 MQTT 时以 device_key 作为 ClientID/用户名、device_secret 作为密码，关闭后无法再查询。"
      />
      <div class="result-tools">
        <el-button size="small" :icon="CopyDocument" @click="copyAll">复制全部</el-button>
        <el-button size="small" :icon="Download" @click="exportSecrets">导出 CSV</el-button>
        <span class="result-count">共 {{ resultItems.length }} 台</span>
      </div>
      <el-table :data="resultItems" size="small" max-height="320">
        <el-table-column prop="device_key" label="device_key" min-width="170" show-overflow-tooltip />
        <el-table-column prop="device_secret" label="device_secret" min-width="190" show-overflow-tooltip />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="copyOne(row)">复制</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button type="primary" @click="resultVisible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, CopyDocument, Download } from '@element-plus/icons-vue'
import { registerDevice, registerBatch, listProducts } from '../api'
import { downloadCsv } from '../utils/export'
import { copyText } from '../utils/clipboard'

const emit = defineEmits(['registered'])

const props = defineProps({
  // 3.3：工具栏模式为 false 时不渲染自带按钮（由父组件触发 openDialog）
  showButton: { type: Boolean, default: true }
})

const dialogVisible = ref(false)
const resultVisible = ref(false)
const submitting = ref(false)
const mode = ref('single')
const products = ref([])
const form = reactive({
  product_key: 'p_demo_001',
  device_key: '',
  device_name: '',
  name_prefix: '',
  count: 10
})
const resultItems = ref([])

/** 复制单行：device_key + device_secret 以制表符分隔，可直接粘进 Excel 分列 */
async function copyOne(row) {
  const ok = await copyText(`${row.device_key}\t${row.device_secret}`)
  if (ok) ElMessage.success('已复制 key 与 secret')
  else ElMessage.error('复制失败，请手动选中复制')
}

async function copyAll() {
  const text = resultItems.value.map((r) => `${r.device_key}\t${r.device_secret}`).join('\n')
  const ok = await copyText(text)
  if (ok) ElMessage.success(`已复制 ${resultItems.value.length} 台设备的 key 与 secret`)
  else ElMessage.error('复制失败，请改用「导出 CSV」')
}

function exportSecrets() {
  if (!resultItems.value.length) return
  downloadCsv(
    `device_secrets_${new Date().toISOString().slice(0, 10)}.csv`,
    ['device_key', 'device_secret'],
    resultItems.value
  )
  ElMessage.success(`已导出 ${resultItems.value.length} 条凭据`)
}

async function loadProducts() {
  try {
    const res = await listProducts()
    products.value = res.data.items || []
  } catch (e) {
    products.value = [] // 加载失败仍可手输 product_key（allow-create）
  }
}

function openDialog() {
  dialogVisible.value = true
  loadProducts()
}

// 3.3 批次列表：批量注册成功后把批次信息写入本地记录（后端未建批次表）
function saveBatchRecord(items) {
  const keys = (items || []).map((i) => i.device_key)
  const record = {
    id: Date.now(),
    time: new Date().toLocaleString('zh-CN', { hour12: false }),
    product_key: form.product_key.trim(),
    name_prefix: form.name_prefix.trim() || 'dev',
    count: keys.length,
    keys
  }
  try {
    const list = JSON.parse(localStorage.getItem('iot_batch_records') || '[]')
    list.unshift(record)
    localStorage.setItem('iot_batch_records', JSON.stringify(list.slice(0, 50)))
  } catch (e) {
    // 本地记录写失败不影响注册主流程
  }
}

async function submit() {
  if (!form.product_key.trim()) {
    ElMessage.warning('请填写产品标识')
    return
  }
  submitting.value = true
  try {
    let items = []
    if (mode.value === 'single') {
      const payload = { product_key: form.product_key.trim() }
      if (form.device_key.trim()) payload.device_key = form.device_key.trim()
      if (form.device_name.trim()) payload.device_name = form.device_name.trim()
      const res = await registerDevice(payload)
      items = [res.data]
    } else {
      const payload = { product_key: form.product_key.trim() }
      if (form.name_prefix.trim()) payload.name_prefix = form.name_prefix.trim()
      payload.count = form.count
      const res = await registerBatch(payload)
      items = res.data.items || []
    }
    resultItems.value = items
    dialogVisible.value = false
    resultVisible.value = true
    if (mode.value === 'batch') saveBatchRecord(items)
    emit('registered')
  } catch (e) {
    // 错误提示已由 axios 拦截器统一处理
  } finally {
    submitting.value = false
  }
}

defineExpose({ openDialog })
</script>

<style scoped>
.device-register {
  margin-bottom: 10px;
}

.device-register.inline {
  margin-bottom: 0;
}

.result-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0 8px;
}

.result-count {
  margin-left: auto;
  font-size: 12px;
  color: #9ca3af;
}

.register-btn {
  width: 100%;
}
</style>
