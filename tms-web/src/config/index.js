// 环境配置
const env = process.env.NODE_ENV || 'development'

// 动态获取 WebSocket 基础地址
function getWsURL() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}`
}

// 配置对象
const config = {
  // 开发环境配置
  development: {
    baseURL: 'http://localhost:8888',
    apiPrefix: '/api',
    timeout: 30000  // 增加到30秒
  },

  // 生产环境配置
  production: {
    baseURL: '',  // 使用相对路径，通过 nginx 代理
    apiPrefix: '/api',
    timeout: 30000  // 增加到30秒
  },

  // 测试环境配置
  test: {
    baseURL: 'http://localhost:8888',
    apiPrefix: '/api',
    timeout: 30000  // 增加到30秒
  }
}

// 导出当前环境的配置
const currentConfig = config[env]

// 生产环境动态计算 wsURL
if (env === 'production') {
  Object.defineProperty(currentConfig, 'wsURL', { get: getWsURL })
} else {
  currentConfig.wsURL = currentConfig.baseURL.replace('http', 'ws')
}

export default currentConfig

// 导出完整配置对象（用于调试）
export const fullConfig = config

// 导出环境变量
export const isDevelopment = env === 'development'
export const isProduction = env === 'production'
export const isTest = env === 'test' 