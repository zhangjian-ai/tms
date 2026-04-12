import api from './index.js'

// 模块管理API
export const moduleApi = {
  // 获取所有模块列表
  getModuleList() {
    return api.get('/module/list')
  },

  // 新增模块
  addModule(data) {
    return api.post('/module/add', data)
  },

  // 更新模块
  updateModule(data) {
    return api.post('/module/update', data)
  },

  // 删除模块
  deleteModule(data) {
    return api.post('/module/delete', data)
  }
}

