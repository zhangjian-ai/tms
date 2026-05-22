import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import MainLayout from '@/components/MainLayout.vue'
import Login from '@/views/Login.vue'
import DeviceList from '@/views/devices/DeviceList.vue'
import AndroidDevice from '@/views/devices/AndroidDevice.vue'
import IOSDevice from '@/views/devices/IOSDevice.vue'

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
        path: 'toolbox/testgen',
        name: 'TestGenList',
        component: () => import('@/views/toolbox/TestGenList.vue'),
        meta: {
          title: '用例生成',
          requiresAuth: true
        }
      },
      {
        path: 'toolbox/testgen/:taskId',
        name: 'TestGenWorkspace',
        component: () => import('@/views/toolbox/TestGenWorkspace.vue'),
        meta: {
          title: '用例生成工作区',
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

router.beforeEach((to, from, next) => {
  if (to.meta.title) {
    document.title = `${to.meta.title} - 测试平台`
  }

  const requiresAuth = to.matched.some(record => record.meta.requiresAuth)

  if (requiresAuth) {
    const userStore = useUserStore()
    userStore.initUserInfo()

    if (!userStore.isLoggedIn()) {
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
