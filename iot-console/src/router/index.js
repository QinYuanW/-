import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/',
    component: () => import('../layout/AppLayout.vue'),
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '仪表盘', crumb: ['设备管理', '仪表盘'] }
      },
      {
        path: 'products',
        name: 'products',
        component: () => import('../views/Products.vue'),
        meta: { title: '产品', crumb: ['设备管理', '产品'] }
      },
      {
        path: 'products/:key',
        name: 'product-detail',
        component: () => import('../views/ProductDetail.vue'),
        // menu：详情页属于哪个菜单项。侧栏高亮按 path 精确匹配，而详情页 path 是
        // `/products/xxx`，对不上 `/products` → 不高亮（= 导航装作你没在任何地方）。
        meta: { title: '产品详情', crumb: ['设备管理', '产品详情'], menu: '/products' }
      },
      {
        // 通用物模型：浏览内置模板 + 一键套用到产品。
        // 放在「设备管理」下而不是塞进产品详情 —— 它的**主语是模板不是某个产品**，
        // 用户想的是"我要一个温湿度模型"，不该先选产品再找入口。
        path: 'tsl-templates',
        name: 'tsl-templates',
        component: () => import('../views/TslTemplates.vue'),
        meta: { title: '物模型', crumb: ['设备管理', '物模型'] }
      },
      {
        path: '',
        name: 'console',
        component: () => import('../views/Console.vue'),
        meta: { title: '设备', crumb: ['设备管理', '设备'] }
      },
      {
        path: 'categories',
        name: 'categories',
        component: () => import('../views/Categories.vue'),
        meta: { title: '产品分类', crumb: ['设备管理', '产品分类'] }
      },
      {
        path: 'rules',
        name: 'rules',
        component: () => import('../views/Rules.vue'),
        meta: { title: '告警规则', crumb: ['设备管理', '告警规则'] }
      },
      {
        path: 'devices/:key',
        name: 'device-detail',
        component: () => import('../views/DeviceDetail.vue'),
        // 同上：设备详情归属「设备」（菜单 index 为 `/`）
        meta: { title: '设备详情', crumb: ['设备管理', '设备详情'], menu: '/' }
      },
      {
        // 1.5：兜底 404。
        //
        // 原先没有任何通配路由，访问不存在的地址（如 /foo）会走到 Vue Router 的
        // "No match found" —— **整页空白，连左侧导航都没有**，用户只能改地址栏才出得去。
        // （评审报告原先写的是"会落到设备列表"，实测是错的，已更正。）
        //
        // 放成 `/` 的子路由而不是独立整页：404 也套在布局壳里，导航还在，用户能直接点走。
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: () => import('../views/NotFound.vue'),
        meta: { title: '页面不存在' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem('iot_token')
  if (to.name !== 'login' && !token) {
    return { name: 'login' }
  }
  if (to.name === 'login' && token) {
    return { name: 'console' }
  }
  document.title = to.meta.title ? `${to.meta.title} · 物联网 PaaS` : '物联网 PaaS'
})

export default router
