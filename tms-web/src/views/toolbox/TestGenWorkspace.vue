<template>
  <div class="test-gen-workspace">
    <div class="workspace-header">
      <div class="header-left">
        <el-button text @click="goBack"><el-icon><ArrowLeft /></el-icon></el-button>
        <h3>{{ store.task?.taskName || '用例生成' }}</h3>
        <el-tag v-if="store.task" :type="statusType" size="small">{{ statusText }}</el-tag>
        <el-tag v-if="store.wsConnected" type="success" size="small" effect="plain">已连接</el-tag>
        <el-tag v-else type="danger" size="small" effect="plain">未连接</el-tag>
        <el-tag v-if="readonly" type="warning" size="small" effect="dark">只读</el-tag>
      </div>
      <div class="header-right">
        <span v-if="store.task?.message && (store.task?.status === 'GENERATING' || store.task?.status === 'PLANNING')" class="progress-text">
          {{ store.task.message }}
        </span>
        <el-button
          type="primary"
          @click="handleFinish"
          :disabled="readonly || !store.treeData || isGeneratingPoints || generatingPointIds.size > 0"
        >完成</el-button>
      </div>
    </div>

    <div class="workspace-content" v-loading="restoring" element-loading-text="正在恢复工作区...">
      <!-- 大纲确认阶段：覆盖整个工作区 -->
      <div v-if="showOutlinePanel" class="outline-overlay">
        <OutlineConfirmPanel
          :outline="outline"
          :loading="outlineConfirming"
          @confirm="handleConfirmOutline"
          @cancel="goBack"
        />
      </div>

      <XMindTreePanel
        ref="treePanelRef"
        :tree-data="store.treeData"
        :generating-point-ids="generatingPointIds"
        :disabled="treeDisabled"
        :disabled-tip="disabledTip"
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
import { ArrowLeft, /* ChatDotRound, */ Close } from '@element-plus/icons-vue'
import { useTestGenStore } from '@/stores/testgen'
import { useUserStore } from '@/stores/user'
import { testgenApi } from '@/api/testgen'
import XMindTreePanel from '@/components/testgen/XMindTreePanel.vue'
import AgentChatPanel from '@/components/testgen/AgentChatPanel.vue'
import OutlineConfirmPanel from '@/components/testgen/OutlineConfirmPanel.vue'
import config from '@/config/index.js'

