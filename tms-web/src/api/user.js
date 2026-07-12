import request from './index'

export const userApi = {
  getFeishuAuthUrl() {
    return request({
      url: '/user/feishu/authorize-url',
      method: 'get'
    })
  },

  // 飞书扫码登录（回调页用 code + state 换取登录态）
  feishuLogin(data) {
    return request({
      url: '/user/feishu/login',
      method: 'post',
      data
    })
  },

  getMe() {
    return request({
      url: '/user/me',
      method: 'get'
    })
  },

  logout() {
    return request({
      url: '/user/logout',
      method: 'post'
    })
  }
}
