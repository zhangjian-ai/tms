import api from './index'

export const testgenApi = {
  createTask(data) {
    return api.post('/testgen/task/create', data)
  },
  listTasks() {
    return api.get('/testgen/task/list')
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
  getDownloadUrl(taskId) {
    return api.get(`/testgen/task/${taskId}/download-url`)
  }
}
