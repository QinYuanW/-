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
          <!-- 2.3：不再预填 admin（与真实产品行为不符，还等于把默认凭据写在脸上）；
              交给浏览器 autocomplete 记住，用户下次一个点击就能填上 -->
          <el-input
            v-model="form.username"
            placeholder="用户名"
            :prefix-icon="User"
            autocomplete="username"
            clearable
          />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            :prefix-icon="Lock"
            autocomplete="current-password"
            show-password
          />
        </el-form-item>

        <!-- 2.2：登录失败内联显示 —— 用户视线在表单上，错误飘到屏幕右上角的 toast 里是找不到的 -->
        <el-alert
          v-if="loginError"
          class="login-error"
          :title="loginError"
          type="error"
          show-icon
          :closable="false"
        />

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
import { ref, reactive, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { login } from '../api'

const router = useRouter()
const formRef = ref()
const loading = ref(false)
// 2.3：用户名留空（原先预填 'admin' —— 与真实产品行为不符，还暗示了默认凭据）
const form = reactive({ username: '', password: '' })
// 2.2：登录失败的内联错误
const loginError = ref('')

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

// 用户一改动就清掉上次的错误，别让旧提示挂在那里误导
watch(() => [form.username, form.password], () => { loginError.value = '' })

async function handleLogin() {
  try {
    await formRef.value.validate()
  } catch {
    return   // 校验失败：Element 已在表单内联提示，这里只需中断提交
  }
  loading.value = true
  loginError.value = ''
  try {
    const res = await login(form.username, form.password)
    localStorage.setItem('iot_token', res.data.token)
    localStorage.setItem('iot_username', res.data.username)
    ElMessage.success('登录成功')
    router.push('/')
  } catch (e) {
    // 2.2：内联呈现（拦截器已按 silentError 关掉全局 toast，避免"两处都报同一个错"）
    loginError.value = e?.message || '登录失败，请稍后重试'
    // 光标落到密码框：多数失败是密码错，省掉用户一次点击
    nextTick(() => {
      const el = document.querySelector('.login-card input[type="password"]')
      if (el) el.focus()
    })
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
  width: min(400px, calc(100vw - 32px));
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

/* 2.2：登录失败的内联提示 —— 紧贴表单，别让用户去屏幕角落找错误 */
.login-error {
  margin-bottom: 14px;
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
