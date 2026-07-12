<template>
  <div class="login-container">
    <div class="login-box">
      <div class="login-header">
        <h2>测试平台</h2>
        <p class="subtitle">请使用飞书扫码登录</p>
      </div>

      <el-button
        type="primary"
        size="large"
        :loading="loading"
        class="login-button"
        @click="handleFeishuLogin"
      >
        <el-icon style="margin-right: 6px;"><ChatDotRound /></el-icon>
        飞书登录
      </el-button>
    </div>
  </div>
</template>

<script>
export default {
  name: 'UserLogin'
}
</script>

<script setup>
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import { userApi } from '@/api/user'

const route = useRoute()

const loading = ref(false)

// 跳转飞书授权页；回调地址由后端配置，回到 /auth/feishu/callback
const handleFeishuLogin = async () => {
  loading.value = true
  try {
    const res = await userApi.getFeishuAuthUrl()
    if (res.code === 0 && res.data) {
      // 记住回跳目标，回调成功后继续
      if (route.query.redirect) {
        sessionStorage.setItem('login_redirect', route.query.redirect)
      }
      window.location.href = res.data
    } else {
      ElMessage.error(res.msg || '获取飞书授权链接失败')
    }
  } catch (e) {
    ElMessage.error('获取飞书授权链接失败，请检查飞书配置')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-box {
  width: 400px;
  padding: 40px;
  background: white;
  border-radius: 10px;
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.2);
}

.login-header {
  text-align: center;
  margin-bottom: 30px;
}

.login-header h2 {
  margin: 0;
  font-size: 28px;
  color: #303133;
}

.login-header .subtitle {
  margin: 8px 0 0;
  font-size: 14px;
  color: #909399;
}

.login-button {
  width: 100%;
}
</style>
