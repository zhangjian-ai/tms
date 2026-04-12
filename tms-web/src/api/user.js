import request from './index'

export const userApi = {
  // 用户注册
  signup(data) {
    return request({
      url: '/user/user/signup',
      method: 'post',
      data
    })
  },

  // 用户登录
  login(data) {
    return request({
      url: '/user/user/login',
      method: 'post',
      data
    })
  }
}
