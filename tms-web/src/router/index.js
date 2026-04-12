import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import MainLayout from '@/components/MainLayout.vue'
import Login from '@/views/Login.vue'
import DeviceList from '@/views/devices/DeviceList.vue'
import AndroidDevice from '@/views/devices/AndroidDevice.vue'
import IOSDevice from '@/views/devices/IOSDevice.vue'
import TestCaseManagement from '@/views/functional-test/TestCaseManagement.vue'
import ProductManagement from '@/views/functional-test/ProductManagement.vue'
import TaskManagement from '@/views/functional-test/TaskManagement.vue'
import AutomationTest from '@/views/special-test/AutomationTest.vue'
import PerformanceTest from '@/views/special-test/PerformanceTest.vue'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: Login,
    meta: {
      title: '登录',
      requiresAuth: false
    }
  },
  {
    path: '/',
    component: MainLayout,
    redirect: '/devices',
    meta: {
      requiresAuth: true
    },
    children: [
      {
        path: 'devices',
        name: 'Devices',
        component: DeviceList,
        meta: {
          title: '设备管理',
          requiresAuth: true
        }
      },
      {
        path: 'devices/:id/android',
        name: 'AndroidDevice',
        component: AndroidDevice,
        meta: {
          title: 'Android 设备详情',
          requiresAuth: true
        }
      },
      {
        path: 'devices/:id/ios',
        name: 'IOSDevice',
        component: IOSDevice,
        meta: {
          title: 'iOS 设备详情',
          requiresAuth: true
        }
      },
      {
        path: 'functional-test/test-cases',
        name: 'TestCaseManagement',
        component: TestCaseManagement,
        meta: {
          title: '用例管理',
          requiresAuth: true
        }
      },
      {
        path: 'functional-test/products',
        name: 'ProductManagement',
        component: ProductManagement,
        meta: {
          title: '产品管理',
          requiresAuth: true
        }
      },
      {
        path: 'functional-test/tasks',
        name: 'TaskManagement',
        component: TaskManagement,
        meta: {
          title: '任务管理',
          requiresAuth: true
        }
      },
      {
        path: 'special-test/automation',
        name: 'AutomationTest',
        component: AutomationTest,
        meta: {
          title: '自动化测试',
          requiresAuth: true
        }
      },
      {
        path: 'special-test/performance',
        name: 'PerformanceTest',
        component: PerformanceTest,
        meta: {
          title: '性能测试',
          requiresAuth: true
        }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/devices'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 路由守卫
router.beforeEach((to, from, next) => {
  // 设置页面标题
  if (to.meta.title) {
    document.title = `${to.meta.title} - 测试平台`
  }

  // 检查是否需要登录
  const requiresAuth = to.matched.some(record => record.meta.requiresAuth)

  if (requiresAuth) {
    const userStore = useUserStore()
    userStore.initUserInfo() // 初始化用户信息

    if (!userStore.isLoggedIn()) {
      // 未登录，跳转到登录页
      next({
        path: '/login',
        query: { redirect: to.fullPath }
      })
    } else {
      next()
    }
  } else {
    next()
  }
})

export default router 