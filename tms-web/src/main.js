import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import router from './router'
import App from './App.vue'

// 全局屏蔽 ResizeObserver 错误
window.addEventListener('error', e => {
  if (e.message && e.message.includes('ResizeObserver')) {
    e.stopImmediatePropagation()
    e.preventDefault()
    return true
  }
})

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(ElementPlus)
app.use(router)
app.mount('#app')
