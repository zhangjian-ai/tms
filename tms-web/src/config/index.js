const env = process.env.NODE_ENV || 'development'

function getWsURL() {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}`
}

const config = {
  development: {
    baseURL: 'http://localhost:8888',
    apiPrefix: '/api',
    timeout: 30000
  },

  production: {
    baseURL: '',  // 使用相对路径，通过 nginx 代理
    apiPrefix: '/api',
    timeout: 30000
  },

  test: {
    baseURL: 'http://localhost:8888',
    apiPrefix: '/api',
    timeout: 30000
  }
}

const currentConfig = config[env]

if (env === 'production') {
  Object.defineProperty(currentConfig, 'wsURL', { get: getWsURL })
} else {
  currentConfig.wsURL = currentConfig.baseURL.replace('http', 'ws')
}

export default currentConfig

export const fullConfig = config

export const isDevelopment = env === 'development'
export const isProduction = env === 'production'
export const isTest = env === 'test' 