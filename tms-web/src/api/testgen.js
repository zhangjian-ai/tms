import api from './index'

export const testgenApi = {
  createTask(data) {
    return api.post('/testgen/task/create', data)
  },
  listTasks(params) {
    return api.get('/testgen/task/list', { params })
  },
  getTask(taskId) {
    return api.get(`/testgen/task/${taskId}`)
  },
  getXMindData(taskId) {
    return api.get(`/testgen/task/${taskId}/xmind`)
  },
  saveXMindData(taskId, treeData) {
    return api.put(`/testgen/task/${taskId}/xmind`, treeData)
  },
  generatePoints(taskId) {
    return api.post(`/testgen/task/${taskId}/points`)
  },
  confirmPlan(taskId, outline) {
    return api.post(`/testgen/task/${taskId}/confirm-plan`, outline)
  },
  getOutline(taskId) {
    return api.get(`/testgen/task/${taskId}/outline`)
  },
  generateCasesForPoint(taskId, pointId) {
    return api.post(`/testgen/task/${taskId}/point/${pointId}/generate`)
  },
  finishTask(taskId) {
    return api.post(`/testgen/task/${taskId}/finish`)
  },
  regenerateTask(taskId) {
    return api.post(`/testgen/task/${taskId}/regenerate`)
  },
  restoreTask(taskId) {
    return api.get(`/testgen/task/${taskId}/restore`)
  },
  deleteTask(taskId) {
    return api.delete(`/testgen/task/${taskId}`)
  },
  getDownloadUrl(taskId) {
    return api.get(`/testgen/task/${taskId}/download-url`)
  }
}

// 系统配置 - 模型管理
export const modelApi = {
  list(params) {
    return api.get('/testgen/model/list', { params })
  },
  detail(id) {
    return api.get('/testgen/model/detail', { params: { id } })
  },
  saveOrUpdate(data) {
    return api.post('/testgen/model/saveOrUpdate', data)
  },
  mark(id, role, marked) {
    return api.post('/testgen/model/mark', null, { params: { id, role, marked } })
  },
  delete(id) {
    return api.post('/testgen/model/delete', null, { params: { id } })
  }
}

export const promptApi = {
  list(params) {
    return api.get('/testgen/prompt/list', { params })
  },
  detail(id) {
    return api.get('/testgen/prompt/detail', { params: { id } })
  },
  stages() {
    return api.get('/testgen/prompt/stages')
  },
  saveOrUpdate(data) {
    return api.post('/testgen/prompt/saveOrUpdate', data)
  },
  delete(id) {
    return api.post('/testgen/prompt/delete', null, { params: { id } })
  }
}
