import axios from 'axios'
import config from '../config/index.js'

// 创建axios实例
const api = axios.create({
  baseURL: `${config.baseURL}${config.apiPrefix}`,
  timeout: config.timeout,
  headers: {
    'Content-Type': 'application/json'
  }
})

export default api

// 请求拦截器
api.interceptors.request.use(
  config => {
    return config
  },
  error => {
    return Promise.reject(error)
  }
)

// 响应拦截器
api.interceptors.response.use(
  response => {    
    return response.data
  },
  error => {
    return Promise.reject(error)
  }
)