export default {
  name: 'TestGenWorkspace',
  components: { XMindTreePanel, AgentChatPanel, OutlineConfirmPanel, ArrowLeft, /* ChatDotRound, */ Close },
  setup() {
    const route = useRoute()
    const router = useRouter()
    const store = useTestGenStore()
    const userStore = useUserStore()
    const taskId = route.params.taskId
    const restoring = ref(true)
    const chatLoading = ref(false)
    const chatVisible = ref(false)
    const generatingPointIds = ref(new Set())
    const treePanelRef = ref(null)
    const readonly = ref(false)
    const outline = ref(null)
    const outlineConfirming = ref(false)
    let ws = null
    let reconnectTimer = null
    let heartbeatTimer = null

    const statusText = computed(() => {
      const map = {
        NEW: '新建',
        PLANNING: '规划中',
        PLAN_REVIEW: '待确认',
        GENERATING: '生成中',
        EDITING: '编辑中',
        FINISHED: '已完成',
        FAILED: '失败'
      }
      return map[store.task?.status] || ''
    })
    const statusType = computed(() => {
      const map = {
        NEW: 'info',
        PLANNING: 'warning',
        PLAN_REVIEW: 'warning',
        GENERATING: 'warning',
        EDITING: '',
        FINISHED: 'success',
        FAILED: 'danger'
      }
      return map[store.task?.status] || 'info'
    })

    // 是否在测试点生成阶段（显示全屏遮罩）
    const isGeneratingPoints = computed(() => {
      return store.task?.status === 'GENERATING' || store.task?.status === 'PLANNING'
    })

    const showOutlinePanel = computed(() => {
      return !readonly.value && store.task?.status === 'PLAN_REVIEW' && !!outline.value
    })

    // 生成阶段（PLANNING/GENERATING）禁止编辑树；只读用户也禁；其他场景允许
    const treeDisabled = computed(() => {
      if (readonly.value) return true
      const s = store.task?.status
      return s === 'PLANNING' || s === 'GENERATING'
    })
    const disabledTip = computed(() => {
      if (readonly.value) return '只读模式：当前任务正被其他用户编辑'
      const s = store.task?.status
      if (s === 'PLANNING' || s === 'GENERATING') {
        return store.task?.message || '正在生成中，编辑已暂时禁用...'
      }
      return ''
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
          // 恢复大纲（任务停留在 PLAN_REVIEW 时）
          if (res.data.outline) {
            outline.value = res.data.outline
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
      return store.task && (
        store.task.status === 'GENERATING' ||
        store.task.status === 'PLANNING' ||
        store.task.status === 'PLAN_REVIEW' ||
        store.task.status === 'EDITING'
      )
    }

    function connectWs() {
      if (!needsWs()) {
        store.wsConnected = false
        return
      }
      if (ws && ws.readyState === WebSocket.OPEN) return
      const token = userStore.token || ''
      if (!token) {
        ElMessage.error('登录态失效，请重新登录')
        router.push('/login')
        return
      }
      ws = new WebSocket(`${config.wsURL}/api/ws/testgen/${taskId}?token=${encodeURIComponent(token)}`)
      ws.onopen = () => {
        store.wsConnected = true
        if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
        startHeartbeat()
        checkAndTriggerGeneration()
      }
      ws.onclose = () => {
        store.wsConnected = false
        stopHeartbeat()
        if (readonly.value) return
        // 大纲规划/生成中/编辑中 阶段都需要继续接收推送，断开时尝试重连
        const status = store.task && store.task.status
        if (status === 'PLANNING' || status === 'PLAN_REVIEW' ||
            status === 'GENERATING' || status === 'EDITING') {
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

    function startHeartbeat() {
      stopHeartbeat()
      heartbeatTimer = setInterval(() => {
        if (ws && ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'HEARTBEAT' }))
        }
      }, 30000)
    }

    function stopHeartbeat() {
      if (heartbeatTimer) {
        clearInterval(heartbeatTimer)
        heartbeatTimer = null
      }
    }

    async function checkAndTriggerGeneration() {
      const { generate, regenerate } = route.query

      if (readonly.value) return

      // 重新生成：只有非生成中状态才触发，避免刷新页面重复清空数据
      if (regenerate === 'true') {
        if (store.task && store.task.status === 'GENERATING') {
          console.log('任务正在生成中，跳过重新生成')
          return
        }
        // 立即清除 URL 中的 regenerate 参数，避免本次生成完成后刷新页面又重复触发
        router.replace({ path: route.path })
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
        return
      }

      // 首次生成：只有 NEW 状态才触发，避免刷新页面重复生成
      if (generate === 'true') {
        if (!store.task || store.task.status !== 'NEW') {
          console.log('任务状态不是 NEW，跳过生成。当前状态:', store.task?.status)
          return
        }
        // 立即清除 URL 中的 generate 参数，避免本次生成完成后刷新页面又重复触发
        router.replace({ path: route.path })
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
        case 'CONNECTED':
          // 占用判定结果由后端通过 ownership 字段告知
          if (msg.data && (msg.data.ownership === 'GRANTED' || msg.data.ownership === 'SHARED')) {
            readonly.value = false
          }
          break
        case 'OCCUPIED': {
          readonly.value = true
          stopHeartbeat()
          if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null }
          const occupier = msg.data?.occupiedBy || '其他用户'
          ElMessageBox.alert(
            `该任务正在被 ${occupier} 编辑，您当前为只读模式，无法触发生成或修改。`,
            '任务被占用',
            { confirmButtonText: '我知道了', type: 'warning' }
          ).catch(() => {})
          break
        }
        case 'HEARTBEAT_ACK':
          break
        case 'PROGRESS':
          if (store.task && store.task.status !== 'FINISHED') {
            store.task.progress = msg.data.progress
            store.task.message = msg.data.message
          }
          break
        case 'TASK_STATUS':
          if (store.task) {
            const prev = store.task.status
            store.task.status = msg.data.status
            store.task.message = msg.data.message
            // 自动生成阶段彻底结束（GENERATING -> EDITING）：流式期间的 updatePointCases
            // 累计改动会让 mind-elixir 内部状态漂移，此时缩放/再操作可能把画布推出可视区。
            // 这里销毁重建一次面板，相当于"重进页面"的干净初始化。
            if (prev === 'GENERATING' && msg.data.status === 'EDITING') {
              setTimeout(() => {
                if (treePanelRef.value && treePanelRef.value.rebuild) {
                  treePanelRef.value.rebuild()
                }
              }, 100)
            }
          }
          break
        case 'PLAN_DRAFTED':
          outline.value = msg.data?.outline || null
          break
        case 'PHASE_CHANGED':
          // 阶段切换：可在此驱动时间线 UI；目前只通过 task.status 间接体现
          break
        case 'POINTS_GENERATED':
          store.setTreeData(msg.data)
          break
        case 'POINT_ADDED':
          // 流式新增单个测试点：树重建后由后端指定的 latestNodeId 精确居中
          if (msg.data && msg.data.root) {
            store.setTreeData(msg.data.root)
            if (treePanelRef.value && msg.data.latestNodeId) {
              // 等待 watch -> initMind 完成后再居中
              setTimeout(function () {
                if (treePanelRef.value && treePanelRef.value.centerOnNode) {
                  treePanelRef.value.centerOnNode(msg.data.latestNodeId)
                }
              }, 100)
            }
          }
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
          // 流式期间：累积更新 point 节点的子用例（done=true 也同样以最终 cases 列表覆盖一次）
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

    async function handleConfirmOutline(payload) {
      if (readonly.value) {
        ElMessage.warning('当前为只读模式，无法确认大纲')
        return
      }
      outlineConfirming.value = true
      try {
        await testgenApi.confirmPlan(taskId, payload)
        outline.value = payload
        // 状态切换由后端 ws TASK_STATUS / PHASE_CHANGED 推送驱动，避免错误分支卡死
      } catch (e) {
        ElMessage.error('确认大纲失败')
      } finally {
        outlineConfirming.value = false
      }
    }

    async function handleTreeUpdate(updatedTree) {
      if (readonly.value) {
        ElMessage.warning('当前为只读模式，无法修改')
        return
      }
      store.setTreeData(updatedTree)
      await testgenApi.saveXMindData(taskId, updatedTree)
    }

    function findNodeById(node, id) {
      if (!node) return null
      if (node.id === id) return node
      if (!node.children) return null
      for (const c of node.children) {
        const found = findNodeById(c, id)
        if (found) return found
      }
      return null
    }

    async function handleGeneratePoint(pointId) {
      if (readonly.value) {
        ElMessage.warning('当前为只读模式，无法生成')
        return
      }
      if (generatingPointIds.value.has(pointId)) {
        ElMessage.warning('该测试点正在生成用例中，请稍候...')
        return
      }
      // 已有用例的测试点：二次确认（后端会清空旧用例后重新生成）
      const pointNode = findNodeById(store.treeData, pointId)
      const existingCases = (pointNode?.children || []).filter(c => c.type === 'case')
      if (existingCases.length > 0) {
        try {
          await ElMessageBox.confirm(
            `该测试点下已有 ${existingCases.length} 条用例，重新生成将清空现有用例，确定继续？`,
            '确认重新生成',
            { confirmButtonText: '继续生成', cancelButtonText: '取消', type: 'warning' }
          )
        } catch (e) {
          return
        }
      }
      const next = new Set(generatingPointIds.value)
      next.add(pointId)
      generatingPointIds.value = next
      // 立即清空面板上该测试点的旧用例，避免新用例到达前看到陈旧内容
      // （与流式增量推送一致，只动 mind 内存数据，权威状态由后端 ws 推送回填）
      if (existingCases.length > 0 && treePanelRef.value) {
        treePanelRef.value.updatePointCases(pointId, [])
      }
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
      if (readonly.value) {
        ElMessage.warning('当前为只读模式，无法发送消息')
        return
      }
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
      if (readonly.value) {
        ElMessage.warning('当前为只读模式，无法完成任务')
        return
      }
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
      stopHeartbeat()
      if (ws) ws.close()
      if (reconnectTimer) clearTimeout(reconnectTimer)
      window.removeEventListener('beforeunload', onBeforeUnload)
      store.reset()
    })

    return {
      store, restoring, chatLoading, chatVisible, statusText, statusType,
      generatingPointIds, isGeneratingPoints, treePanelRef, readonly,
      outline, outlineConfirming, showOutlinePanel,
      treeDisabled, disabledTip,
      toggleChat, handleTreeUpdate, handleGeneratePoint, handleSendMessage,
      handleFinish, handleConfirmOutline, goBack
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

/* 大纲确认阶段的覆盖面板 */
.outline-overlay {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: #fff;
  z-index: 999;
  display: flex;
}
.outline-overlay > * { width: 100%; }

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
