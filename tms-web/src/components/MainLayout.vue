<template>
  <el-container class="main-layout">
    <!-- 侧边栏 -->
    <el-aside :width="isCollapse ? '64px' : '200px'" class="sidebar">
      <div class="sidebar-header">
        <img src="@/assets/logo.png" alt="Logo" class="logo" v-if="!isCollapse">
        <span class="title" v-if="!isCollapse">测试平台</span>
      </div>
      
      <el-menu
        :default-active="activeMenu"
        :collapse="isCollapse"
        :unique-opened="true"
        router
        class="sidebar-menu"
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409EFF"
      >
        <el-menu-item index="/devices">
          <el-icon><Cellphone /></el-icon>
          <template #title>设备管理</template>
        </el-menu-item>
        
        <el-sub-menu index="functional-test">
          <template #title>
            <el-icon><Document /></el-icon>
            <span>功能测试</span>
          </template>
          <el-menu-item index="/functional-test/products">
            <el-icon><Box /></el-icon>
            <template #title>产品管理</template>
          </el-menu-item>
          <el-menu-item index="/functional-test/test-cases">
            <el-icon><List /></el-icon>
            <template #title>用例管理</template>
          </el-menu-item>
          <el-menu-item index="/functional-test/tasks">
            <el-icon><Timer /></el-icon>
            <template #title>任务管理</template>
          </el-menu-item>
        </el-sub-menu>
        
        <el-sub-menu index="special-test">
          <template #title>
            <el-icon><Setting /></el-icon>
            <span>专项测试</span>
          </template>
          <el-menu-item index="/special-test/automation">
            <el-icon><Lightning /></el-icon>
            <template #title>自动化测试</template>
          </el-menu-item>
          <el-menu-item index="/special-test/performance">
            <el-icon><Odometer /></el-icon>
            <template #title>性能测试</template>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>

    <!-- 主内容区域 -->
    <el-container>
      <!-- 顶部导航栏 -->
      <el-header class="main-header">
        <div class="header-left">
          <el-button
            type="text"
            @click="toggleSidebar"
            class="collapse-btn"
          >
            <el-icon :size="20">
              <Fold v-if="!isCollapse" />
              <Expand v-else />
            </el-icon>
          </el-button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentPageTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        
        <div class="header-right">
          <el-dropdown @command="handleCommand">
            <span class="user-info">
              <el-avatar :size="28" src="https://cube.elemecdn.com/0/88/03b0d39583f48206768a7534e55bcpng.png" />
              <span class="username">{{ username }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <!-- 主体内容 -->
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script>
import { ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import {
  Cellphone,
  Fold,
  Expand,
  Document,
  List,
  Timer,
  Setting,
  Odometer,
  Lightning,
  Box
} from '@element-plus/icons-vue'

export default {
  name: 'MainLayout',
  components: {
    Cellphone,
    Fold,
    Expand,
    Document,
    List,
    Timer,
    Setting,
    Odometer,
    Lightning,
    Box
  },
  setup() {
    const route = useRoute()
    const router = useRouter()
    const userStore = useUserStore()
    const isCollapse = ref(false)

    // 当前激活的菜单项
    const activeMenu = computed(() => route.path)

    // 用户名
    const username = computed(() => {
      return userStore.userInfo?.username || '未登录'
    })

    // 当前页面标题
    const currentPageTitle = computed(() => {
      const titleMap = {
        '/devices': '设备管理',
        '/devices/connection': '设备连接信息',
        '/functional-test/test-cases': '用例管理',
        '/functional-test/products': '产品管理',
        '/functional-test/tasks': '任务管理',
        '/special-test/automation': '自动化测试',
        '/special-test/performance': '性能测试'
      }
      return titleMap[route.path] || '设备管理'
    })

    // 切换侧边栏
    const toggleSidebar = () => {
      isCollapse.value = !isCollapse.value
    }

    // 处理下拉菜单命令
    const handleCommand = async (command) => {
      if (command === 'logout') {
        try {
          await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
            confirmButtonText: '确定',
            cancelButtonText: '取消',
            type: 'warning'
          })
          userStore.logout()
          ElMessage.success('已退出登录')
          router.push('/login')
        } catch (error) {
          // 用户取消
        }
      }
    }

    return {
      isCollapse,
      activeMenu,
      currentPageTitle,
      username,
      toggleSidebar,
      handleCommand
    }
  }
}
</script>

<style scoped>
.main-layout {
  height: 100vh;
}

.sidebar {
  background-color: #304156;
  transition: width 0.3s;
  overflow: hidden;
}

.sidebar-header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #2b2f3a;
  color: white;
  padding: 0 16px;
}

.logo {
  width: 42px;
  height: 42px;
  margin-right: 8px;
}

.title {
  font-size: 16px;
  font-weight: 600;
  white-space: nowrap;
}

.sidebar-menu {
  border: none;
  height: calc(100vh - 56px);
}

.main-header {
  background-color: white;
  border-bottom: 1px solid #e6e6e6;
  display: flex;
  height: 48px;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  margin: 0;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
}

.header-left {
  display: flex;
  align-items: center;
}

.collapse-btn {
  padding: 8px;
  color: #666;
}

.collapse-btn:hover {
  color: #409EFF;
  background-color: #f5f7fa;
}

.header-right {
  display: flex;
  align-items: center;
}

.user-info {
  display: flex;
  align-items: center;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: background-color 0.3s;
}

.user-info:hover {
  background-color: #87b3f5;
}

.username {
  font-size: 12px;
  color: #0e0e0d;
}

.main-content {
  background-color: #f5f7fa;
  overflow-y: auto;
  padding: 0;
  margin: 0;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .sidebar {
    position: fixed;
    z-index: 1000;
    height: 100vh;
  }
  
  .main-header {
    padding: 0 12px;
  }
  
  .main-content {
    padding: 0;
    margin: 0;
  }
}
</style> 