<template>
  <div class="shadow-panel" v-loading="loading">
    <div class="sp-toolbar">
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      <el-button
        size="small"
        type="primary"
        :loading="saving"
        :disabled="!dirtyKeys.length"
        @click="saveDesired"
      >
        保存期望值{{ dirtyKeys.length ? `（${dirtyKeys.length} 项改动）` : '' }}
      </el-button>
      <el-button size="small" type="danger" plain @click="resetShadow">清空影子</el-button>
    </div>
    <p class="sp-hint">
      期望值 = 你希望设备达到的状态；上报值 = 设备实际回报。
      不一致的属性会持续出现在 delta 中，设备上线时平台会自动对齐（离线时挂起、上线补发）。
    </p>

    <el-table :data="rows" size="small" empty-text="该产品暂无属性">
      <el-table-column label="属性" min-width="130">
        <template #default="{ row }">
          {{ row.name }}
          <small class="sp-key">{{ row.key }}</small>
        </template>
      </el-table-column>
      <el-table-column label="期望值 desired" min-width="160">
        <template #default="{ row }">
          <el-switch
            v-if="row.writable && row.type === 'bool'"
            v-model="draft[row.key]"
            @change="touch(row.key)"
          />
          <el-input-number
            v-else-if="row.writable && (row.type === 'int' || row.type === 'float')"
            v-model="draft[row.key]"
            size="small"
            :step="row.type === 'int' ? 1 : 0.1"
            style="width: 130px"
            @change="touch(row.key)"
          />
          <el-input
            v-else-if="row.writable"
            v-model="draft[row.key]"
            size="small"
            style="width: 140px"
            @input="touch(row.key)"
          />
          <span v-else class="sp-dash">-</span>
        </template>
      </el-table-column>
      <el-table-column label="上报值 reported" min-width="110">
        <template #default="{ row }">
          <span v-if="row.reported === undefined" class="sp-dash">未上报</span>
          <code v-else>{{ row.reported }}{{ row.unit || '' }}</code>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag size="small" :type="row.tagType" effect="plain">{{ row.tagText }}</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="lastResult" class="sp-result" :class="lastResult.cls">{{ lastResult.text }}</div>

    <div class="sp-pending">
      <div class="sp-pending-title">
        待下发队列（离线指令）
        <el-tag size="small" :type="pending.length ? 'warning' : 'info'" effect="plain">
          {{ pending.length }}
        </el-tag>
      </div>
      <p v-if="!pending.length" class="sp-hint">
        无挂起指令。设备离线时下发的指令会挂起在这里，设备上线后自动补发（E1）。
      </p>
      <div v-for="c in pending" :key="c.request_id" class="sp-pending-row">
        <code class="sp-rid">{{ c.request_id.slice(0, 8) }}</code>
        <span class="sp-payload">{{ c.payload }}</span>
        <span class="sp-time">{{ (c.created_at || '').replace('T', ' ').slice(0, 19) }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getShadow, setShadowDesired, clearShadow, listPendingCommands } from '../api'

const props = defineProps({
  deviceKey: { type: String, required: true },
  tsl: { type: Object, default: null }
})

const loading = ref(false)
const saving = ref(false)
const reported = ref({})
const pending = ref([])
const lastResult = ref(null)
const draft = reactive({})   // 期望值草稿（可写属性）
const dirty = reactive({})   // 用户改过的属性
let timer = null

const writableProps = computed(() =>
  (props.tsl?.properties || []).filter((p) => p.access === 'readwrite')
)

const dirtyKeys = computed(() => Object.keys(dirty))

