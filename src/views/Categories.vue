<template>
  <div class="categories">
    <div class="cat-head">
      <span class="cat-title">产品分类</span>
    </div>
    <div class="cat-card">
      <el-empty description="产品分类功能规划中">
        <p class="cat-desc">
          产品分类（按分类组织产品、分类维度筛选）将在 E4 分组管理阶段一并落地。<br />
          当前请先到「产品」页完成产品与物模型配置，再到「设备」页管理设备。
        </p>
      </el-empty>
    </div>
  </div>
</template>

<script setup>
</script>

<style scoped>
.categories {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px 24px;
  overflow-y: auto;
}

.cat-head {
  display: flex;
  align-items: center;
}

.cat-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2937;
}

.cat-card {
  background: #fff;
  border: 1px solid #e4e9f0;
  border-radius: var(--radius-md, 10px);
  padding: 48px 16px;
}

.cat-desc {
  font-size: 13px;
  color: #9ca3af;
  line-height: 1.8;
  margin: 0;
}

@media (max-width: 900px) {
  .categories {
    padding: 12px;
  }
}
</style>
