<template>
  <div class="nf">
    <div class="nf-card">
      <div class="nf-code">404</div>
      <div class="nf-title">找不到这个页面</div>
      <p class="nf-desc">地址 <span class="mono">{{ path }}</span> 不存在，或者它对应的资源已经被删除了。</p>
      <div class="nf-actions">
        <el-button type="primary" @click="router.push('/')">回到设备列表</el-button>
        <el-button @click="router.back()">返回上一页</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const path = computed(() => route.fullPath)
</script>

<style scoped>
/* 404 放在布局壳**里面**（而不是一个独立整页）：
   用户走错路时，左侧导航还在，可以直接点去别处 —— 比"一片空白只能改地址栏"友好得多。 */
.nf {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.nf-card {
  text-align: center;
  background: #fff;
  border: 1px solid #e5eaf2;
  border-radius: 12px;
  padding: 40px 48px;
  max-width: 520px;
}

.nf-code {
  font-size: 56px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: 2px;
  color: #c7d7f5;
}

.nf-title {
  margin-top: 12px;
  font-size: 17px;
  font-weight: 600;
  color: #1f2937;
}

.nf-desc {
  margin: 10px 0 22px;
  font-size: 13px;
  line-height: 1.7;
  color: #6b7280;
}

.nf-desc .mono {
  font-family: Consolas, monospace;
  color: #374151;
  word-break: break-all;
}

.nf-actions {
  display: flex;
  gap: 10px;
  justify-content: center;
}
</style>
