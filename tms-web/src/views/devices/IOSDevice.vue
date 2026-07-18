<template>
  <div class="device-connection">
    <div class="main-content">
      <!-- 左侧连接信息区域 -->
      <div class="connection-info-area">
        <div class="info-content">
          <el-card class="info-card">
            <template #header>
              <div class="card-header">
                <h3>连接信息</h3>
                <el-button
                  type="primary"
                  size="small"
                  @click="releaseDevice"
                  :title="'释放设备'"
                >
                  释放设备
                </el-button>
              </div>
            </template>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="连接状态">
                <el-tag v-if="isConnected" type="success">已连接</el-tag>
                <el-tag v-else-if="loading" type="info">连接中...</el-tag>
                <el-tag v-else type="danger">未连接</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="代理服务">
                {{ connectionInfo.proxyHost && connectionInfo.proxyPort ? connectionInfo.proxyHost + ':' + connectionInfo.proxyPort : '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="WDA 连接">
                <el-tag type="success" @click="copyWDACommand" style="cursor: pointer;">
                  {{ getWDAUrl() }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>

          <!-- 应用管理面板 -->
          <DeviceAppPanel
            v-if="appManagerEnabled"
            :proxy-host="connectionInfo.proxyHost"
            :proxy-port="connectionInfo.proxyPort"
            :serial="deviceSerial"
            platform="ios"
          />

          <el-card v-if="elementInspectorEnabled" class="info-card">
            <template #header>
              <div class="card-header">
                <h3>元素信息</h3>
              </div>
            </template>
            <div class="element-inspector-content">
              <div v-if="hoverElement" class="element-info">
                <el-descriptions :column="1" border size="small">
                  <el-descriptions-item label="类型">
                    {{ hoverElement.type || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="名称">
                    {{ hoverElement.name || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="标签">
                    {{ hoverElement.label || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="值">
                    {{ hoverElement.value || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="边界">
                    {{ hoverElement.rect || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="可用">
                    <el-tag :type="hoverElement.enabled === 'true' ? 'success' : 'danger'" size="small">
                      {{ hoverElement.enabled === 'true' ? '是' : '否' }}
                    </el-tag>
                  </el-descriptions-item>
                  <el-descriptions-item label="可见">
                    <el-tag :type="hoverElement.visible === 'true' ? 'success' : 'info'" size="small">
                      {{ hoverElement.visible === 'true' ? '是' : '否' }}
                    </el-tag>
                  </el-descriptions-item>
                </el-descriptions>
              </div>

              <div v-else class="no-selection">
                <el-icon size="24"><InfoFilled /></el-icon>
                <p>请悬停选择元素</p>
              </div>
            </div>
          </el-card>
        </div>
      </div>

      <!-- 中间投屏区域 -->
      <div class="screen-area">
        <div class="screen-header">
          <h3>设备投屏</h3>
          <div class="header-controls" v-if="isConnected && videoResolution.width > 0">
            <el-button
              type="warning"
              size="small"
              @click="handleWakeScreen"
              class="control-btn"
              :title="'唤醒屏幕'"
            >
              <el-icon><Sunny /></el-icon>
            </el-button>
            <el-button
              type="primary"
              size="small"
              @click="handleHomeKey"
              class="control-btn"
              :title="'HOME'"
            >
              <el-icon><HomeFilled /></el-icon>
            </el-button>
            <el-button
              type="success"
              size="small"
              @click="handleScreenshot"
              class="control-btn"
              :title="'截屏'"
            >
              <el-icon><Camera /></el-icon>
            </el-button>
            <el-button
              type="info"
              size="small"
              @click="handleDumpXml"
              class="control-btn"
              :title="'Dump XML'"
            >
              <el-icon><Document /></el-icon>
            </el-button>
            <el-button
              :type="elementInspectorEnabled ? 'danger' : 'warning'"
              size="small"
              @click="toggleElementInspector"
              class="control-btn"
              :title="elementInspectorEnabled ? '关闭元素检查器' : '开启元素检查器'"
            >
              <el-icon><Search /></el-icon>
            </el-button>
            <el-button
              size="small"
              @click="toggleAppManager"
              class="control-btn"
              :type="appManagerEnabled ? 'primary' : ''"
              :title="appManagerEnabled ? '关闭应用管理' : '应用管理'"
            >
              <el-icon><Grid /></el-icon>
            </el-button>
          </div>
        </div>

        <div class="screen-main">
          <div v-if="loading" class="screen-placeholder">
            <el-icon class="is-loading" size="48"><Loading /></el-icon>
            <p>正在获取连接信息...</p>
          </div>
          <div v-else-if="!isConnected" class="screen-placeholder">
            <el-icon size="48" color="#F56C6C">
              <Monitor />
            </el-icon>
            <el-button type="primary" size="small" @click="connectDevice" style="margin-top: 16px;">
              重新连接
            </el-button>
          </div>
          <div v-else class="screen-display">
            <div ref="videoWrapperRef" class="video-wrapper">
              <canvas
                ref="screenCanvas"
                class="screen-canvas"
                :width="videoResolution.width"
                :height="videoResolution.height"
                @pointerdown="handlePointerDown"
                @pointermove="handlePointerMove"
                @pointerup="handlePointerUp"
                @pointercancel="handlePointerCancel"
                @pointerleave="handlePointerLeave"
                @wheel.prevent="handleWheel"
              />
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧操作日志面板 -->
      <div v-if="elementInspectorEnabled" class="operation-log-panel">
        <div class="log-panel-header">
          <h3>操作日志</h3>
          <el-button
            size="small"
            type="danger"
            @click="clearOperationLogs"
            :title="'清空操作日志'"
          >
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>

        <div class="log-panel-content">
          <div v-if="operationLogs.length === 0" class="no-logs">
            <el-icon size="24"><Document /></el-icon>
            <p>暂无操作记录</p>
            <small>鼠标hover或点击元素时会自动记录</small>
          </div>
          <div v-else class="logs-container">
            <div
              v-for="(log, index) in operationLogs"
              :key="index"
              class="log-item"
            >
              <div class="log-header">
                <span class="log-action">{{ log.action }}</span>
                <span class="log-time">{{ formatTime(log.timestamp) }}</span>
              </div>
              <div class="log-details">
                <div v-if="log.element" class="element-info">
                  <div v-if="log.element.label || log.element.name" class="element-text-row">
                    <span class="element-text" @click="copyToClipboard(log.element.label || log.element.name, '文本')">
                      <span class="property-label">文本:</span>{{ log.element.label || log.element.name }}
                    </span>
                  </div>
                  <div class="element-main">
                    <span v-if="log.element.type" class="element-class" @click="copyToClipboard(log.element.type, '类型')">
                      <span class="property-label">类型:</span>{{ log.element.type }}
                    </span>
                    <span v-if="log.element.value" class="element-id" @click="copyToClipboard(log.element.value, '值')">
                      <span class="property-label">值:</span>{{ log.element.value }}
                    </span>
                  </div>
                  <div class="element-meta">
                    <span v-if="log.coordinates" class="coordinates" @click="copyToClipboard(log.coordinates, '坐标')">
                      <span class="property-label">坐标:</span>{{ log.coordinates }}
                    </span>
                    <span v-if="log.element.rect" class="bounds" @click="copyToClipboard(log.element.rect, '边界')">
                      <span class="property-label">边界:</span>{{ log.element.rect }}
                    </span>
                    <span v-if="log.element.enabled === 'true'" class="clickable">可用</span>
                    <span v-if="log.element.enabled === 'false'" class="disabled">禁用</span>
                  </div>
                </div>
                <!-- 无元素信息时只显示坐标 -->
                <div v-else class="action-info">
                  <span v-if="log.coordinates" class="coordinates" @click="copyToClipboard(log.coordinates, '坐标')">
                    <span class="property-label">坐标:</span>{{ log.coordinates }}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Camera, Document, Loading, Sunny, HomeFilled, Monitor, Search, Delete, InfoFilled, Grid } from '@element-plus/icons-vue'
import { deviceApi } from '@/api/device'
import DeviceAppPanel from '@/views/devices/DeviceAppPanel.vue'
import { useUserStore } from '@/stores/user'
import { useDeviceSessionStore } from '@/stores/deviceSession.js'
import { useIdleRelease } from '@/composables/useIdleRelease'
import config from '@/config/index.js'
import pako from 'pako'

const genSessionId = () => {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID()
  }
  return 'sess-' + Math.random().toString(36).slice(2) + Date.now().toString(36)
}

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const deviceSessionStore = useDeviceSessionStore()

// 设备序列号（用于 WebSocket 路径）
const deviceSerial = ref(route.query.serial || '')

const deviceId = ref(route.params.id ? Number(route.params.id) : null)

// 本页会话令牌：优先沿用列表页占用时的 sessionId，否则 onMounted 时自行占用
let sessionId = deviceSessionStore.getSession(deviceId.value)

// 连接信息（iOS 下 adb_host/adb_port 表示 WDA 地址）
const connectionInfo = reactive({
  proxyHost: '',
  proxyPort: '',
  adbHost: '',
  adbPort: ''
})

const loading = ref(true)

const isConnected = ref(false)
let controlWs = null
let screenWs = null
let inspectorWs = null

// 视频分辨率
const videoResolution = reactive({
  width: 0,
  height: 0
})

const screenCanvas = ref(null)
const videoWrapperRef = ref(null)
let canvasContext = null

// 投屏帧解码：始终渲染最新帧、丢弃积压帧（与 Android WebCodecs 一致的「画最新」策略）
let latestFrameBuffer = null
let frameDecoding = false

const mouseState = reactive({
  isDown: false,
  startX: 0,
  startY: 0,
  beganAt: null  // 按下时间，用于区分点击/长按
})
let activePointerId = null  // 指针捕获的活动 pointerId

let wheelDebounceTimer = null
let wheelAccumulatedY = 0
let wheelStartCoords = null

let resizeTimer = null

let holdWs = null
let holdHeartbeatTimer = null

const elementInspectorEnabled = ref(false)
const appManagerEnabled = ref(false)     // 应用管理面板开关（与检查器互斥）
const operationLogs = ref([])
const hoverElement = ref(null)
const uiHierarchy = ref(null)
let xmlCheckTimer = null
let lastXmlHash = ref('')
let elementHoverTimer = null

const getWDAUrl = () => {
  if (!connectionInfo.adbHost || !connectionInfo.adbPort) {
    return '-'
  }
  return `http://${connectionInfo.adbHost}:${connectionInfo.adbPort}`
}

const fetchConnectionInfo = async () => {
  if (!deviceId.value) return false
  try {
    const res = await deviceApi.getDeviceConnection(deviceId.value)
    // 代理未起时 data 可能为 null，静默等待轮询
    if (res.code === 0 && res.data) {
      const d = res.data
      connectionInfo.proxyHost = d.proxyHost || ''
      connectionInfo.proxyPort = d.proxyPort != null ? String(d.proxyPort) : ''
      connectionInfo.adbHost = d.adbHost || ''
      connectionInfo.adbPort = d.adbPort != null ? String(d.adbPort) : ''
    }
  } catch (e) {
    // 轮询期间静默
  }
  return !!(connectionInfo.proxyHost && connectionInfo.proxyPort)
}

const waitForConnectionReady = async (timeoutMs = 40000, intervalMs = 1500) => {
  loading.value = true
  const start = Date.now()
  try {
    while (Date.now() - start < timeoutMs) {
      if (await fetchConnectionInfo()) return true
      await new Promise(r => setTimeout(r, intervalMs))
    }
    return await fetchConnectionInfo()
  } finally {
    loading.value = false
  }
}

const copyWDACommand = () => {
  const url = getWDAUrl()
  if (url === '-') return

  navigator.clipboard.writeText(url).then(() => {
    ElMessage.success('WDA 地址已复制')
  })
}

// 按可视区域与真实分辨率比例计算投屏展示尺寸
const adjustScreenContainer = () => {
  const wrapper = videoWrapperRef.value
  if (!wrapper || !videoResolution.width || !videoResolution.height) return
  const vw = videoResolution.width
  const vh = videoResolution.height
  const ratio = vw / vh
  try {
    const mainContent = document.querySelector('.main-content')
    if (!mainContent) return
    const availableHeight = mainContent.clientHeight - 24
    const headerEl = document.querySelector('.screen-header')
    const headerHeight = headerEl ? headerEl.offsetHeight : 40
    const maxVideoHeight = availableHeight - headerHeight - 20

    // 计算可用宽度：总宽度减去连接信息区域和间距
    const connectionArea = document.querySelector('.connection-info-area')
    const connectionWidth = connectionArea ? connectionArea.offsetWidth : 350
    const maxVideoWidth = mainContent.clientWidth - connectionWidth - 36

    // iOS 逻辑分辨率按比例缩放
    const scaleFactor = 0.8
    let targetWidth = vw * scaleFactor
    let targetHeight = vh * scaleFactor

    // 先按可用高度约束
    if (targetHeight > maxVideoHeight) {
      targetHeight = maxVideoHeight
      targetWidth = targetHeight * ratio
    }
    // 再按宽度约束
    if (targetWidth > maxVideoWidth) {
      targetWidth = maxVideoWidth
      targetHeight = targetWidth / ratio
    }

    wrapper.style.width = `${targetWidth}px`
    wrapper.style.height = `${targetHeight}px`
    const screenArea = document.querySelector('.screen-area')
    if (screenArea) {
      screenArea.style.maxWidth = `${targetWidth + 24}px`
      screenArea.style.width = 'auto'
      // 左侧栏高度对齐投屏区，使应用管理面板底边与投屏底边一致
      if (connectionArea) {
        connectionArea.style.height = `${screenArea.offsetHeight}px`
      }
    }
  } catch (e) {
    console.error('adjustScreenContainer:', e)
  }
}

const sendControlMessage = (message) => {
  if (controlWs && controlWs.readyState === WebSocket.OPEN) {
    controlWs.send(JSON.stringify(message))
  } else {
    ElMessage.error('设备控制连接未建立，无法操作设备')
  }
}

const connectDevice = async () => {
  try {
    await fetchConnectionInfo()
    if (!connectionInfo.proxyHost || !connectionInfo.proxyPort) {
      ElMessage.warning('暂无代理连接信息，请确认 agent 已上报该设备')
      return
    }
    if (!deviceSerial.value) {
      ElMessage.warning('缺少设备序列号')
      return
    }

    await connectControlWebSocket()
    await connectScreenWebSocket()
    isConnected.value = true
    ElMessage.success('设备连接成功')
  } catch (error) {
    console.error('连接设备失败:', error)
    ElMessage.error(`连接失败: ${error.message}`)
  }
}

const connectControlWebSocket = () => {
  return new Promise((resolve, reject) => {
    const wsUrl = `ws://${connectionInfo.proxyHost}:${connectionInfo.proxyPort}/devices/${deviceSerial.value}/control`
    controlWs = new WebSocket(wsUrl)

    controlWs.onopen = () => {
      console.log('控制 WebSocket 连接成功')
      resolve()
    }

    controlWs.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        handleControlMessage(data)
      } catch (error) {
        console.error('解析控制消息失败:', error)
      }
    }

    controlWs.onerror = (error) => {
      console.error('控制 WebSocket 错误:', error)
      reject(error)
    }

    controlWs.onclose = () => {
      console.log('控制 WebSocket 连接关闭')
      isConnected.value = false
    }
  })
}

const connectScreenWebSocket = () => {
  return new Promise((resolve, reject) => {
    const wsUrl = `ws://${connectionInfo.proxyHost}:${connectionInfo.proxyPort}/devices/${deviceSerial.value}/screen`
    screenWs = new WebSocket(wsUrl)
    screenWs.binaryType = 'arraybuffer'  // 接收二进制数据

    screenWs.onopen = () => {
      console.log('投屏 WebSocket 连接成功')
      screenWs.send(JSON.stringify({ type: 'start_stream' }))
      resolve()
    }

    screenWs.onmessage = (event) => {
      try {
        if (event.data instanceof ArrayBuffer) {
          renderBinaryFrame(event.data)
        } else {
          const data = JSON.parse(event.data)
          handleScreenMessage(data)
        }
      } catch (error) {
        console.error('处理投屏消息失败:', error)
      }
    }

    screenWs.onerror = (error) => {
      console.error('投屏 WebSocket 错误:', error)
      reject(error)
    }

    screenWs.onclose = () => {
      console.log('投屏 WebSocket 连接关闭')
    }
  })
}

const handleControlMessage = (data) => {
  switch (data.type) {
    case 'connected':
      if (data.device_resolution) {
        const w = data.device_resolution[0]
        const h = data.device_resolution[1]
        videoResolution.width = w
        videoResolution.height = h
        nextTick(() => {
          if (screenCanvas.value) {
            canvasContext = screenCanvas.value.getContext('2d')
          }
          adjustScreenContainer()
        })
      }
      break
    case 'screenshot_result':
      if (data.success) {
        downloadScreenshot(data.data.image)
      } else {
        ElMessage.error(data.error || '截图失败')
      }
      break
    case 'dump_hierarchy_result':
      if (data.success) {
        downloadXml(data.data)
      } else {
        ElMessage.error(data.error || 'Dump XML 失败')
      }
      break
    case 'home_result':
      if (!data.success) {
        ElMessage.error(data.error || 'HOME 键执行失败')
      }
      break
    case 'wake_screen_result':
      if (!data.success) {
        ElMessage.error(data.error || '唤醒屏幕失败')
      }
      break
    case 'click_result':
      if (!data.success) {
        console.warn('点击失败:', data.error)
      }
      break
    case 'swipe_result':
      if (!data.success) {
        console.warn('滑动失败:', data.error)
      }
      break
    case 'error':
      ElMessage.error(data.message || '设备操作异常')
      break
  }
}

const handleScreenMessage = (data) => {
  switch (data.type) {
    case 'stream_started':
      console.log('投屏已启动，FPS:', data.fps || 30)
      break
    case 'stream_stopped':
      console.log('投屏已停止')
      break
    case 'device_disconnected':
      // 设备被拔出/断开：清理连接
      ElMessage.warning('设备已断开连接')
      isConnected.value = false
      if (controlWs) controlWs.close()
      if (screenWs) screenWs.close()
      if (inspectorWs) inspectorWs.close()
      break
  }
}

const renderBinaryFrame = (arrayBuffer) => {
  if (!canvasContext) return
  // 只保留最新帧；若正在解码则直接替换待渲染帧，解码完再取最新，避免积压导致延迟累积
  latestFrameBuffer = arrayBuffer
  if (frameDecoding) return
  frameDecoding = true
  ;(async () => {
    while (latestFrameBuffer) {
      const buf = latestFrameBuffer
      latestFrameBuffer = null
      try {
        // createImageBitmap 在后台线程解码 JPEG，无 objectURL/Image 开销，延迟与 GC 都更低
        const bitmap = await createImageBitmap(new Blob([buf], { type: 'image/jpeg' }))
        if (canvasContext) {
          canvasContext.drawImage(bitmap, 0, 0, videoResolution.width, videoResolution.height)
        }
        bitmap.close()
      } catch (e) {
        // 单帧解码失败直接丢弃
      }
    }
    frameDecoding = false
  })()
}

const getEventDeviceCoords = (clientX, clientY, clampToBounds = false) => {
  const canvas = screenCanvas.value
  if (!canvas || !videoResolution.width || !videoResolution.height) return null
  const rect = canvas.getBoundingClientRect()
  if (rect.width <= 0 || rect.height <= 0) return null
  const scaleX = videoResolution.width / rect.width
  const scaleY = videoResolution.height / rect.height
  let x = (clientX - rect.left) * scaleX
  let y = (clientY - rect.top) * scaleY
  const outOfBounds = x < 0 || y < 0 || x > videoResolution.width || y > videoResolution.height
  if (outOfBounds) {
    // 拖拽/抬手时钳制到屏幕边缘（保证滑到边、界外松手不误判为点击）；其余情况越界忽略
    if (!clampToBounds) return null
    x = Math.max(0, Math.min(x, videoResolution.width))
    y = Math.max(0, Math.min(y, videoResolution.height))
  }
  return { x: Math.round(x), y: Math.round(y) }
}

// 指针按下 - 记录起点并捕获指针，使后续 move/up 即使移出 canvas 仍投递到此元素
const handlePointerDown = (event) => {
  if (!isConnected.value) return
  // 只响应主键（鼠标左键 / 触摸 / 笔）；已有活动手势时忽略额外指针
  if (event.button !== undefined && event.button !== 0) return
  if (mouseState.isDown) return

  const coords = getEventDeviceCoords(event.clientX, event.clientY)
  if (!coords) return

  try {
    screenCanvas.value.setPointerCapture(event.pointerId)
  } catch (e) { /* 忽略 */ }
  activePointerId = event.pointerId

  mouseState.isDown = true
  mouseState.startX = coords.x
  mouseState.startY = coords.y
  mouseState.beganAt = Date.now()
}

const handlePointerMove = (event) => {
  // 未按下且启用元素检查器时进行元素查找（hover 越界忽略，用严格坐标）
  if (!mouseState.isDown && elementInspectorEnabled.value) {
    const coords = getEventDeviceCoords(event.clientX, event.clientY)
    if (coords && videoResolution.width && videoResolution.height) {
      if (elementHoverTimer) {
        clearTimeout(elementHoverTimer)
      }
      elementHoverTimer = setTimeout(() => {
        if (coords.x >= 0 && coords.y >= 0 && coords.x <= videoResolution.width && coords.y <= videoResolution.height) {
          if (!uiHierarchy.value) {
            scheduleXmlCheck(100)
          }
          findElementAtPosition(coords.x, coords.y)
        }
      }, 150)
    }
  }
  // 按下拖拽过程：WDA 仅支持 tap/swipe，不发送中间点
}

const handlePointerLeave = () => {
  // 仅在未按下时清理检查器 hover；拖拽期间由指针捕获维持，离开元素不影响
  if (!mouseState.isDown && elementInspectorEnabled.value) {
    hoverElement.value = null
  }
}

const handlePointerUp = (event) => {
  if (activePointerId !== null && event.pointerId !== activePointerId) return
  finishGesture(event)
}

const handlePointerCancel = (event) => {
  if (activePointerId !== null && event.pointerId !== activePointerId) return
  finishGesture(event)
}

const finishGesture = (event) => {
  if (!mouseState.isDown) return

  // 终点钳制坐标：界外松手落在边缘，而非回退起点被误判成点击
  const coords = getEventDeviceCoords(event.clientX, event.clientY, true)
  const endX = coords ? coords.x : mouseState.startX
  const endY = coords ? coords.y : mouseState.startY
  const moveX = Math.abs(endX - mouseState.startX)
  const moveY = Math.abs(endY - mouseState.startY)
  const duration = Date.now() - mouseState.beganAt

  // 先复位状态并释放捕获，避免中途断连时输入被永久锁死
  mouseState.isDown = false
  try {
    if (activePointerId !== null) {
      screenCanvas.value.releasePointerCapture(activePointerId)
    }
  } catch (e) { /* 忽略 */ }
  activePointerId = null

  if (!isConnected.value) return

  if (moveX < 10 && moveY < 10) {
    if (duration < 200) {
      sendClick(mouseState.startX, mouseState.startY)
    } else {
      sendLongClick(mouseState.startX, mouseState.startY, duration)
    }
  } else {
    sendSwipe(mouseState.startX, mouseState.startY, endX, endY)
  }
}

const handleWheel = (event) => {
  if (!isConnected.value) return

  if (!wheelStartCoords) {
    const coords = getEventDeviceCoords(event.clientX, event.clientY)
    if (!coords) return
    wheelStartCoords = coords
  }

  wheelAccumulatedY += event.deltaY

  if (wheelDebounceTimer) {
    clearTimeout(wheelDebounceTimer)
  }

  // 滚动停止 150ms 后执行
  wheelDebounceTimer = setTimeout(() => {
    if (!wheelStartCoords) return

    const scrollAmount = wheelAccumulatedY
    wheelAccumulatedY = 0

    if (Math.abs(scrollAmount) < 5) {
      wheelStartCoords = null
      wheelDebounceTimer = null
      return
    }

    // 滚轮偏移映射为滑动距离（上限为屏幕高度 1/4）
    const maxSwipe = videoResolution.height * 0.25
    const swipeDistance = Math.min(Math.abs(scrollAmount) * 1.5, maxSwipe)
    const direction = scrollAmount > 0 ? 1 : -1

    const startX = wheelStartCoords.x
    const startY = wheelStartCoords.y
    const endX = startX
    const endY = Math.max(0, Math.min(videoResolution.height, startY - direction * swipeDistance))

    sendSwipe(startX, startY, endX, endY)

    wheelStartCoords = null
    wheelDebounceTimer = null
  }, 150)
}

const sendClick = (x, y) => {
  if (elementInspectorEnabled.value) {
    const logData = {
      coordinates: `(${x}, ${y})`,
      timestamp: new Date()
    }

    if (hoverElement.value) {
      logData.element = { ...hoverElement.value }
    }

    addOperationLog('点击操作', logData)
    scheduleXmlCheck(300)
  }

  sendControlMessage({ type: 'click', x, y })
}

const sendLongClick = (x, y, duration) => {
  sendControlMessage({ type: 'long_click', x, y, duration: duration / 1000 })
}

// 发送滑动 - duration 100ms
const sendSwipe = (startX, startY, endX, endY) => {
  if (elementInspectorEnabled.value) {
    const logData = {
      coordinates: `从(${startX}, ${startY})到(${endX}, ${endY})`,
      timestamp: new Date()
    }

    const startElement = findElementAtPosition(startX, startY)
    if (startElement) {
      logData.element = { ...startElement }
    } else if (hoverElement.value) {
      logData.element = { ...hoverElement.value }
    }

    addOperationLog('滑动操作', logData)
    scheduleXmlCheck(300)
  }

  sendControlMessage({
    type: 'swipe',
    start_x: startX,
    start_y: startY,
    end_x: endX,
    end_y: endY,
    duration: 0.1
  })
}

const handleScreenshot = () => {
  sendControlMessage({ type: 'screenshot' })
}

const handleDumpXml = () => {
  sendControlMessage({ type: 'dump_hierarchy' })
}

const handleHomeKey = () => {
  sendControlMessage({ type: 'home' })
}

const handleWakeScreen = () => {
  sendControlMessage({ type: 'wake_screen' })
}

const downloadScreenshot = (base64Image) => {
  const link = document.createElement('a')
  link.href = `data:image/png;base64,${base64Image}`
  link.download = `ios_screenshot_${Date.now()}.png`
  link.click()
  ElMessage.success('截图已保存')
}

// 下载 XML（后端返回 gzip+base64）
const downloadXml = async (data) => {
  if (!data || !data.hierarchy) return
  try {
    if (data.compressed) {
      const binaryStr = atob(data.hierarchy)
      const bytes = new Uint8Array(binaryStr.length)
      for (let i = 0; i < binaryStr.length; i++) {
        bytes[i] = binaryStr.charCodeAt(i)
      }
      const blob = new Blob([bytes])
      const ds = new DecompressionStream('gzip')
      const decompressed = await new Response(blob.stream().pipeThrough(ds)).text()
      saveXmlFile(decompressed)
    } else {
      saveXmlFile(data.hierarchy)
    }
  } catch (e) {
    console.error('解压 XML 失败:', e)
    saveXmlFile(data.hierarchy)
  }
}

const saveXmlFile = (content) => {
  const blob = new Blob([content], { type: 'application/xml' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `ios_hierarchy_${Date.now()}.xml`
  link.click()
  URL.revokeObjectURL(link.href)
  ElMessage.success('XML 已导出')
}

// 静默释放：调后端释放 + 断连清理，供手动释放与空闲释放复用
const teardownAndRelease = async () => {
  if (!deviceId.value) return false
  const res = await deviceApi.deviceHold({ id: deviceId.value, holder: null, sessionId })
  if (res.code !== 0) {
    ElMessage.error(res.msg || '释放失败')
    return false
  }
  deviceSessionStore.clearSession(deviceId.value)
  stopHoldHeartbeat()
  if (controlWs) controlWs.close()
  if (screenWs) screenWs.close()
  if (inspectorWs) inspectorWs.close()
  isConnected.value = false
  elementInspectorEnabled.value = false
  appManagerEnabled.value = false
  return true
}

const releaseDevice = async () => {
  try {
    await ElMessageBox.confirm('确定要释放此设备吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return // 用户取消
  }
  try {
    if (await teardownAndRelease()) {
      ElMessage.success('设备已释放')
      router.push({ name: 'Devices' })
    }
  } catch (error) {
    console.error('释放设备失败:', error)
    ElMessage.error('释放设备失败')
  }
}

// 长时间切走页面则自动释放并在回到页面时提示
useIdleRelease({
  isActive: () => isConnected.value,
  release: teardownAndRelease,
  onReleased: () => router.push({ name: 'Devices' })
})

// 窗口 resize 时重新计算投屏展示尺寸（防抖）
const handleWindowResize = () => {
  if (resizeTimer) clearTimeout(resizeTimer)
  resizeTimer = setTimeout(() => {
    if (videoResolution.width && videoResolution.height) {
      nextTick(() => adjustScreenContainer())
    }
  }, 100)
}

// ========== 元素检查器功能 ==========

const connectInspectorWebSocket = () => {
  return new Promise((resolve, reject) => {
    const wsUrl = `ws://${connectionInfo.proxyHost}:${connectionInfo.proxyPort}/devices/${deviceSerial.value}/inspector`
    inspectorWs = new WebSocket(wsUrl)

    inspectorWs.onopen = () => {
      console.log('元素检查器 WebSocket 连接成功')
      resolve()
    }

    inspectorWs.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data)
        handleInspectorMessage(data)
      } catch (error) {
        console.error('解析检查器消息失败:', error)
      }
    }

    inspectorWs.onerror = (error) => {
      console.error('元素检查器 WebSocket 错误:', error)
      reject(error)
    }

    inspectorWs.onclose = () => {
      console.log('元素检查器 WebSocket 连接关闭')
    }
  })
}

const handleInspectorMessage = (data) => {
  switch (data.type) {
    case 'connected':
      console.log('元素检查器已连接')
      break
    case 'ui_hierarchy':
    case 'xml_only':
      if (data.success && data.data && data.data.xml) {
        decompressXml(data.data).then(xmlContent => {
          const currentXmlHash = generateXmlHash(xmlContent)
          if (currentXmlHash && currentXmlHash !== lastXmlHash.value) {
            lastXmlHash.value = currentXmlHash
            const tree = parseIOSXml(xmlContent)
            if (tree) {
              uiHierarchy.value = tree
            }
          }
        }).catch(error => {
          console.error('处理 XML 数据失败:', error)
        })
      }
      break
    case 'error':
      console.error('元素检查器错误:', data.message)
      break
  }
}

// 关闭元素检查器并清理其状态
const closeElementInspector = () => {
  elementInspectorEnabled.value = false
  hoverElement.value = null
  uiHierarchy.value = null
  if (inspectorWs) {
    inspectorWs.close()
    inspectorWs = null
  }
}

const toggleElementInspector = async () => {
  if (elementInspectorEnabled.value) {
    closeElementInspector()
    return
  }
  if (appManagerEnabled.value) {
    try {
      await ElMessageBox.confirm('应用管理已开启，切换到元素检查器？', '提示', { type: 'warning' })
    } catch {
      return
    }
    appManagerEnabled.value = false
  }
  elementInspectorEnabled.value = true
  if (!inspectorWs || inspectorWs.readyState !== WebSocket.OPEN) {
    try {
      await connectInspectorWebSocket()
      // 连接成功后立即获取一次 UI 层次
      refreshUIHierarchy()
    } catch (error) {
      console.error('连接元素检查器失败:', error)
      ElMessage.error('元素检查器连接失败')
      elementInspectorEnabled.value = false
    }
  } else {
    refreshUIHierarchy()
  }
}

const toggleAppManager = async () => {
  if (appManagerEnabled.value) {
    appManagerEnabled.value = false
    return
  }
  if (elementInspectorEnabled.value) {
    try {
      await ElMessageBox.confirm('元素检查器已开启，切换到应用管理？', '提示', { type: 'warning' })
    } catch {
      return
    }
    closeElementInspector()
  }
  appManagerEnabled.value = true
}

const refreshUIHierarchy = () => {
  if (!inspectorWs || inspectorWs.readyState !== WebSocket.OPEN) return
  inspectorWs.send(JSON.stringify({ type: 'get_xml_only' }))
}

const decompressXml = async (data) => {
  if (!data || !data.xml) {
    throw new Error('无效的 XML 数据')
  }

  try {
    if (data.compressed) {
      const binaryStr = atob(data.xml)
      const bytes = new Uint8Array(binaryStr.length)
      for (let i = 0; i < binaryStr.length; i++) {
        bytes[i] = binaryStr.charCodeAt(i)
      }
      // 后端用 gzip 压缩，需 ungzip
      const decompressed = pako.ungzip(bytes, { to: 'string' })
      return decompressed
    } else {
      return data.xml
    }
  } catch (error) {
    console.error('解压 XML 失败:', error)
    throw error
  }
}

const generateXmlHash = (xmlString) => {
  if (!xmlString) return ''
  let hash = 0
  for (let i = 0; i < xmlString.length; i++) {
    const char = xmlString.charCodeAt(i)
    hash = ((hash << 5) - hash) + char
    hash = hash & hash
  }
  return hash.toString()
}

const parseIOSXml = (xmlString) => {
  try {
    const parser = new DOMParser()
    const xmlDoc = parser.parseFromString(xmlString, 'text/xml')

    const parseError = xmlDoc.querySelector('parsererror')
    if (parseError) {
      console.error('XML 解析错误:', parseError.textContent)
      return null
    }

    const root = xmlDoc.documentElement
    return parseXmlNode(root)
  } catch (error) {
    console.error('解析 iOS XML 失败:', error)
    return null
  }
}

const parseXmlNode = (node) => {
  if (!node || node.nodeType !== 1) return null

  const element = {
    type: node.getAttribute('type') || '',
    name: node.getAttribute('name') || '',
    label: node.getAttribute('label') || '',
    value: node.getAttribute('value') || '',
    enabled: node.getAttribute('enabled') || 'true',
    visible: node.getAttribute('visible') || 'true',
    x: node.getAttribute('x') || '0',
    y: node.getAttribute('y') || '0',
    width: node.getAttribute('width') || '0',
    height: node.getAttribute('height') || '0',
    children: []
  }

  const x = parseInt(element.x)
  const y = parseInt(element.y)
  const w = parseInt(element.width)
  const h = parseInt(element.height)
  element.rect = `[${x},${y}][${x + w},${y + h}]`

  for (let i = 0; i < node.children.length; i++) {
    const child = parseXmlNode(node.children[i])
    if (child) {
      element.children.push(child)
    }
  }

  return element
}

const findElementAtPosition = (x, y) => {
  if (!uiHierarchy.value) return null

  const element = findSmallestElementAt(uiHierarchy.value, x, y)
  hoverElement.value = element
  return element
}

const findSmallestElementAt = (node, x, y) => {
  if (!node) return null

  const allMatches = collectAllMatchingElements(node, x, y)

  if (allMatches.length === 0) return null

  let bestMatch = allMatches[0]
  let minArea = bestMatch.area

  for (const match of allMatches) {
    if (match.area < minArea) {
      minArea = match.area
      bestMatch = match
    }
  }

  return bestMatch.element
}

const collectAllMatchingElements = (node, x, y, matches = []) => {
  if (!node) return matches

  const nx = parseInt(node.x)
  const ny = parseInt(node.y)
  const nw = parseInt(node.width)
  const nh = parseInt(node.height)

  if (x >= nx && x <= nx + nw && y >= ny && y <= ny + nh) {
    matches.push({
      element: node,
      area: nw * nh
    })

    if (node.children && node.children.length > 0) {
      for (const child of node.children) {
        collectAllMatchingElements(child, x, y, matches)
      }
    }
  }

  return matches
}

const addOperationLog = (action, data) => {
  operationLogs.value.unshift({
    action,
    ...data
  })

  if (operationLogs.value.length > 5) {
    operationLogs.value = operationLogs.value.slice(0, 5)
  }
}

const clearOperationLogs = () => {
  operationLogs.value = []
  ElMessage.success('操作日志已清空')
}

const formatTime = (timestamp) => {
  const date = new Date(timestamp)
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const seconds = String(date.getSeconds()).padStart(2, '0')
  return `${hours}:${minutes}:${seconds}`
}

const copyToClipboard = (text, label) => {
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success(`${label}已复制`)
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

const scheduleXmlCheck = (delay = 300) => {
  if (!elementInspectorEnabled.value) return

  if (xmlCheckTimer) {
    clearTimeout(xmlCheckTimer)
  }

  xmlCheckTimer = setTimeout(() => {
    refreshUIHierarchy()
  }, delay)
}

// 设备占用心跳 - 每5秒发送一次
const startHoldHeartbeat = () => {
  stopHoldHeartbeat()
  const wsUrl = config.wsURL + '/api/ws/device/hold'
  holdWs = new WebSocket(wsUrl)
  holdWs.onopen = () => {
    sendHoldHeartbeat()
    holdHeartbeatTimer = setInterval(sendHoldHeartbeat, 5000)
  }
  holdWs.onclose = () => {
    if (holdHeartbeatTimer) {
      clearInterval(holdHeartbeatTimer)
      holdHeartbeatTimer = null
    }
  }
  holdWs.onerror = (err) => {
    console.error('设备占用心跳连接失败:', err)
  }
}

const sendHoldHeartbeat = () => {
  if (holdWs && holdWs.readyState === WebSocket.OPEN && deviceSerial.value) {
    holdWs.send(JSON.stringify({
      serial: deviceSerial.value,
      username: userStore.userInfo?.username || '',
      sessionId
    }))
  }
}

const stopHoldHeartbeat = () => {
  if (holdHeartbeatTimer) {
    clearInterval(holdHeartbeatTimer)
    holdHeartbeatTimer = null
  }
  if (holdWs) {
    try {
      if (holdWs.readyState === WebSocket.OPEN || holdWs.readyState === WebSocket.CONNECTING) {
        holdWs.close(1000)
      }
    } catch (e) { /* ignore */ }
    holdWs = null
  }
}

// 确保本页持有设备占用：继承自列表页则沿用，否则自行原子占用
const ensureHold = async () => {
  if (sessionId) return true

  const holder = userStore.userInfo?.username || ''
  if (!holder) {
    ElMessage.error('未获取到登录用户信息，无法占用设备')
    return false
  }

  const newSession = genSessionId()
  try {
    const res = await deviceApi.deviceHold({ id: deviceId.value, holder, sessionId: newSession, cast: true })
    if (!res || res.code !== 0) return false
  } catch (e) {
    return false
  }

  sessionId = newSession
  deviceSessionStore.setSession(deviceId.value, sessionId)
  return true
}

// 页面关闭/刷新时以 keepalive 方式释放占用
const handlePageHide = () => {
  if (sessionId) {
    deviceApi.releaseHoldOnUnload({ id: deviceId.value, holder: null, sessionId })
  }
}

onMounted(async () => {
  // 先确认占用再投屏
  const ok = await ensureHold()
  if (!ok) {
    try {
      await ElMessageBox.alert('该设备正在被占用/使用中，无法投屏', '设备使用中', {
        confirmButtonText: '返回设备列表',
        type: 'warning'
      })
    } catch (e) { /* ignore */ }
    router.push({ name: 'Devices' })
    return
  }

  if (deviceSerial.value) {
    startHoldHeartbeat()
  }

  // 等待 agent 启动 WDA 代理并上报连接信息
  const ready = await waitForConnectionReady()
  if (!ready) {
    ElMessage.error('设备代理启动超时，请重试')
    stopHoldHeartbeat()
    if (sessionId) {
      deviceApi.deviceHold({ id: deviceId.value, holder: null, sessionId }).catch(() => {})
      deviceSessionStore.clearSession(deviceId.value)
    }
    router.push({ name: 'Devices' })
    return
  }

  connectDevice()
  window.addEventListener('resize', handleWindowResize)
  window.addEventListener('pagehide', handlePageHide)
})

onBeforeUnmount(() => {
  // 关闭可能仍打开的消息弹窗
  ElMessageBox.close()
  if (resizeTimer) clearTimeout(resizeTimer)
  if (wheelDebounceTimer) clearTimeout(wheelDebounceTimer)
  if (xmlCheckTimer) clearTimeout(xmlCheckTimer)
  if (elementHoverTimer) clearTimeout(elementHoverTimer)
  stopHoldHeartbeat()
  window.removeEventListener('resize', handleWindowResize)
  window.removeEventListener('pagehide', handlePageHide)
  if (controlWs) controlWs.close()
  if (screenWs) screenWs.close()
  if (inspectorWs) inspectorWs.close()
  // 仅释放本会话持有的占用
  if (sessionId) {
    deviceApi.deviceHold({ id: deviceId.value, holder: null, sessionId }).catch(() => {})
    deviceSessionStore.clearSession(deviceId.value)
  }
})
</script>

<style scoped>
.device-connection {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.main-content {
  flex: 1;
  display: flex;
  gap: 12px;
  padding: 12px;
  overflow-y: auto;
  align-items: flex-start;
}

.connection-info-area {
  width: 350px;
  display: flex;
  flex-direction: column;
}

.info-content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}

/* 应用管理面板填满连接信息卡片下方的剩余空间，使其底边与投屏区底边对齐 */
.info-content .app-manager-card {
  flex: 1;
  min-height: 0;
  margin-bottom: 0;
}

.info-card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header h3 {
  margin: 0;
  font-size: 16px;
}

.element-inspector-content {
  min-height: 200px;
}

.no-selection {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  color: #909399;
  text-align: center;
}

.no-selection p {
  margin: 12px 0 0;
  font-size: 14px;
}

.screen-area {
  display: flex;
  flex-direction: column;
  background-color: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  flex: none;
  overflow: hidden;
}

.screen-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 10px;
  border-bottom: 1px solid #e6e6e6;
}

.screen-header h3 {
  margin: 0;
  color: #303133;
}

.header-controls {
  display: flex;
  gap: 8px;
  align-items: center;
}

.header-controls .control-btn {
  width: 32px !important;
  padding: 0 !important;
  margin: 0 !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  border-radius: 6px !important;
}

.screen-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 0;
}

.screen-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  color: #909399;
  text-align: center;
  min-height: 480px;
  min-width: 320px;
  background-color: #000;
}

.screen-placeholder p {
  margin: 8px 0;
  font-size: 14px;
}

.screen-display {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: fit-content;
  background-color: #000;
  padding: 0;
}

.video-wrapper {
  position: relative;
  background: #000;
  overflow: hidden;
  flex-shrink: 0;
}

.screen-canvas {
  display: block;
  width: 100%;
  height: 100%;
  cursor: crosshair;
  background-color: #000;
  touch-action: none;
}

/* 操作日志面板样式 */
.operation-log-panel {
  display: flex;
  flex-direction: column;
  width: 320px;
  min-width: 320px;
  max-width: 320px;
  height: 100%;
  border-left: 1px solid #e4e7ed;
  flex-shrink: 0;
}

.log-panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid #e4e7ed;
  background-color: #f5f7fa;
}

.log-panel-header h3 {
  margin: 0;
  font-size: 16px;
  color: #303133;
}

.log-panel-content {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.no-logs {
  text-align: center;
  color: #909399;
  padding: 40px 20px;
}

.no-logs p {
  margin: 0 0 8px 0;
  font-size: 14px;
}

.logs-container {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.log-item {
  padding: 8px 10px;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  background-color: #fff;
  font-size: 12px;
  line-height: 1.4;
  width: 100%;
  overflow-wrap: break-word;
  word-break: break-word;
}

.log-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.log-action {
  font-weight: 500;
  color: #303133;
  font-size: 13px;
}

.log-time {
  font-size: 11px;
  color: #909399;
}

.log-details {
  font-size: 12px;
}

.element-info {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.element-text-row {
  margin-bottom: 2px;
}

.element-text-row .element-text {
  display: block;
  width: 100%;
  word-break: break-all;
  line-height: 1.3;
  margin-top: 1px;
}

.element-main {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: flex-start;
  width: 100%;
  overflow-wrap: break-word;
  margin-bottom: 1px;
}

.element-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  font-size: 11px;
  color: #606266;
  width: 100%;
  overflow-wrap: break-word;
  margin-top: 1px;
}

.action-info {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  color: #606266;
  font-size: 11px;
  align-items: flex-start;
  width: 100%;
  overflow-wrap: break-word;
}

/* 属性标签样式 */
.property-label {
  font-size: 10px;
  color: #909399;
  font-weight: normal;
  margin-right: 2px;
  display: inline-block;
  flex-shrink: 0;
}

/* 元素属性样式 - 统一样式 */
.element-class,
.element-text,
.element-id,
.coordinates,
.bounds {
  font-size: 11px;
  cursor: pointer;
  padding: 1px 2px;
  border-radius: 2px;
  transition: background-color 0.2s;
  word-break: break-all;
  word-wrap: break-word;
  max-width: 100%;
  display: inline-block;
  line-height: 1.3;
  margin: 0;
}

/* 各属性的特定颜色 */
.element-class {
  color: #e6a23c;
  font-weight: 500;
}

.element-class:hover {
  background: #fdf6ec;
}

.element-text {
  color: #409eff;
}

.element-text:hover {
  background: #ecf5ff;
}

.element-id {
  color: #f56c6c;
}

.element-id:hover {
  background: #fef0f0;
}

.coordinates,
.bounds {
  color: #909399;
  font-family: monospace;
}

.coordinates:hover,
.bounds:hover {
  background: #f4f4f5;
}

.bounds {
  font-size: 10px;
}

.clickable {
  color: #67c23a;
  background: #f0f9ff;
  padding: 1px 3px;
  border-radius: 2px;
  font-size: 10px;
}

.disabled {
  color: #f56c6c;
  background: #fef0f0;
  padding: 1px 3px;
  border-radius: 2px;
  font-size: 10px;
}
</style>
