import api from './index.js'

// 配置对比 - 机器管理
export const confMachineApi = {
  list(params) {
    return api.get('/confdiff/machine/list', { params })
  },
  detail(id) {
    return api.get('/confdiff/machine/detail', { params: { id } })
  },
  saveOrUpdate(data) {
    return api.post('/confdiff/machine/saveOrUpdate', data)
  },
  delete(id) {
    return api.post('/confdiff/machine/delete', null, { params: { id } })
  },
  testConnection(id) {
    return api.post('/confdiff/machine/testConnection', null, { params: { id } })
  }
}

// 配置对比 - 项目管理
export const confProjectApi = {
  list(params) {
    return api.get('/confdiff/project/list', { params })
  },
  listByMachine(machineId) {
    return api.get('/confdiff/project/listByMachine', { params: { machineId } })
  },
  detail(id) {
    return api.get('/confdiff/project/detail', { params: { id } })
  },
  saveOrUpdate(data) {
    return api.post('/confdiff/project/saveOrUpdate', data)
  },
  delete(id) {
    return api.post('/confdiff/project/delete', null, { params: { id } })
  }
}

// 配置对比 - 执行对比
export const confCompareApi = {
  run(data) {
    return api.post('/confdiff/compare/run', data, { timeout: 600000 })
  },
  prepare(projectId) {
    return api.post('/confdiff/compare/prepare', null, { params: { projectId } })
  },
  prepareStatus(projectId) {
    return api.get('/confdiff/compare/prepareStatus', { params: { projectId } })
  },
  branches(projectId) {
    return api.get('/confdiff/compare/branches', { params: { projectId }, timeout: 120000 })
  },
  commits(projectId, branch, limit) {
    return api.get('/confdiff/compare/commits', { params: { projectId, branch, limit }, timeout: 120000 })
  },
  history(projectId) {
    return api.get('/confdiff/compare/history', { params: { projectId } })
  },
  result(id) {
    return api.get('/confdiff/compare/result', { params: { id } })
  }
}
