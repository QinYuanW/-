<template>
  <div class="groups">
    <div class="groups-head">
      <div>
        <div class="groups-title">设备分组</div>
        <div class="groups-sub">按运维维度批量组织设备；分组与「产品」（设备的能力定义）正交</div>
      </div>
      <el-button type="primary" @click="openCreate">新建分组</el-button>
    </div>

    <div class="stat-row">
      <div class="stat-box">
        <div class="stat-label">分组总数</div>
        <div class="stat-num">{{ groups.length }}</div>
      </div>
      <div class="stat-box">
        <div class="stat-label">已分组设备</div>
        <div class="stat-num">{{ groupedCount }}</div>
      </div>
      <div class="stat-box">
        <div class="stat-label">未分组设备</div>
        <div class="stat-num">{{ ungrouped }}</div>
      </div>
    </div>

    <div v-loading="loading" class="group-grid">
      <div class="group-card is-ungrouped" @click="goDevices(0)">
        <div class="gc-head">
          <span class="gc-name">未分组</span>
          <el-tag size="small" type="info" effect="plain">{{ ungrouped }}</el-tag>
        </div>
        <div class="gc-desc">尚未归入任何分组的设备</div>
        <div class="gc-foot">点击查看设备 →</div>
      </div>

      <div v-for="g in groups" :key="g.id" class="group-card" @click="goDevices(g.id)">
        <div class="gc-head">
          <span class="gc-name">{{ g.name }}</span>
          <el-tag size="small" effect="plain">{{ g.deviceCount }}</el-tag>
        </div>
        <div class="gc-desc">{{ g.description || '（无描述）' }}</div>
        <div class="gc-actions" @click.stop>
          <el-button link size="small" @click="openEdit(g)">编辑</el-button>
          <el-button link size="small" type="danger" @click="removeGroup(g)">删除</el-button>
        </div>
      </div>
    </div>

    <el-empty
      v-if="!loading && !groups.length"
      class="grid-empty"
      description="还没有分组，点右上角新建一个"
      :image-size="80"
    />

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑分组' : '新建分组'" width="440px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="64" placeholder="如：一楼温控" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" maxlength="255" placeholder="选填" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listDeviceGroups,
  createDeviceGroup,
  updateDeviceGroup,
  deleteDeviceGroup
} from '../api'

const router = useRouter()

const groups = ref([])
const ungrouped = ref(0)
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const form = ref({ id: null, name: '', description: '', sortOrder: 0 })

const groupedCount = computed(() =>
  groups.value.reduce((sum, g) => sum + Number(g.deviceCount || 0), 0)
)

async function load() {
  loading.value = true
  try {
    const res = await listDeviceGroups()
    groups.value = res.data?.items || []
    ungrouped.value = res.data?.ungrouped || 0
  } catch {
    groups.value = []
    ungrouped.value = 0
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = { id: null, name: '', description: '', sortOrder: 0 }
  dialogVisible.value = true
}

function openEdit(g) {
  form.value = {
    id: g.id,
    name: g.name,
    description: g.description || '',
    sortOrder: g.sortOrder == null ? 0 : g.sortOrder
  }
  dialogVisible.value = true
}

async function save() {
  if (!form.value.name || !form.value.name.trim()) {
    ElMessage.warning('分组名称不能为空')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name.trim(),
      description: form.value.description,
      sortOrder: form.value.sortOrder
    }
    if (form.value.id) {
      await updateDeviceGroup(form.value.id, payload)
      ElMessage.success('已保存')
    } else {
      await createDeviceGroup(payload)
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeGroup(g) {
  try {
    await ElMessageBox.confirm(
      `删除分组「${g.name}」后，组内 ${g.deviceCount} 台设备会回到「未分组」（设备不会被删除）。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await deleteDeviceGroup(g.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '删除失败')
  }
}

// 点卡片跳到设备列表并按该分组筛选（0 = 未分组）
// ⚠️ 设备列表的路由是 `/`（name: 'console'），**没有 /devices** —— 用 name 跳转避免路径写错
function goDevices(groupId) {
  router.push({ name: 'console', query: { group_id: groupId } })
}

onMounted(load)
</script>

<style scoped>
.groups {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 24px;
  overflow-y: auto;
}

.groups-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.groups-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.groups-sub {
  margin-top: 4px;
  font-size: 12px;
  color: #9ca3af;
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.stat-box {
  background: #fff;
  border: 1px solid #e4e9f0;
  border-radius: 10px;
  padding: 14px 16px;
}

.stat-label {
  font-size: 12px;
  color: #9ca3af;
}

.stat-num {
  margin-top: 6px;
  font-size: 24px;
  font-weight: 600;
  color: #1f2937;
  line-height: 1;
}

.group-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
  min-height: 120px;
}

.group-card {
  background: #fff;
  border: 1px solid #e4e9f0;
  border-radius: 10px;
  padding: 14px 16px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.group-card:hover {
  border-color: #85b7eb;
}

.group-card.is-ungrouped {
  background: #fafbfc;
  border-style: dashed;
}

.gc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.gc-name {
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
}

.gc-desc {
  margin-top: 8px;
  font-size: 12px;
  color: #9ca3af;
  min-height: 32px;
  line-height: 1.6;
}

.gc-foot {
  font-size: 12px;
  color: #378add;
}

.gc-actions {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #eef1f5;
  display: flex;
  gap: 8px;
}

.grid-empty {
  grid-column: 1 / -1;
}

@media (max-width: 900px) {
  .groups {
    padding: 12px;
  }

  .stat-row {
    grid-template-columns: 1fr;
  }
}
</style>