const rows = computed(() =>
  (props.tsl?.properties || []).map((p) => {
    const d = draft[p.key]
    const r = reported.value[p.key]
    let tagText = '仅上报'
    let tagType = 'info'
    if (d !== undefined && d !== null && d !== '') {
      if (r === undefined) {
        tagText = '待设备达成'
        tagType = 'warning'
      } else if (String(d) === String(r)) {
        tagText = '一致'
        tagType = 'success'
      } else {
        tagText = '不一致'
        tagType = 'danger'
      }
    }
    return {
      key: p.key,
      name: p.name,
      type: p.type,
      unit: p.unit,
      writable: p.access === 'readwrite',
      reported: r,
      tagText,
      tagType
    }
  })
)

function touch(key) {
  dirty[key] = true
}

async function load() {
  if (!props.deviceKey) return
  loading.value = true
  try {
    const [s, p] = await Promise.all([
      getShadow(props.deviceKey),
      listPendingCommands(props.deviceKey)
    ])
    const desired = s.data.desired || {}
    reported.value = s.data.reported || {}
    pending.value = p.data.items || []

    // 草稿同步：用户没改过的键跟随云端 desired
    for (const prop of props.tsl?.properties || []) {
      if (prop.key in dirty) continue
      if (prop.access !== 'readwrite') continue
      draft[prop.key] = desired[prop.key] ?? (prop.type === 'bool' ? false : undefined)
    }
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function saveDesired() {
  const payload = {}
  for (const k of dirtyKeys.value) payload[k] = draft[k]
  if (!Object.keys(payload).length) return
  saving.value = true
  try {
    const res = await setShadowDesired(props.deviceKey, payload)
    const st = res.data.command_status
    if (st === 1) {
      lastResult.value = { cls: 'ok', text: '期望值已保存，差异指令已下发' }
    } else if (st === 0) {
      lastResult.value = {
        cls: 'warn',
        text: '期望值已保存；设备离线，差异指令已挂起，设备上线后自动补发（E1）'
      }
    } else {
      lastResult.value = {
        cls: 'warn',
        text: '期望值已保存' + (res.data.dispatch_error ? `，下发失败：${res.data.dispatch_error}` : '（无差异）')
      }
    }
    Object.keys(dirty).forEach((k) => delete dirty[k])
    await load()
  } catch (e) {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function resetShadow() {
  try {
    await ElMessageBox.confirm('清空该设备的影子（期望值与上报值都会被删除）？', '提示', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await clearShadow(props.deviceKey)
    ElMessage.success('影子已清空')
    Object.keys(dirty).forEach((k) => delete dirty[k])
    Object.keys(draft).forEach((k) => delete draft[k])
    await load()
  } catch (e) {
    // 拦截器已提示
  }
}

watch(
  () => props.deviceKey,
  () => {
    Object.keys(dirty).forEach((k) => delete dirty[k])
    Object.keys(draft).forEach((k) => delete draft[k])
    lastResult.value = null
    load()
  },
  { immediate: true }
)

onMounted(() => {
  timer = setInterval(load, 5000)
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.shadow-panel {
  min-height: 120px;
}
.sp-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
}
.sp-hint {
  font-size: 12px;
  color: #9ca3af;
  margin: 8px 0 10px;
  line-height: 1.6;
}
.sp-key {
  display: block;
  color: #9ca3af;
  font-size: 11px;
  font-family: Consolas, monospace;
}
.sp-dash {
  color: #c0c4cc;
  font-size: 12px;
}
.sp-result {
  margin-top: 10px;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 12px;
}
.sp-result.ok {
  background: #f0fdf4;
  color: #166534;
}
.sp-result.warn {
  background: #fffbeb;
  color: #92400e;
}
.sp-pending {
  margin-top: 14px;
  border-top: 1px dashed #eef1f6;
  padding-top: 10px;
}
.sp-pending-title {
  font-size: 13px;
  font-weight: 600;
  color: #374151;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.sp-pending-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  padding: 5px 0;
  border-bottom: 1px dashed #f3f4f6;
}
.sp-rid {
  color: #6b7280;
  font-family: Consolas, monospace;
}
.sp-payload {
  flex: 1;
  color: #374151;
  font-family: Consolas, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sp-time {
  color: #9ca3af;
}
</style>
