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
          :disabled="readonly || !store.treeData || isGenerating || generatingNodeIds.size > 0"
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
        :generating-node-ids="generatingNodeIds"
        :disabled="treeDisabled"
        :disabled-tip="disabledTip"
        @update="handleTreeUpdate"
        @generate-cases="handleGenerateForNode"
      />
    </div>
  </div>
</template>

<script>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { useTestGenStore } from '@/stores/testgen'
import { useUserStore } from '@/stores/user'
import { testgenApi } from '@/api/testgen'
import XMindTreePanel from '@/components/testgen/XMindTreePanel.vue'
import OutlineConfirmPanel from '@/components/testgen/OutlineConfirmPanel.vue'
import config from '@/config/index.js'

export default {
  name: 'TestGenWorkspace',
  components: { XMindTreePanel, OutlineConfirmPanel, ArrowLeft },
  setup() {
    const route = useRoute()
    const router = useRouter()
    const store = useTestGenStore()
    const userStore = useUserStore()
    const taskId = route.params.taskId
    const restoring = ref(true)
    const generatingNodeIds = ref(new Set())
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

    // 是否在生成阶段（规划/用例生成，显示全屏遮罩）
    const isGenerating = computed(() => {
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
          // 恢复正在生成中的节点 loading 态
          if (res.data.generatingNodeIds && res.data.generatingNodeIds.length > 0) {
            generatingNodeIds.value = new Set(res.data.generatingNodeIds)
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
      if (generatingNodeIds.value.size > 0) return true
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
          store.task.message = '正在准备重新生成...'
        }
        try {
          await testgenApi.regenerateTask(taskId)
          await testgenApi.generatePlan(taskId)
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
          store.task.message = '正在准备生成...'
        }
        try {
          await testgenApi.generatePlan(taskId)
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
            store.task.message = msg.data.message
          }
          break
        case 'TASK_STATUS':
          if (store.task) {
            const prev = store.task.status
            store.task.status = msg.data.status
            store.task.message = msg.data.message
            // 自动生成阶段彻底结束（GENERATING -> EDITING）：流式期间的 updateNodeChildren
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
        case 'TREE_UPDATED':
          // 整树快照（提取/精修/阶段完成时的权威更新）
          store.setTreeData(msg.data)
          break
        case 'NODE_CASES_GENERATED':
          if (msg.data.done) {
            var doneSet = new Set(generatingNodeIds.value)
            doneSet.delete(msg.data.nodeId)
            generatingNodeIds.value = doneSet
          }
          // 流式期间：以后端推送的最新子树覆盖该节点（done=true 也以最终子树覆盖一次）
          if (treePanelRef.value && msg.data.children) {
            treePanelRef.value.updateNodeChildren(msg.data.nodeId, msg.data.children)
          }
          break
        case 'ERROR':
          ElMessage.error(msg.data.error || '发生错误')
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

    // ---- 云端保存：防抖 + 失败重试 ----
    // 面板每次操作都会触发 handleTreeUpdate，这里对「网络保存」做防抖，
    // 合并短时间内的多次改动为一次全量 PUT；store 仍即时更新以保证 UI 响应。
    let saveTimer = null
    let pendingTree = null
    const SAVE_DEBOUNCE_MS = 600

    // 保存一次，失败则延迟重试一次，仍失败才 toast 提示（避免本地已改、云端未存却无感知）
    async function doSaveWithRetry(tree) {
      try {
        await testgenApi.saveXMindData(taskId, tree)
      } catch (e) {
        await new Promise(function(r) { setTimeout(r, 800) })
        try {
          await testgenApi.saveXMindData(taskId, tree)
        } catch (e2) {
          ElMessage.error('用例改动保存到云端失败，请检查网络后重试')
        }
      }
    }

    function scheduleSave(tree) {
      pendingTree = tree
      if (saveTimer) clearTimeout(saveTimer)
      saveTimer = setTimeout(function() {
        saveTimer = null
        var t = pendingTree
        pendingTree = null
        if (t) doSaveWithRetry(t)
      }, SAVE_DEBOUNCE_MS)
    }

    // 立即冲刷未落库的改动（离开页面 / 卸载时调用），避免防抖窗口内的最后一次改动丢失
    function flushSave() {
      if (!saveTimer) return
      clearTimeout(saveTimer)
      saveTimer = null
      var t = pendingTree
      pendingTree = null
      if (t) doSaveWithRetry(t)
    }

    function handleTreeUpdate(updatedTree) {
      if (readonly.value) {
        ElMessage.warning('当前为只读模式，无法修改')
        return
      }
      store.setTreeData(updatedTree)
      scheduleSave(updatedTree)
    }

    async function handleGenerateForNode(nodeId) {
      if (readonly.value) {
        ElMessage.warning('当前为只读模式，无法生成')
        return
      }
      if (generatingNodeIds.value.has(nodeId)) {
        ElMessage.warning('该目录正在生成用例中，请稍候...')
        return
      }
      // 弹框收集补充测试内容（必填），基于完整需求文档 + 补充测试内容为该目录生成用例（仅追加）
      let extraRequirement = ''
      try {
        const { value } = await ElMessageBox.prompt(
          '请填写要补充的测试内容，将结合需求文档为该目录生成用例',
          '生成用例',
          {
            confirmButtonText: '生成',
            cancelButtonText: '取消',
            inputType: 'textarea',
            inputPlaceholder: '例如：补充某个边界场景 / 指定关注的功能点……',
            inputValidator: (val) => (val && val.trim()) ? true : '请填写补充测试内容'
          }
        )
        extraRequirement = (value || '').trim()
      } catch (e) {
        return
      }
      if (!extraRequirement) return
      const next = new Set(generatingNodeIds.value)
      next.add(nodeId)
      generatingNodeIds.value = next
      ElMessage.info('正在为该目录生成用例...')
      try {
        await testgenApi.generateCasesForNode(taskId, nodeId, { extraRequirement })
      } catch (e) {
        const rollback = new Set(generatingNodeIds.value)
        rollback.delete(nodeId)
        generatingNodeIds.value = rollback
        ElMessage.error('生成失败')
      }
    }

    async function handleFinish() {
      if (readonly.value) {
        ElMessage.warning('当前为只读模式，无法完成任务')
        return
      }
      if (generatingNodeIds.value.size > 0) {
        ElMessage.warning('还有目录正在生成用例，请等待完成后再操作')
        return
      }
      try {
        await ElMessageBox.confirm('确定完成？完成后可在列表页下载 XMind、Excel 文件。', '提示')
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
      flushSave()
      stopHeartbeat()
      if (ws) ws.close()
      if (reconnectTimer) clearTimeout(reconnectTimer)
      window.removeEventListener('beforeunload', onBeforeUnload)
      store.reset()
    })

    return {
      store, restoring, statusText, statusType,
      generatingNodeIds, isGenerating, treePanelRef, readonly,
      outline, outlineConfirming, showOutlinePanel,
      treeDisabled, disabledTip,
      handleTreeUpdate, handleGenerateForNode,
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
</style>
