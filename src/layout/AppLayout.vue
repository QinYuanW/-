<template>
  <el-container class="app-layout">
    <el-aside width="216px" class="app-aside">
      <div class="app-brand">
        <div class="brand-logo">
          <el-icon :size="18"><Connection /></el-icon>
        </div>
        <span>物联网 PaaS</span>
      </div>
      <el-menu class="app-menu" :default-active="activeMenu" @select="handleMenuSelect">
        <el-sub-menu index="device-mgmt">
          <template #title>
            <el-icon><Management /></el-icon>
            <span>设备管理</span>
          </template>
          <el-menu-item index="/dashboard">
            <el-icon><DataBoard /></el-icon>
            <span>仪表盘</span>
          </el-menu-item>
          <el-menu-item index="/products">
            <el-icon><Goods /></el-icon>
            <span>产品</span>
          </el-menu-item>
          <el-menu-item index="/">
            <el-icon><Odometer /></el-icon>
            <span>设备</span>
          </el-menu-item>
          <!-- E4 已落地：分组管理页放出菜单 -->
          <el-menu-item index="/categories">
            <el-icon><FolderOpened /></el-icon>
            <span>设备分组</span>
          </el-menu-item>
          <!-- E3 尾巴：规则管理页（此前规则只能进 MySQL 敲 SQL，有告警无配置入口） -->
          <el-menu-item index="/rules">
            <el-icon><Bell /></el-icon>
            <span>告警规则</span>
          </el-menu-item>
        </el-sub-menu>
        <el-sub-menu index="ops">
          <template #title>
            <el-icon><Monitor /></el-icon>
            <span>运维监控</span>
          </template>
          <el-menu-item index="grafana">
            <el-icon><DataAnalysis /></el-icon>
            <span>监控大盘</span>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>

    <el-container class="app-body">
      <el-header height="56px" class="app-header">
          <div class="header-left">
            <!-- 1.1：原先固定渲染「首页 / 设备管理 / xxx」——「首页」既不可点也没信息量，
                 而且全站都在设备管理下，两级前缀把顶栏一半空间吃掉了。
                 现在只渲染 meta.crumb（如「设备管理 / 设备」），层级仍在但不啰嗦。 -->
            <el-breadcrumb separator="/" class="app-crumb">
              <el-breadcrumb-item v-for="c in crumbs" :key="c">{{ c }}</el-breadcrumb-item>
            </el-breadcrumb>
            <!-- 1.3：窄屏把面包屑整个 display:none 掉，而 ≤900px 侧栏又只剩图标 ——
                 两者一叠加，页面上就再没有任何文字能回答"我在哪"。
                 所以窄屏保留**当前页标题**：一级信息不如路径，但比一片空白强得多。
                 （返回上层的入口由各详情页自己的「返回」按钮承担） -->
            <span class="crumb-compact">{{ compactTitle }}</span>
          </div>
          <div class="header-right">
            <!-- 1.4：原先只有「用户名 + 退出登录」，没有用户菜单（改密码 / 退出）。
                 用下拉把账号相关的动作收在一处，别在顶栏铺一排按钮。 -->
            <el-dropdown trigger="click" @command="onUserCommand">
              <span class="user user-trigger">
                <el-icon><UserFilled /></el-icon>
                <span>{{ username }}</span>
                <el-icon class="caret"><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item disabled>当前账号：{{ username }}</el-dropdown-item>
                  <el-dropdown-item command="password" divided>修改密码</el-dropdown-item>
                  <el-dropdown-item command="logout">退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
      </el-header>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>

    <!-- 1.4：修改密码 -->
    <el-dialog v-model="pwdVisible" title="修改密码" width="440px">
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="86px">
        <el-form-item label="原密码" prop="old_password">
          <el-input v-model="pwdForm.old_password" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码" prop="new_password">
          <el-input v-model="pwdForm.new_password" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirm">
          <el-input v-model="pwdForm.confirm" type="password" show-password autocomplete="new-password" @keyup.enter="doChangePassword" />
        </el-form-item>
      </el-form>
      <div class="pwd-tip">
        改完密码后当前登录不会掉线（token 是无状态的，服务端无法撤销已签发的票据）——
        下次登录请用新密码。
      </div>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingPwd" @click="doChangePassword">保存</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { changePassword } from '../api'

const route = useRoute()
const router = useRouter()

/**
 * 侧栏高亮项。
 *
 * 优先取 `route.meta.menu`：详情页的 path（`/devices/:key`、`/products/:key`）与任何菜单
 * index 都不相等，若只按 `route.path` 精确匹配，**进入详情页后侧栏会一个都不高亮**
 * —— 导航装作你没在任何地方，位置感直接丢失。
 * 其余页面的 path 与菜单 index 一致，回退到 `route.path` 即可。
 */
const activeMenu = computed(() => route.meta.menu || route.path)
const crumbs = computed(() => route.meta.crumb || [route.meta.title || '设备控制台'])

/** 1.3：窄屏显示的一级标题 —— 取面包屑最后一段（最具体的那一层） */
const compactTitle = computed(() => {
  const list = crumbs.value
  return list[list.length - 1] || '设备控制台'
})
const username = localStorage.getItem('iot_username') || 'admin'

  /**
   * Grafana 大盘地址。
   *
   * ⚠️ 不能只写 `http://localhost:3000` —— 那样打开的是 Grafana 的 **Home 欢迎页**
   * （"Welcome to Grafana" + 新手引导卡），用户会以为"监控大盘就长这样"。
   * 大盘本体在 `/d/<uid>` 上，这里直接指到它。
   * （后端 uid 定义见 monitoring/grafana/dashboards/iot-platform-main.json）
   */
  const GRAFANA_DASHBOARD_URL = 'http://localhost:3000/d/iot-platform-main'

  function handleMenuSelect(index) {
    if (index === 'grafana') {
      window.open(GRAFANA_DASHBOARD_URL, '_blank')
      return
    }
    router.push(index)
  }

