<template>
  <div class="test-gen-workspace">
    <div class="workspace-header">
      <div class="header-left">
        <el-button text @click="goBack"><el-icon><ArrowLeft /></el-icon></el-button>
        <h3>{{ store.task?.taskName || '用例生成' }}</h3>
        <el-tag v-if="store.task" :type="statusType" size="small">{{ statusText }}</el-tag>
        <el-tag v-if="store.wsConnected" type="success" size="small" effect="plain">已连接</el-tag>
        <el-tag v-else type="danger" size="small" effect="plain">未连接</el-tag>
      </div>
      <div class="header-right">
        <span v-if="store.task?.message && store.task?.status === 'GENERATING'" class="progress-text">
          {{ store.task.message }}
        </span>
        <el-button type="primary" @click="handleFinish" :disabled="!store.treeData">完成</el-button>
      </div>
    </div>

    <div class="workspace-content" v-loading="restoring" element-loading-text="正在恢复工作区...">
      <!-- 测试点生成阶段的全屏遮罩 -->
      <div v-if="isGeneratingPoints" class="generating-overlay">
        <div class="generating-content">
          <el-icon class="is-loading" :size="60"><Loading /></el-icon>
          <p class="generating-message">{{ store.task?.message || '正在生成测试点...' }}</p>
        </div>
      </div>

      <XMindTreePanel
        ref="treePanelRef"
        :tree-data="store.treeData"
        :generating-point-ids="generatingPointIds"
        @update="handleTreeUpdate"
        @generate-point="handleGeneratePoint"
      />
    </div>

    <!-- 浮窗对话框：Agent 对话功能暂时隐藏 -->
    <transition name="slide-fade" v-if="false">
      <div v-if="chatVisible" class="chat-float-panel">
        <div class="chat-float-header">
          <span>小助理</span>
          <el-button text @click="toggleChat" size="small">
            <el-icon><Close /></el-icon>
          </el-button>
        </div>
        <div class="chat-float-body">
          <AgentChatPanel
            :messages="store.chatMessages"
            :loading="chatLoading"
            @send="handleSendMessage"
          />
        </div>
      </div>
    </transition>
  </div>
</template>

<script>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, /* ChatDotRound, */ Close, Loading } from '@element-plus/icons-vue'
import { useTestGenStore } from '@/stores/testgen'
import { testgenApi } from '@/api/testgen'
import XMindTreePanel from '@/components/testgen/XMindTreePanel.vue'
import AgentChatPanel from '@/components/testgen/AgentChatPanel.vue'
import config from '@/config/index.js'

