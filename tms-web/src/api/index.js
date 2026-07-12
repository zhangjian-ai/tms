import axios from 'axios'
import config from '../config/index.js'

const api = axios.create({
  baseURL: `${config.baseURL}${config.apiPrefix}`,
  timeout: config.timeout,
  headers: {
    'Content-Type': 'application/json'
  }
})

export default api

// 请求拦截器：注入登录 token
api.interceptors.request.use(
  cfg => {
    const token = localStorage.getItem('token')
    if (token) {
      cfg.headers = cfg.headers || {}
      cfg.headers.Authorization = `Bearer ${token}`
    }
    return cfg
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器：解包 body；401 未登录/过期时清理并跳转登录页
api.interceptors.response.use(
  response => {
    return response.data
  },
  error => {
    if (error.response && error.response.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      if (window.location.pathname !== '/login') {
        const redirect = encodeURIComponent(window.location.pathname + window.location.search)
        window.location.href = `/login?redirect=${redirect}`
      }
    }
    return Promise.reject(error)
  }
)
