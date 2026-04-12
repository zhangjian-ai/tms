// 环境配置
const env = process.env.NODE_ENV || 'development'

// 配置对象
const config = {
  // 开发环境配置
  development: {
    baseURL: 'http://localhost:8888',
    apiPrefix: '/',
    timeout: 30000  // 增加到30秒
  },
  
  // 生产环境配置
  production: {
    baseURL: 'http://your-production-domain.com',
    apiPrefix: '/',
    timeout: 30000  // 增加到30秒
  },
  
  // 测试环境配置
  test: {
    baseURL: 'http://localhost:8888',
    apiPrefix: '/',
    timeout: 30000  // 增加到30秒
  }
}

// 导出当前环境的配置
export default config[env]

// 导出完整配置对象（用于调试）
export const fullConfig = config

// 导出环境变量
export const isDevelopment = env === 'development'
export const isProduction = env === 'production'
export const isTest = env === 'test' 