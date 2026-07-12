<template>
  <div class="callback-container">
    <div class="callback-box">
      <el-icon v-if="!error" class="loading-icon is-loading"><Loading /></el-icon>
      <p v-if="!error">正在完成飞书登录...</p>
      <template v-else>
        <el-icon class="error-icon"><CircleClose /></el-icon>
        <p>{{ error }}</p>
        <el-button type="primary" size="default" @click="backToLogin">返回登录</el-button>
      </template>
    </div>
  </div>
</template>

<script>
export default {
  name: 'FeishuCallback'
}
</script>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Loading, CircleClose } from '@element-plus/icons-vue'
import { userApi } from '@/api/user'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const error = ref('')

const backToLogin = () => router.replace('/login')

onMounted(async () => {
  const { code, state } = route.query
  if (!code) {
    error.value = '缺少授权码，登录失败'
    return
  }
  try {
    const res = await userApi.feishuLogin({ code, state })
    if (res.code === 0 && res.data) {
      userStore.setToken(res.data.token)
      userStore.setUserInfo({ username: res.data.username, avatar: res.data.avatar })
      const redirect = sessionStorage.getItem('login_redirect')
      sessionStorage.removeItem('login_redirect')
      router.replace(redirect || '/devices')
    } else {
      error.value = res.msg || '飞书登录失败'
    }
  } catch (e) {
    error.value = '飞书登录失败，请重试'
  }
})
</script>

<style scoped>
.callback-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.callback-box {
  width: 360px;
  padding: 40px;
  background: white;
  border-radius: 10px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.2);
  text-align: center;
  color: #606266;
}
.loading-icon {
  font-size: 36px;
  color: #409eff;
  margin-bottom: 12px;
}
.error-icon {
  font-size: 36px;
  color: #f56c6c;
  margin-bottom: 12px;
}
</style>
