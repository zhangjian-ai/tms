import api from './index.js'
import config from '../config/index.js'

export const deviceApi = {
  getDeviceList(params) {
    return api.get('/device/list', { params })
  },

  getDeviceDetail(id) {
    return api.get('/device/detailById', { params: { id } })
  },

  deviceHold(data) {
    return api.post('/device/hold', data)
  },

  getDeviceConnection(id) {
    return api.get('/device/getConnectionById', { params: { id } })
  },

  // 使用 keepalive 释放设备占用：用于页面关闭/刷新(pagehide)场景，
  // 此时 onUnmounted 不可靠，普通异步请求可能来不及发出
  releaseHoldOnUnload(data) {
    try {
      return fetch(`${config.baseURL}${config.apiPrefix}/device/hold`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(data),
        keepalive: true
      })
    } catch (e) {
      return Promise.resolve()
    }
  }

}

