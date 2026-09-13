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
          <el-menu-item index="/categories">
            <el-icon><FolderOpened /></el-icon>
            <span>产品分类</span>
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
          <el-breadcrumb separator="/" class="app-crumb">
            <el-breadcrumb-item>首页</el-breadcrumb-item>
            <el-breadcrumb-item v-for="c in crumbs" :key="c">{{ c }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <span class="user">
            <el-icon><UserFilled /></el-icon>
            {{ username }}
          </span>
          <el-button text @click="handleLogout">退出登录</el-button>
        </div>
      </el-header>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'

const route = useRoute()
const router = useRouter()

const activeMenu = computed(() => route.path)
const crumbs = computed(() => route.meta.crumb || [route.meta.title || '设备控制台'])
const username = localStorage.getItem('iot_username') || 'admin'

function handleMenuSelect(index) {
  if (index === 'grafana') {
    window.open('http://localhost:3000', '_blank')
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
  .app-crumb {
    display: none;
  }

  .app-header {
    padding: 0 12px;
  }
}
</style>
