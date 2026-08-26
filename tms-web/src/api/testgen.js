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
  generatePlan(taskId) {
    return api.post(`/testgen/task/${taskId}/plan`)
  },
  confirmPlan(taskId, outline) {
    return api.post(`/testgen/task/${taskId}/confirm-plan`, outline)
  },
  getOutline(taskId) {
    return api.get(`/testgen/task/${taskId}/outline`)
  },
  generateCasesForNode(taskId, nodeId, body) {
    return api.post(`/testgen/task/${taskId}/node/${nodeId}/generate-cases`, body || {})
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
  getDownloadUrl(taskId, type) {
    return api.get(`/testgen/task/${taskId}/download-url`, { params: type ? { type } : {} })
  },
  // 临时导出：把选定用例子树发给后端即时渲染，直接拿到文件 blob 下载（不落 MinIO、不改任务状态）
  exportTemp(taskId, type, treeData) {
    return api.post(`/testgen/task/${taskId}/export`, treeData, {
      params: { type },
      responseType: 'blob'
    })
  },
  // AI 调试 - 提案（同步调 LLM，可能较久，放宽超时）
  aiDebugPropose(taskId, body) {
    return api.post(`/testgen/task/${taskId}/ai-debug`, body, { timeout: 600000 })
  },
  // AI 调试 - 应用确认的删/改/增
  aiDebugApply(taskId, body) {
    return api.post(`/testgen/task/${taskId}/ai-debug/apply`, body)
  },
  // AI 调试 - 历史记录（当前用户+任务）
  getAiDebugHistory(taskId) {
    return api.get(`/testgen/task/${taskId}/ai-debug/history`)
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
