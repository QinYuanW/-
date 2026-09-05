<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-logo">
        <div class="logo-box">
          <el-icon :size="26"><Connection /></el-icon>
        </div>
        <h2>物联网 PaaS 控制台</h2>
        <p>设备管理 · 数据监控 · 远程控制</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="handleLogin">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" clearable />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>
        <el-button
          type="primary"
          class="login-btn"
          :loading="loading"
          @click="handleLogin"
        >
          登 录
        </el-button>
      </el-form>

      <div class="login-tip">
        默认账号 admin / admin123
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { login } from '../api'

const router = useRouter()
const formRef = ref()
const loading = ref(false)
const form = reactive({ username: 'admin', password: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  await formRef.value.validate()
  loading.value = true
  try {
    const res = await login(form.username, form.password)
    localStorage.setItem('iot_token', res.data.token)
    localStorage.setItem('iot_username', res.data.username)
    ElMessage.success('登录成功')
    router.push('/')
  } catch (e) {
    // 错误提示已由拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #eef4ff 0%, #f6f8fb 50%, #eef2f9 100%);
}

.login-card {
  width: 400px;
  background: #fff;
  border-radius: 14px;
  padding: 40px 36px 28px;
  box-shadow: 0 12px 40px rgba(30, 64, 175, 0.10);
  border: 1px solid #e5eaf2;
}

.login-logo {
  text-align: center;
  margin-bottom: 28px;
}

.logo-box {
  width: 58px;
  height: 58px;
  margin: 0 auto 12px;
  border-radius: 14px;
  background: linear-gradient(135deg, #3b82f6, #2563eb);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6px 16px rgba(59, 130, 246, 0.35);
}

.login-logo h2 {
  font-size: 19px;
  color: #1f2937;
}

.login-logo p {
  font-size: 12px;
  color: #9ca3af;
  margin-top: 6px;
}

.login-btn {
  width: 100%;
  margin-top: 4px;
  font-size: 15px;
  letter-spacing: 4px;
}

.login-tip {
  margin-top: 18px;
  text-align: center;
  font-size: 12px;
  color: #b0b8c4;
}
</style>
