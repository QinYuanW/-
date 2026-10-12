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
          <el-button link size="small" @click="openDevices(g)">管理设备</el-button>
          <el-button link size="small" @click="openEdit(g)">编辑</el-button>
          <el-button link size="small" type="danger" @click="removeGroup(g)">删除</el-button>
        </div>
      </div>
    </div>

    <!-- 组内设备管理：从分组侧直接增删设备（设备侧也有「批量移入分组」，两个方向都要能走） -->
    <el-drawer v-model="drawerVisible" :title="`「${currentGroup?.name || ''}」的设备`" size="640px">
      <div class="drawer-head">
        <el-input
          v-model="deviceKeyword"
          class="drawer-search"
          placeholder="搜索 device_key / 设备名"
          clearable
          :prefix-icon="Search"
          @keyup.enter="loadGroupDevices"
          @clear="loadGroupDevices"
        />
        <el-button type="primary" :icon="Plus" @click="openAddDevices">添加设备</el-button>
      </div>

      <el-table v-loading="deviceLoading" :data="groupDevices" class="drawer-table" height="calc(100vh - 240px)">
        <el-table-column label="设备" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="dv-name">{{ row.device_name || row.device_key }}</div>
            <div class="dv-key">{{ row.device_key }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <span class="dv-st" :class="row.online_status === 1 ? 'on' : 'off'">
              {{ row.online_status === 1 ? '在线' : '离线' }}
            </span>
            <span v-if="row.status === 0" class="dv-st dis">已停用</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="right">
          <template #default="{ row }">
            <el-button link size="small" type="danger" @click="removeFromGroup(row)">移出</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!deviceLoading && !groupDevices.length" description="该分组下还没有设备" :image-size="70" />
    </el-drawer>

    <!-- 选择设备加入分组（列出未在该组的设备） -->
    <el-dialog v-model="addVisible" title="添加设备到分组" width="680px">
      <el-input
        v-model="addKeyword"
        class="add-search"
        placeholder="搜索 device_key / 设备名后回车"
        clearable
        :prefix-icon="Search"
        @keyup.enter="loadCandidates"
        @clear="loadCandidates"
      />
      <el-table
        ref="addTableRef"
        v-loading="addLoading"
        :data="candidates"
        class="add-table"
        height="380"
        @selection-change="(rows) => (pendingKeys = rows.map((r) => r.device_key))"
      >
        <el-table-column type="selection" width="44" />
        <el-table-column label="设备" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="dv-name">{{ row.device_name || row.device_key }}</div>
            <div class="dv-key">{{ row.device_key }}</div>
          </template>
        </el-table-column>
        <el-table-column label="当前分组" width="140">
          <template #default="{ row }">
            <span v-if="row.group_id" class="dv-key">其他分组</span>
            <span v-else class="dv-key">未分组</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="add-tip">已在该分组中的设备不会出现；选中后移入会覆盖其原分组归属。</div>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="adding" :disabled="!pendingKeys.length" @click="confirmAdd">
          加入该分组{{ pendingKeys.length ? `（${pendingKeys.length}）` : '' }}
        </el-button>
      </template>
    </el-dialog>

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
import { Search, Plus } from '@element-plus/icons-vue'
import {
  listDeviceGroups,
  createDeviceGroup,
  updateDeviceGroup,
  deleteDeviceGroup,
  listDevices,
  assignDevicesToGroup
} from '../api'

const router = useRouter()

const groups = ref([])
const ungrouped = ref(0)
const loading = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const form = ref({ id: null, name: '', description: '', sortOrder: 0 })

// ── 组内设备管理（抽屉）──
const drawerVisible = ref(false)
const currentGroup = ref(null)
const groupDevices = ref([])
const deviceLoading = ref(false)
const deviceKeyword = ref('')

// ── 添加设备（对话框）──
const addVisible = ref(false)
const candidates = ref([])
const addLoading = ref(false)
const addKeyword = ref('')
const pendingKeys = ref([])
const addTableRef = ref()
const adding = ref(false)

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

// ─────────── 组内设备管理（分组侧入口）───────────

/** 打开某分组的设备抽屉 */
function openDevices(g) {
  currentGroup.value = g
  deviceKeyword.value = ''
  drawerVisible.value = true
  loadGroupDevices()
}

/** 该分组下的设备（后端按 group_id 过滤） */
async function loadGroupDevices() {
  if (!currentGroup.value) return
  deviceLoading.value = true
  try {
    const res = await listDevices(1, 200, deviceKeyword.value, '', 0, currentGroup.value.id)
    groupDevices.value = res.data.items || []
  } catch {
    groupDevices.value = []
  } finally {
    deviceLoading.value = false
  }
}

/**
 * 候选设备 = 全量里**排除已在本组的**。
 * 后端没有「group_id != X」这种筛选参数，所以在前端做差集（设备量级可控）。
 */
async function loadCandidates() {
  addLoading.value = true
  try {
    const res = await listDevices(1, 200, addKeyword.value, '', 0, null)
    const inGroup = new Set(groupDevices.value.map((d) => d.device_key))
    candidates.value = (res.data.items || []).filter((d) => !inGroup.has(d.device_key))
  } catch {
    candidates.value = []
  } finally {
    addLoading.value = false
  }
}

function openAddDevices() {
  pendingKeys.value = []
  addKeyword.value = ''
  addVisible.value = true
  loadCandidates()
}

async function confirmAdd() {
  if (!pendingKeys.value.length) return
  adding.value = true
  try {
    const res = await assignDevicesToGroup(currentGroup.value.id, pendingKeys.value)
    ElMessage.success(`已加入 ${res.data.affected} 台设备`)
    addVisible.value = false
    // 分组卡片上的计数也要跟着刷新
    await Promise.all([loadGroupDevices(), load()])
  } catch (e) {
    // 拦截器已提示
  } finally {
    adding.value = false
  }
}

/** 把设备移出分组（id=0 即「未分组」） */
async function removeFromGroup(row) {
  try {
    await assignDevicesToGroup(0, [row.device_key])
    ElMessage.success('已移出该分组（设备本身未被删除）')
    await Promise.all([loadGroupDevices(), load()])
  } catch (e) {
    // 拦截器已提示
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
/* ─── 组内设备抽屉 / 添加设备对话框 ─── */
.drawer-head {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}

.drawer-search {
  flex: 1;
}

.drawer-table {
  width: 100%;
}

.dv-name {
  font-size: 13px;
  font-weight: 500;
  color: #1f2937;
}

.dv-key {
  font-size: 12px;
  font-family: Consolas, monospace;
  color: #9ca3af;
}

.dv-st {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 999px;
  white-space: nowrap;
}

.dv-st.on {
  color: #047857;
  background: #ecfdf5;
}

.dv-st.off {
  color: #6b7280;
  background: #f3f4f6;
}

/* 与在线状态并排展示的「已停用」—— 两维度，别混为一谈 */
.dv-st.dis {
  margin-left: 4px;
  color: #ba7517;
  background: #faeeda;
}

.add-search {
  margin-bottom: 10px;
}

.add-table {
  width: 100%;
}

.add-tip {
  margin-top: 10px;
  font-size: 12px;
  color: #9ca3af;
  line-height: 1.6;
}

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
