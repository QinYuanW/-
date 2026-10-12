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

        <!-- 2.6：记住我 —— 勾选则后端签发 7 天有效期（默认只有 2 小时，频繁被踢回登录页） -->
        <div class="remember-row">
          <el-checkbox v-model="form.remember">记住我（7 天内免登录）</el-checkbox>
        </div>

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
/**
 * 「记住我」记住的用户名，**必须用独立的 key**。
 *
 * ⚠️ 一开始我复用了 `iot_username`，结果永远记不住 —— 因为顶栏的「退出登录」
 * 会 removeItem('iot_username')，一退出就把要记的东西自己清掉了。
 * `iot_username` 是**当前会话**的用户名，`iot_remember_user` 是**用户显式同意记住**的用户名，
 * 两者生命周期不同，不能共用一个 key。
 */
const REMEMBER_KEY = 'iot_remember_user'

/** 上次是否勾了「记住我」（勾过才有值）——这是用户显式同意过的，不违反 2.3 的"不预填" */
const rememberedUser = localStorage.getItem(REMEMBER_KEY) || ''

// 2.3：用户名不预填 admin（与真实产品行为不符，还暗示了默认凭据）；
// 只有用户自己勾过「记住我」才回填他上次的用户名
const form = reactive({ username: rememberedUser, password: '', remember: rememberedUser !== '' })
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
    const res = await login(form.username, form.password, form.remember)
    localStorage.setItem('iot_token', res.data.token)
    localStorage.setItem('iot_username', res.data.username)
    // 2.6：勾了就记住用户名，没勾就清掉 —— 不然下次又被带回来，等于偷偷预填。
    // 注意写的是 REMEMBER_KEY 而不是 iot_username（后者会被「退出登录」清掉）
    if (form.remember) localStorage.setItem(REMEMBER_KEY, form.username)
    else localStorage.removeItem(REMEMBER_KEY)
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

/* 2.6：记住我 —— 右对齐，与下面的主按钮形成"次要选项在上、主操作在下"的层次 */
.remember-row {
  display: flex;
  justify-content: flex-end;
  margin: -4px 0 14px;
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
