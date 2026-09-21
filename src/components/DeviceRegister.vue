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
          <!-- 9.4：去掉 `allow-create` —— 设备必须挂在一个**已存在**的产品下，
               允许手输任意 product_key 只可能输入错、然后在提交时吃一个"产品不存在"。
               列表加载失败时给明确提示 + 重试，而不是留个能瞎输的口子。 -->
          <el-select
            v-model="form.product_key"
            filterable
            default-first-option
            :loading="productsLoading"
            placeholder="请选择产品"
            style="width: 100%"
          >
            <el-option
              v-for="p in products"
              :key="p.product_key"
              :label="`${p.name}（${p.product_key}）`"
              :value="p.product_key"
            />
          </el-select>
          <div v-if="productsFailed" class="product-warn">
            产品列表加载失败，请
            <el-button link type="primary" size="small" @click="loadProducts">重试</el-button>
          </div>
          <div v-else-if="!productsLoading && !products.length" class="product-warn">
            还没有任何产品，请先到「产品」页创建一个
          </div>
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
  // 9.3：不再写死演示产品 `p_demo_001` —— 换个环境或删掉该产品后，
  // 它会变成一个"看不见的错值"（下拉显示为空、提交却带着它）。
  // 现在留空，等产品列表加载后默认取第一项；列表为空就要求用户自己选。
  product_key: '',
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

// 9.4：产品列表的加载态与失败态 —— 失败时要明确告知并给重试，
// 而不是留一个"能手输任意 product_key"的口子（那条路只会输入错）
const productsLoading = ref(false)
const productsFailed = ref(false)

async function loadProducts() {
  productsLoading.value = true
  productsFailed.value = false
  try {
    const res = await listProducts()
    products.value = res.data.items || []
    // 默认选第一项，省掉"每次都要手动选一下"的机械操作；已有值（如二次打开）不动
    if (!form.product_key && products.value.length) {
      form.product_key = products.value[0].product_key
    }
  } catch (e) {
    products.value = []
    productsFailed.value = true
  } finally {
    productsLoading.value = false
  }
}

function openDialog() {
  dialogVisible.value = true
  loadProducts()
}

// 4.7：批次记录不再写 localStorage ——
// 后端 `GET /devices/batches` 直接从设备表推导（device_key = 前缀_时间戳_序号），
// 换浏览器/清缓存都不会"看起来丢了数据"。前端不再需要任何本地记账。

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

/* 9.4：产品下拉的辅助提示（加载失败 / 一个产品都没有）——
   替代原来"允许手输任意 key"的兜底：说清问题并给下一步动作 */
.product-warn {
  font-size: 12px;
  color: #d97706;
  line-height: 1.6;
}

.register-btn {
  width: 100%;
}
</style>