export default {
  name: 'TestGenWorkspace',
  components: { XMindTreePanel, AgentChatPanel, ArrowLeft, /* ChatDotRound, */ Close, Loading },
  setup() {
    const route = useRoute()
    const router = useRouter()
    const store = useTestGenStore()
    const taskId = route.params.taskId
    const restoring = ref(true)
    const chatLoading = ref(false)
    const chatVisible = ref(false)
    const generatingPointIds = ref(new Set())
    const treePanelRef = ref(null)
    let ws = null
    let reconnectTimer = null

    const statusText = computed(() => {
      const map = { NEW: '新建', GENERATING: '生成中', EDITING: '编辑中', FINISHED: '已完成', FAILED: '失败' }
      return map[store.task?.status] || ''
    })
    const statusType = computed(() => {
      const map = { NEW: 'info', GENERATING: 'warning', EDITING: '', FINISHED: 'success', FAILED: 'danger' }
      return map[store.task?.status] || 'info'
    })

    // 是否在测试点生成阶段（显示全屏遮罩）
    const isGeneratingPoints = computed(() => {
      return store.task?.status === 'GENERATING'
    })

    function toggleChat() {
      chatVisible.value = !chatVisible.value
    }

    async function restore() {
      restoring.value = true
      try {
        const res = await testgenApi.restoreTask(taskId)
        if (res.data) {
          store.setTask(res.data.task)
          // 重新生成场景下，不恢复旧的 treeData
          if (route.query.regenerate === 'true') {
            store.setTreeData(null)
          } else {
            store.setTreeData(res.data.treeData)
          }
          store.setChatHistory((res.data.chatHistory || []).map(c => ({
            role: c.role,
            content: c.content
          })))
          // 恢复正在生成中的测试点状态
          if (res.data.generatingPointIds && res.data.generatingPointIds.length > 0) {
            generatingPointIds.value = new Set(res.data.generatingPointIds)
          }
        }
      } catch (e) {
        ElMessage.error('恢复工作区失败')
      } finally {
        restoring.value = false
      }
    }

    function needsWs() {
      const { generate, regenerate } = route.query
      if (generate === 'true' || regenerate === 'true') return true
      if (generatingPointIds.value.size > 0) return true
      return store.task && (store.task.status === 'GENERATING' || store.task.status === 'EDITING')
    }

    function connectWs() {
      if (!needsWs()) {
        store.wsConnected = false
        return
      }
      if (ws && ws.readyState === WebSocket.OPEN) return
      const wsBase = config.baseURL.replace('http', 'ws')
      ws = new WebSocket(`${wsBase}/ws/testgen/${taskId}`)
      ws.onopen = () => {
        store.wsConnected = true
        if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
        checkAndTriggerGeneration()
      }
      ws.onclose = () => {
        store.wsConnected = false
        if (store.task && store.task.status === 'GENERATING') {
          scheduleReconnect()
        }
      }
      ws.onerror = () => {
        store.wsConnected = false
      }
      ws.onmessage = (event) => {
        const msg = JSON.parse(event.data)
        handleWsMessage(msg)
      }
    }

    async function checkAndTriggerGeneration() {
      const { generate, regenerate } = route.query
      if (regenerate === 'true') {
        if (store.task) {
          store.task.status = 'GENERATING'
          store.task.progress = 0
          store.task.message = '正在准备重新生成...'
        }
        try {
          await testgenApi.regenerateTask(taskId)
          await testgenApi.generatePoints(taskId)
        } catch (e) {
          ElMessage.error('重新生成失败')
          console.error(e)
        }
      } else if (generate === 'true') {
        if (store.task) {
          store.task.status = 'GENERATING'
          store.task.progress = 0
          store.task.message = '正在准备生成...'
        }
        try {
          await testgenApi.generatePoints(taskId)
        } catch (e) {
          ElMessage.error('生成失败')
          console.error(e)
        }
      }
    }

    function scheduleReconnect() {
      if (reconnectTimer) return
      reconnectTimer = setTimeout(() => {
        reconnectTimer = null
        connectWs()
      }, 3000)
    }

    function handleWsMessage(msg) {
      switch (msg.type) {
        case 'PROGRESS':
          if (store.task && store.task.status !== 'FINISHED') {
            store.task.progress = msg.data.progress
            store.task.message = msg.data.message
          }
          break
        case 'TASK_STATUS':
          if (store.task) {
            store.task.status = msg.data.status
            store.task.message = msg.data.message
          }
          break
        case 'POINTS_GENERATED':
          store.setTreeData(msg.data)
          break
        case 'CHAT_RESPONSE':
          store.addChatMessage({ role: 'assistant', content: msg.data.message })
          if (msg.data.treeData) store.setTreeData(msg.data.treeData)
          chatLoading.value = false
          break
        case 'POINT_CASES_GENERATED':
          if (msg.data.done) {
            var doneSet = new Set(generatingPointIds.value)
            doneSet.delete(msg.data.pointId)
            generatingPointIds.value = doneSet
          }
          // 直接追加节点，不触发全量渲染（保留用户折叠状态）
          if (treePanelRef.value && msg.data.cases) {
            treePanelRef.value.updatePointCases(msg.data.pointId, msg.data.cases)
          }
          break
        case 'CASES_GENERATED':
          store.setTreeData(msg.data)
          if (store.task) store.task.status = 'FINISHED'
          break
        case 'ERROR':
          ElMessage.error(msg.data.error || '发生错误')
          chatLoading.value = false
          if (store.task) store.task.status = 'FAILED'
          break
      }
    }

    async function handleTreeUpdate(updatedTree) {
      store.setTreeData(updatedTree)
      await testgenApi.saveXMindData(taskId, updatedTree)
    }

    async function handleGeneratePoint(pointId) {
      if (generatingPointIds.value.has(pointId)) {
        ElMessage.warning('该测试点正在生成用例中，请稍候...')
        return
      }
      const next = new Set(generatingPointIds.value)
      next.add(pointId)
      generatingPointIds.value = next
      ElMessage.info('正在为该测试点生成用例...')
      try {
        await testgenApi.generateCasesForPoint(taskId, pointId)
      } catch (e) {
        const rollback = new Set(generatingPointIds.value)
        rollback.delete(pointId)
        generatingPointIds.value = rollback
        ElMessage.error('生成失败')
      }
    }

    async function handleSendMessage(message) {
      store.addChatMessage({ role: 'user', content: message })
      chatLoading.value = true
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
          type: 'CHAT_MESSAGE',
          message: message,
          treeData: store.treeData
        }))
      } else {
        store.addChatMessage({ role: 'assistant', content: 'WebSocket 未连接，请稍后重试' })
        chatLoading.value = false
      }
    }

    async function handleFinish() {
      if (generatingPointIds.value.size > 0) {
        ElMessage.warning('还有测试点正在生成用例，请等待完成后再操作')
        return
      }
      try {
        await ElMessageBox.confirm('确定完成？完成后可在列表页下载 XMind 文件。', '提示')
        await testgenApi.finishTask(taskId)
        ElMessage.success('任务已完成')
        router.push('/toolbox/testgen')
      } catch (e) {
        if (e === 'cancel') {
          // 用户取消，不做任何操作
          return
        }
        ElMessage.error('完成失败')
      }
    }

    function goBack() {
      router.push('/toolbox/testgen')
    }

    function onBeforeUnload() {
      if (store.treeData) {
        const blob = new Blob([JSON.stringify(store.treeData)], { type: 'application/json' })
        navigator.sendBeacon(`${config.baseURL}${config.apiPrefix}/testgen/task/${taskId}/xmind`, blob)
      }
    }

    onMounted(async () => {
      await restore()
      connectWs()
      window.addEventListener('beforeunload', onBeforeUnload)
    })

    onUnmounted(() => {
      if (ws) ws.close()
      if (reconnectTimer) clearTimeout(reconnectTimer)
      window.removeEventListener('beforeunload', onBeforeUnload)
      store.reset()
    })

    return {
      store, restoring, chatLoading, chatVisible, statusText, statusType,
      generatingPointIds, isGeneratingPoints, treePanelRef,
      toggleChat, handleTreeUpdate, handleGeneratePoint, handleSendMessage,
      handleFinish, goBack
    }
  }
}
</script>

