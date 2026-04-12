import api from './index.js'

// 设备管理API
export const deviceApi = {
  // 获取设备列表
  getDeviceList(params) {
    return api.get('/device/list', { params })
  },

  // 根据ID获取设备详情
  getDeviceDetail(id) {
    return api.get('/device/detailById', { params: { id } })
  },

  // 占用或释放设备
  deviceHold(data) {
    return api.post('/device/hold', data)
  },

  // 根据ID获取设备连接信息
  getDeviceConnection(id) {
    return api.get('/device/getConnectionById', { params: { id } })
  }

}
