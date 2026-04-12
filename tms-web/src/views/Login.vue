<template>
  <div class="login-container">
    <div class="login-box">
      <div class="login-header">
        <h2>{{ isLogin ? '登录' : '注册' }}</h2>
      </div>

      <el-form
        ref="formRef"
        :model="formData"
        :rules="rules"
        class="login-form"
        @submit.prevent="handleSubmit"
      >
        <el-form-item prop="username">
          <el-input
            v-model="formData.username"
            placeholder="请输入用户名"
            size="large"
            clearable
          >
            <template #prefix>
              <el-icon><User /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="formData.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            show-password
            clearable
          >
            <template #prefix>
              <el-icon><Lock /></el-icon>
            </template>
          </el-input>
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            class="login-button"
            @click="handleSubmit"
          >
            {{ isLogin ? '登录' : '注册' }}
          </el-button>
        </el-form-item>

        <div class="switch-mode">
          <span>{{ isLogin ? '还没有账号？' : '已有账号？' }}</span>
          <el-button type="text" @click="toggleMode">
            {{ isLogin ? '立即注册' : '立即登录' }}
          </el-button>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script>
export default {
  name: 'UserLogin'
}
</script>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { userApi } from '@/api/user'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const isLogin = ref(true)
const loading = ref(false)
const formRef = ref(null)

const formData = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度在 3 到 20 个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度在 6 到 20 个字符', trigger: 'blur' }
  ]
}

const toggleMode = () => {
  isLogin.value = !isLogin.value
  formRef.value?.clearValidate()
}

const handleSubmit = async () => {
  if (!formRef.value) return

  try {
    await formRef.value.validate()
  } catch (error) {
    return
  }

  loading.value = true

  try {
    if (isLogin.value) {
      // 登录
      const res = await userApi.login(formData)
      if (res.code === 0 && res.data) {
        // 登录成功，保存用户信息
        userStore.setUserInfo({
          username: formData.username
        })
        userStore.setToken('user_' + formData.username) // 简单的 token
        ElMessage.success('登录成功')
        router.push('/devices')
      } else {
        ElMessage.error(res.msg || '登录失败，用户名或密码错误')
      }
    } else {
      // 注册
      const res = await userApi.signup(formData)
      if (res.code === 0 && res.data) {
        ElMessage.success('注册成功，请登录')
        isLogin.value = true
        formData.password = ''
      } else {
        ElMessage.error(res.msg || '注册失败')
      }
    }
  } catch (error) {
    console.error('操作失败:', error)
    ElMessage.error(error.message || '操作失败，请稍后重试')
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

.login-form {
  margin-top: 20px;
}

.login-button {
  width: 100%;
}

.switch-mode {
  text-align: center;
  margin-top: 20px;
  color: #909399;
  font-size: 14px;
}

.switch-mode span {
  margin-right: 8px;
}
</style>
