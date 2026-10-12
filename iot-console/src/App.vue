<template>
  <router-view />
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

html,
body,
#app {
  height: 100%;
}

body {
  font-family: 'Segoe UI', 'Microsoft YaHei', -apple-system, sans-serif;
  -webkit-font-smoothing: antialiased;
  background: #f0f2f5;
  color: #2b3440;
}

/* G5 设计令牌：间距 / 圆角 / 阴影统一 */
:root {
  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-lg: 14px;
  --shadow-card: 0 1px 2px rgba(16, 24, 40, 0.04), 0 1px 3px rgba(16, 24, 40, 0.08);
  --shadow-pop: 0 12px 40px rgba(30, 64, 175, 0.1);
  --color-border: #e5eaf2;
  --color-bg-page: #f0f2f5;
}

/* Element Plus 视觉统一 */
.el-table {
  --el-table-border-color: #eef1f6;
  --el-table-header-bg-color: #f8fafc;
  --el-table-header-text-color: #4b5563;
}

.el-card,
.el-table {
  border-radius: var(--radius-md);
}

/* 窄屏（移动端 / 小窗口）响应式基线 */
@media (max-width: 768px) {
  body {
    font-size: 14px;
  }
}
</style>