async function handleLogout() {
  await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
  localStorage.removeItem('iot_token')
  localStorage.removeItem('iot_username')
  router.push('/login')
}

// ===== 1.4 用户菜单 / 修改密码 =====
const pwdVisible = ref(false)
const savingPwd = ref(false)
const pwdFormRef = ref()
const pwdForm = reactive({ old_password: '', new_password: '', confirm: '' })

const pwdRules = {
  old_password: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  new_password: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '新密码至少 6 位', trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      // 前端只做"两次输入一致"的即时反馈；真正的强度/原密码校验在后端
      validator: (rule, value, cb) => {
        if (value !== pwdForm.new_password) cb(new Error('两次输入的新密码不一致'))
        else cb()
      },
      trigger: 'blur'
    }
  ]
}

function onUserCommand(cmd) {
  if (cmd === 'password') {
    pwdForm.old_password = ''
    pwdForm.new_password = ''
    pwdForm.confirm = ''
    pwdVisible.value = true
  } else if (cmd === 'logout') {
    handleLogout()
  }
}

async function doChangePassword() {
  try {
    await pwdFormRef.value.validate()
  } catch {
    return   // 校验失败：Element 已在表单内联提示
  }
  savingPwd.value = true
  try {
    await changePassword(pwdForm.old_password, pwdForm.new_password)
    pwdVisible.value = false
    ElMessage.success('密码已修改，下次登录请使用新密码')
  } catch (e) {
    // 拦截器已提示（原密码错误 / 新密码太短 等）
  } finally {
    savingPwd.value = false
  }
}
</script>

<style scoped>
.app-layout {
  height: 100vh;
}

.app-aside {
  background: #fff;
  border-right: 1px solid #e4e9f0;
  display: flex;
  flex-direction: column;
}

.app-brand {
  height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  font-size: 15px;
  font-weight: 600;
  color: #1f2937;
  border-bottom: 1px solid #eef1f6;
  flex-shrink: 0;
}

.brand-logo {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: linear-gradient(135deg, #2563eb, #1d4ed8);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
}

.app-menu {
  border-right: none;
  padding: 8px;
  flex: 1;
  overflow-y: auto;
}

/* FastBee 式二级列：缩进、小图标、紧凑行高 */
.app-menu :deep(.el-menu-item) {
  position: relative;
  border-radius: 6px;
  margin-bottom: 2px;
  height: 38px;
  font-size: 13px;
}

.app-menu :deep(.el-menu-item .el-icon) {
  font-size: 15px;
}

.app-menu :deep(.el-menu-item.is-active) {
  background: #e6f4ff;
  color: #2563eb;
  font-weight: 600;
}

.app-menu :deep(.el-menu-item.is-active::before) {
  content: '';
  position: absolute;
  left: 0;
  top: 9px;
  bottom: 9px;
  width: 3px;
  border-radius: 3px;
  background: #2563eb;
}

.app-menu :deep(.el-menu-item:hover) {
  background: #f5f7fa;
}

/* 分组下一级项固定缩进，弱化 Element 默认递进 */
.app-menu :deep(.el-sub-menu .el-menu-item) {
  padding-left: 46px !important;
}

.app-menu :deep(.el-sub-menu__title) {
  height: 44px;
  border-radius: 6px;
  font-weight: 600;
  color: #4b5563;
}

.app-body {
  min-width: 0;
}

.app-header {
  background: #fff;
  border-bottom: 1px solid #e4e9f0;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.app-crumb {
  font-size: 13px;
}

/* 1.3：宽屏用面包屑（有完整路径），窄屏换成一級标题 —— 两者互斥，见下方断点 */
.crumb-compact {
  display: none;
  font-size: 14px;
  font-weight: 600;
  color: #1f2937;
}

.page-title {
  font-size: 15px;
  font-weight: 600;
  color: #1f2937;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.user {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: #4b5563;
}

/* 1.4：用户名变成可点下拉 —— 给它 hover/cursor 反馈，否则用户不知道能点 */
.user-trigger {
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 6px;
  outline: none;
  transition: background 0.15s;
}

.user-trigger:hover {
  background: #f3f6fb;
}

.user-trigger .caret {
  font-size: 12px;
  color: #9ca3af;
}

/* 1.4：改密码弹窗里的说明 */
.pwd-tip {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.7;
  margin-top: 4px;
}

.app-main {
  padding: 0;
  overflow: hidden;
  background: #f0f2f5;
}

/* G5 响应式：窄屏收起侧栏为图标栏，顶栏折叠次要信息 */
@media (max-width: 900px) {
  .app-aside {
    width: 64px !important;
  }

  .app-brand {
    justify-content: center;
    padding: 0;
  }

  .app-brand span,
  .app-menu :deep(.el-menu-item span),
  .app-menu :deep(.el-sub-menu__title span) {
    display: none;
  }

  .app-menu :deep(.el-menu-item),
  .app-menu :deep(.el-sub-menu__title) {
    justify-content: center;
    padding: 0 !important;
  }

  .header-right .user {
    display: none;
  }
}

@media (max-width: 768px) {
  /* 1.3：面包屑换成一級标题（而不是直接藏掉不留替代） */
  .app-crumb {
    display: none;
  }

  .crumb-compact {
    display: inline-block;
  }

  .app-header {
    padding: 0 12px;
  }
}
</style>