<style scoped>
.test-gen-workspace {
  height: 100%;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
}
.workspace-header {
  height: 52px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e6e6e6;
  background: #fff;
  flex-shrink: 0;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.header-left h3 { margin: 0; font-size: 16px; }
.header-right { display: flex; align-items: center; gap: 8px; }
.progress-text { font-size: 12px; color: #909399; white-space: nowrap; }
.workspace-content {
  flex: 1;
  overflow: hidden;
  position: relative;
}

/* 测试点生成阶段的全屏遮罩 */
.generating-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(255, 255, 255, 0.95);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.generating-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20px;
}
.generating-content .el-icon {
  color: #409eff;
}
.generating-message {
  font-size: 16px;
  color: #606266;
  margin: 0;
  font-weight: 500;
}

/* 浮窗样式 */
.chat-float-panel {
  position: fixed;
  right: 24px;
  top: 50%;
  transform: translateY(-50%);
  width: 420px;
  height: 600px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
  display: flex;
  flex-direction: column;
  z-index: 1000;
}
.chat-float-header {
  height: 48px;
  padding: 0 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid #e6e6e6;
  font-weight: 500;
  flex-shrink: 0;
}
.chat-float-body {
  flex: 1;
  overflow: hidden;
}

/* 动画 */
.slide-fade-enter-active {
  transition: all 0.3s ease-out;
}
.slide-fade-leave-active {
  transition: all 0.2s ease-in;
}
.slide-fade-enter-from {
  transform: translateY(-50%) translateX(100%);
  opacity: 0;
}
.slide-fade-leave-to {
  transform: translateY(-50%) translateX(100%);
  opacity: 0;
}
</style>
