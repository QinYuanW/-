<template>
  <div class="device-register">
    <el-button type="primary" class="register-btn" :icon="Plus" @click="openDialog">
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

        <el-form-item label="产品标识" required>
          <el-input v-model="form.product_key" placeholder="如 p_demo_001" />
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

    <el-dialog v-model="resultVisible" title="注册成功" width="600px">
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="device_secret 仅本次显示，请立即保存！"
        description="设备连接 MQTT 时以 device_key 作为 ClientID/用户名、device_secret 作为密码，关闭后无法再查询。"
      />
      <el-table :data="resultItems" size="small" max-height="320" style="margin-top: 12px">
        <el-table-column prop="device_key" label="device_key" min-width="180" show-overflow-tooltip />
        <el-table-column prop="device_secret" label="device_secret" min-width="200" show-overflow-tooltip />
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
import { Plus } from '@element-plus/icons-vue'
import { registerDevice, registerBatch } from '../api'

const emit = defineEmits(['registered'])

const dialogVisible = ref(false)
const resultVisible = ref(false)
const submitting = ref(false)
const mode = ref('single')
const form = reactive({
  product_key: 'p_demo_001',
  device_key: '',
  device_name: '',
  name_prefix: '',
  count: 10
})
const resultItems = ref([])

function openDialog() {
  dialogVisible.value = true
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
    emit('registered')
  } catch (e) {
    // 错误提示已由 axios 拦截器统一处理
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.device-register {
  margin-bottom: 10px;
}

.register-btn {
  width: 100%;
}
</style>
