import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const http = axios.create({
  baseURL: '/api/v1',
  timeout: 10000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('iot_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      if (body.code === 4010) {
        localStorage.removeItem('iot_token')
        router.push('/login')
      }
      return Promise.reject(new Error(body.message))
    }
    return body
  },
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('iot_token')
      router.push('/login')
    }
    ElMessage.error(err.response?.data?.message || err.message || '网络错误')
    return Promise.reject(err)
  }
)

export const login = (username, password) =>
  http.post('/login', { username, password })

export const listDevices = (page = 1, size = 20, keyword = '') =>
  http.get('/devices', { params: { page, size, keyword } })

export const getTsl = (deviceKey) =>
  http.get(`/devices/${deviceKey}/tsl`)

export const getLatestTelemetry = (deviceKey) =>
  http.get(`/devices/${deviceKey}/telemetry/latest`)

export const getHistoryTelemetry = (deviceKey, property, start, end) =>
  http.get(`/devices/${deviceKey}/telemetry`, { params: { property, start, end } })

export const dispatchCommand = (deviceKey, commandType, payload) =>
  http.post(`/devices/${deviceKey}/commands`, { command_type: commandType, payload })

export const getCommandStatus = (requestId) =>
  http.get(`/commands/${requestId}`)

export const registerDevice = (data) =>
  http.post('/devices', data)

export const registerBatch = (data) =>
  http.post('/devices/batch', data)

// ===== E5 产品与物模型管理 =====

export const listProducts = () =>
  http.get('/products')

export const createProduct = (data) =>
  http.post('/products', data)

export const getProductTsl = (productKey) =>
  http.get(`/products/${productKey}/tsl`)

export const updateProductTsl = (productKey, tsl) =>
  http.put(`/products/${productKey}/tsl`, tsl)

export default http
