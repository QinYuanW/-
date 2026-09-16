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
        meta: { title: '产品详情', crumb: ['设备管理', '产品详情'] }
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
        meta: { title: '设备详情', crumb: ['设备管理', '设备详情'] }
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
