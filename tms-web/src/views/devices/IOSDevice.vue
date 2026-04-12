<template>
  <div class="device-connection">
    <!-- 主要内容区域 -->
    <div class="main-content">
      <!-- 左侧连接信息区域 -->
      <div class="connection-info-area">
        <div class="info-content">
          <!-- 连接配置 -->
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

          <!-- 元素信息卡片 -->
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
          <!-- 功能按钮（与 Android 对齐：唤醒、HOME、截屏、Dump XML、元素检查器） -->
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
                @mousedown.prevent="handleMouseDown"
                @mousemove="handleMouseMove"
                @mouseup.prevent="handleMouseUp"
                @mouseleave="handleMouseLeave"
                @wheel.prevent="handleWheel"
                @touchstart.prevent="handleTouchStart"
                @touchmove.prevent="handleTouchMove"
                @touchend.prevent="handleTouchEnd"
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
                <!-- 如果有元素信息，显示元素详情 -->
                <div v-if="log.element" class="element-info">
                  <!-- 文本信息单独一行 -->
                  <div v-if="log.element.label || log.element.name" class="element-text-row">
                    <span class="element-text" @click="copyToClipboard(log.element.label || log.element.name, '文本')">
                      <span class="property-label">文本:</span>{{ log.element.label || log.element.name }}
                    </span>
                  </div>
                  <!-- 其他属性信息 -->
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
                <!-- 如果没有元素信息，只显示坐标 -->
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
import { Camera, Document, Loading, Sunny, HomeFilled, Monitor, Search, Delete, InfoFilled } from '@element-plus/icons-vue'
import { deviceApi } from '@/api/device'
import { useUserStore } from '@/stores/user'
import config from '@/config/index.js'
import pako from 'pako'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 设备序列号（用于 WebSocket 路径，来自路由）
const deviceSerial = ref(route.query.serial || '')

// 设备 ID（用于接口与释放）
const deviceId = ref(route.params.id ? Number(route.params.id) : null)

// 连接信息（与 Android 同结构：代理用 proxy_*，iOS 下 adb_host/adb_port 表示 WDA；mjpeg_port 仅 agent 内部使用，web 不关心）
const connectionInfo = reactive({
  proxyHost: '',
  proxyPort: '',
  adbHost: '',
  adbPort: ''
})

// 加载状态
const loading = ref(true)

// 连接状态
const isConnected = ref(false)
let controlWs = null
let screenWs = null
let inspectorWs = null

// 视频分辨率（与真实屏幕一致，用于投屏展示比例与坐标换算）
const videoResolution = reactive({
  width: 0,
  height: 0,
  aspectRatio: 9 / 16
})

// Canvas 与容器引用
const screenCanvas = ref(null)
const videoWrapperRef = ref(null)
let canvasContext = null

// 鼠标/触摸状态
const mouseState = reactive({
  isDown: false,
  startX: 0,
  startY: 0,
  beganAt: null  // 按下时间，用于区分点击/长按
})

// 操作进行中标志，防止重复操作
let operationPending = false

// 滚轮防抖
let wheelDebounceTimer = null
let wheelAccumulatedY = 0
let wheelStartCoords = null

let resizeTimer = null

// 设备占用心跳
let holdWs = null
let holdHeartbeatTimer = null

// 元素检查器相关
const elementInspectorEnabled = ref(false)
const operationLogs = ref([])
const hoverElement = ref(null)
const uiHierarchy = ref(null)
let xmlCheckTimer = null
let lastXmlHash = ref('')
let elementHoverTimer = null

// 获取 WDA URL（iOS 使用 adb_host/adb_port 表示 WDA 服务地址）
const getWDAUrl = () => {
  if (!connectionInfo.adbHost || !connectionInfo.adbPort) {
    return '-'
  }
  return `http://${connectionInfo.adbHost}:${connectionInfo.adbPort}`
}

// 从后端获取设备连接信息（与 Android 同结构：proxy 为代理，iOS 下 adb_host/adb_port 为 WDA）
const fetchConnectionInfo = async () => {
  if (!deviceId.value) return
  loading.value = true
  try {
    const res = await deviceApi.getDeviceConnection(deviceId.value)
    if (res.code === 0 && res.data) {
      const d = res.data
      connectionInfo.proxyHost = d.proxyHost || ''
      connectionInfo.proxyPort = d.proxyPort != null ? String(d.proxyPort) : ''
      connectionInfo.adbHost = d.adbHost || ''
      connectionInfo.adbPort = d.adbPort != null ? String(d.adbPort) : ''
    }
  } catch (e) {
    console.error('获取连接信息失败:', e)
    ElMessage.error('获取连接信息失败')
  } finally {
    loading.value = false
  }
}

// 复制 WDA 命令
const copyWDACommand = () => {
  const url = getWDAUrl()
  if (url === '-') return

  navigator.clipboard.writeText(url).then(() => {
    ElMessage.success('WDA 地址已复制')
  })
}

// 与 Android 一致：按可视区域与真实分辨率比例计算投屏展示尺寸
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

    // iOS 设备逻辑分辨率按比例缩放一下，这样在页面上看着更真实
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
    }
  } catch (e) {
    console.error('adjustScreenContainer:', e)
  }
}

// 发送控制消息（与 Android 一致）
const sendControlMessage = (message) => {
  if (controlWs && controlWs.readyState === WebSocket.OPEN) {
    controlWs.send(JSON.stringify(message))
  } else {
    ElMessage.error('设备控制连接未建立，无法操作设备')
  }
}

// 连接设备（通过代理服务与 agent 建立 WebSocket）
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

// 连接控制 WebSocket（经代理转发到 agent）
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
        resolveControlResponse(data)
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

// 连接投屏 WebSocket（经代理转发到 agent）
const connectScreenWebSocket = () => {
  return new Promise((resolve, reject) => {
    const wsUrl = `ws://${connectionInfo.proxyHost}:${connectionInfo.proxyPort}/devices/${deviceSerial.value}/screen`
    screenWs = new WebSocket(wsUrl)
    screenWs.binaryType = 'arraybuffer'  // 接收二进制数据

    screenWs.onopen = () => {
      console.log('投屏 WebSocket 连接成功')
      // 投屏经 proxy 连接，agent 内部从 device_manager 获取 mjpeg_port，web 无需传递
      screenWs.send(JSON.stringify({ type: 'start_stream' }))
      resolve()
    }

    screenWs.onmessage = (event) => {
      try {
        // 处理二进制 JPEG 帧
        if (event.data instanceof ArrayBuffer) {
          renderBinaryFrame(event.data)
        } else {
          // 处理 JSON 消息
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

// 处理控制消息
const handleControlMessage = (data) => {
  switch (data.type) {
    case 'connected':
      if (data.device_resolution) {
        const w = data.device_resolution[0]
        const h = data.device_resolution[1]
        videoResolution.width = w
        videoResolution.height = h
        videoResolution.aspectRatio = w / h
        setTimeout(() => {
          if (screenCanvas.value) {
            canvasContext = screenCanvas.value.getContext('2d')
          }
        }, 100)
        nextTick(() => adjustScreenContainer())
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

// 处理投屏消息
const handleScreenMessage = (data) => {
  switch (data.type) {
    case 'stream_started':
      console.log('投屏已启动，FPS:', data.fps || 30)
      break
    case 'stream_stopped':
      console.log('投屏已停止')
      break
  }
}

// 渲染二进制 JPEG 帧
const renderBinaryFrame = (arrayBuffer) => {
  if (!canvasContext) return

  // 将 ArrayBuffer 转换为 Blob
  const blob = new Blob([arrayBuffer], { type: 'image/jpeg' })
  const url = URL.createObjectURL(blob)

  const img = new Image()
  img.onload = () => {
    canvasContext.drawImage(img, 0, 0, videoResolution.width, videoResolution.height)
    URL.revokeObjectURL(url)  // 释放内存
  }
  img.onerror = () => {
    URL.revokeObjectURL(url)
  }
  img.src = url
}

// 将事件坐标转换为设备坐标（与 Android 一致：按显示尺寸与真实分辨率换算）
const getEventDeviceCoords = (clientX, clientY) => {
  const canvas = screenCanvas.value
  if (!canvas || !videoResolution.width || !videoResolution.height) return null
  const rect = canvas.getBoundingClientRect()
  if (rect.width <= 0 || rect.height <= 0) return null
  const scaleX = videoResolution.width / rect.width
  const scaleY = videoResolution.height / rect.height
  const x = (clientX - rect.left) * scaleX
  const y = (clientY - rect.top) * scaleY
  if (x < 0 || y < 0 || x > videoResolution.width || y > videoResolution.height) return null
  return { x: Math.round(x), y: Math.round(y) }
}

const handleMouseDown = (event) => {
  if (!isConnected.value || operationPending) return
  event.preventDefault()
  const coords = getEventDeviceCoords(event.clientX, event.clientY)
  if (!coords) return
  mouseState.isDown = true
  mouseState.startX = coords.x
  mouseState.startY = coords.y
  mouseState.beganAt = Date.now()
}

const handleMouseMove = (event) => {
  // 如果启用了元素检查器且没有按下鼠标，进行元素查找
  if (!mouseState.isDown && elementInspectorEnabled.value && !operationPending) {
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

  if (!isConnected.value || !mouseState.isDown) return
  // iOS WDA 仅支持 tap/swipe 两种手势，拖动过程不发送中间点
}

const handleMouseLeave = () => {
  if (elementInspectorEnabled.value) {
    hoverElement.value = null
  }
  if (!operationPending) {
    mouseState.isDown = false
  }
}

const handleMouseUp = (event) => {
  if (!isConnected.value || !mouseState.isDown || operationPending) return
  event.preventDefault()

  const coords = getEventDeviceCoords(event.clientX, event.clientY)
  const endX = coords ? coords.x : mouseState.startX
  const endY = coords ? coords.y : mouseState.startY
  const moveX = Math.abs(endX - mouseState.startX)
  const moveY = Math.abs(endY - mouseState.startY)
  const duration = Date.now() - mouseState.beganAt

  mouseState.isDown = false

  // 禁用触摸，等待 WDA 响应后恢复
  disableCanvasTouch()

  let promise
  if (moveX < 10 && moveY < 10) {
    if (duration < 200) {
      // 短按 → 点击
      promise = sendClick(mouseState.startX, mouseState.startY)
    } else {
      // 长按
      promise = sendLongClick(mouseState.startX, mouseState.startY, duration)
    }
  } else {
    // 滑动
    promise = sendSwipe(mouseState.startX, mouseState.startY, endX, endY)
  }

  promise.finally(() => {
    enableCanvasTouch()
  })
}

// 禁用 canvas 触摸（操作进行中）
const disableCanvasTouch = () => {
  operationPending = true
  const canvas = screenCanvas.value
  if (canvas) {
    canvas.style.pointerEvents = 'none'
    canvas.style.cursor = 'not-allowed'
  }
}

// 恢复 canvas 触摸
const enableCanvasTouch = () => {
  operationPending = false
  const canvas = screenCanvas.value
  if (canvas) {
    canvas.style.pointerEvents = ''
    canvas.style.cursor = ''
  }
}

// 鼠标滚轮 → 转换为 swipe 手势（模拟页面滚动）
const handleWheel = (event) => {
  if (!isConnected.value) return

  // 记录滚动开始时的坐标（只在第一次滚动时记录）
  if (!wheelStartCoords) {
    const coords = getEventDeviceCoords(event.clientX, event.clientY)
    if (!coords) return
    wheelStartCoords = coords
  }

  // 累积滚动量
  wheelAccumulatedY += event.deltaY

  // 清除之前的防抖定时器
  if (wheelDebounceTimer) {
    clearTimeout(wheelDebounceTimer)
  }

  // 设置新的防抖定时器，在滚动停止 150ms 后执行
  wheelDebounceTimer = setTimeout(() => {
    if (!wheelStartCoords) return

    const scrollAmount = wheelAccumulatedY
    wheelAccumulatedY = 0

    if (Math.abs(scrollAmount) < 5) {
      wheelStartCoords = null
      wheelDebounceTimer = null
      return
    }

    // 将滚轮偏移量映射为设备上的滑动距离（屏幕高度的 1/4 为上限）
    const maxSwipe = videoResolution.height * 0.25
    const swipeDistance = Math.min(Math.abs(scrollAmount) * 1.5, maxSwipe)
    const direction = scrollAmount > 0 ? 1 : -1

    const startX = wheelStartCoords.x
    const startY = wheelStartCoords.y
    const endX = startX
    const endY = Math.max(0, Math.min(videoResolution.height, startY - direction * swipeDistance))

    sendSwipe(startX, startY, endX, endY)

    // 重置状态
    wheelStartCoords = null
    wheelDebounceTimer = null
  }, 150)
}

// 触摸事件
const handleTouchStart = (event) => {
  if (event.touches.length === 0) return
  handleMouseDown({ clientX: event.touches[0].clientX, clientY: event.touches[0].clientY, preventDefault: () => {} })
}

const handleTouchMove = (event) => {
  if (event.touches.length === 0) return
  // 仅保持按下状态，不发送中间点
}

const handleTouchEnd = (event) => {
  if (event.changedTouches.length === 0) return
  const t = event.changedTouches[0]
  handleMouseUp({ clientX: t.clientX, clientY: t.clientY, preventDefault: () => {} })
}

// 发送点击 - 返回 Promise，等待 WDA 响应
const sendClick = (x, y) => {
  // 记录操作日志
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

  return new Promise((resolve) => {
    pendingResolve = resolve
    sendControlMessage({ type: 'click', x, y })
    // 超时兜底，防止永远卡住
    setTimeout(resolve, 5000)
  })
}

// 发送长按 - 返回 Promise
const sendLongClick = (x, y, duration) => {
  return new Promise((resolve) => {
    pendingResolve = resolve
    sendControlMessage({ type: 'long_click', x, y, duration: duration / 1000 })
    setTimeout(resolve, 5000)
  })
}

// 发送滑动 - 返回 Promise，duration 100ms（参考实现验证的最佳值）
const sendSwipe = (startX, startY, endX, endY) => {
  // 记录操作日志
  if (elementInspectorEnabled.value) {
    const logData = {
      coordinates: `从(${startX}, ${startY})到(${endX}, ${endY})`,
      timestamp: new Date()
    }

    // 尝试查找起始位置的元素
    const startElement = findElementAtPosition(startX, startY)
    if (startElement) {
      logData.element = { ...startElement }
    } else if (hoverElement.value) {
      logData.element = { ...hoverElement.value }
    }

    addOperationLog('滑动操作', logData)
    scheduleXmlCheck(300)
  }

  return new Promise((resolve) => {
    pendingResolve = resolve
    sendControlMessage({
      type: 'swipe',
      start_x: startX,
      start_y: startY,
      end_x: endX,
      end_y: endY,
      duration: 0.1
    })
    setTimeout(resolve, 5000)
  })
}

// 等待 WDA 操作完成的回调
let pendingResolve = null

// 控制消息响应处理（在 controlWs.onmessage 中调用）
const resolveControlResponse = (msg) => {
  const resultTypes = ['click_result', 'long_click_result', 'swipe_result']
  if (resultTypes.includes(msg.type) && pendingResolve) {
    const resolve = pendingResolve
    pendingResolve = null
    resolve()
  }
}

// 截屏（与 Android 一致，通过控制 WebSocket）
const handleScreenshot = () => {
  sendControlMessage({ type: 'screenshot' })
}

// Dump XML（与 Android 一致）
const handleDumpXml = () => {
  sendControlMessage({ type: 'dump_hierarchy' })
}

// HOME 键（与 Android 一致，经 agent 调用 WDA homescreen）
const handleHomeKey = () => {
  sendControlMessage({ type: 'home' })
}

// 唤醒屏幕（与 Android 一致，经 agent 调用 WDA/设备）
const handleWakeScreen = () => {
  sendControlMessage({ type: 'wake_screen' })
}

// 下载截图
const downloadScreenshot = (base64Image) => {
  const link = document.createElement('a')
  link.href = `data:image/png;base64,${base64Image}`
  link.download = `ios_screenshot_${Date.now()}.png`
  link.click()
  ElMessage.success('截图已保存')
}

// 下载 XML（由 dump_hierarchy 结果回调，后端返回 gzip+base64 编码）
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

// 释放设备
const releaseDevice = async () => {
  try {
    await ElMessageBox.confirm('确定要释放此设备吗？', '提示', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning'
    })
    if (!deviceId.value) {
      ElMessage.error('设备 ID 无效')
      return
    }
    const res = await deviceApi.deviceHold({ id: deviceId.value, holder: null })
    if (res.code !== 0) {
      ElMessage.error(res.msg || '释放失败')
      return
    }
    if (controlWs) controlWs.close()
    if (screenWs) screenWs.close()
    if (inspectorWs) inspectorWs.close()
    isConnected.value = false
    ElMessage.success('设备已释放')
    router.push({ name: 'Devices' })
  } catch (error) {
    if (error !== 'cancel') {
      console.error('释放设备失败:', error)
      ElMessage.error('释放设备失败')
    }
  }
}

// 窗口 resize 时重新计算投屏展示尺寸（与 Android 一致，防抖）
const handleWindowResize = () => {
  if (resizeTimer) clearTimeout(resizeTimer)
  resizeTimer = setTimeout(() => {
    if (videoResolution.width && videoResolution.height) {
      nextTick(() => adjustScreenContainer())
    }
  }, 100)
}

// ========== 元素检查器功能 ==========

// 连接元素检查器 WebSocket
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

// 处理检查器消息
const handleInspectorMessage = (data) => {
  switch (data.type) {
    case 'connected':
      console.log('元素检查器已连接')
      break
    case 'ui_hierarchy':
    case 'xml_only':
      // 处理 XML 内容
      if (data.success && data.data && data.data.xml) {
        decompressXml(data.data).then(xmlContent => {
          const currentXmlHash = generateXmlHash(xmlContent)
          if (currentXmlHash && currentXmlHash !== lastXmlHash.value) {
            lastXmlHash.value = currentXmlHash
            // 解析 XML 为树结构
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

// 切换元素检查器
const toggleElementInspector = async () => {
  elementInspectorEnabled.value = !elementInspectorEnabled.value
  if (elementInspectorEnabled.value) {
    // 开启时连接 inspector WebSocket
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
  } else {
    // 关闭时清理状态并断开连接
    hoverElement.value = null
    uiHierarchy.value = null
    if (inspectorWs) {
      inspectorWs.close()
      inspectorWs = null
    }
  }
}

// 刷新 UI 层次结构
const refreshUIHierarchy = () => {
  if (!inspectorWs || inspectorWs.readyState !== WebSocket.OPEN) return
  inspectorWs.send(JSON.stringify({ type: 'get_xml_only' }))
}

// 解压缩 XML 数据
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
      const decompressed = pako.inflate(bytes, { to: 'string' })
      return decompressed
    } else {
      return data.xml
    }
  } catch (error) {
    console.error('解压 XML 失败:', error)
    throw error
  }
}

// 生成 XML 哈希（用于检测变化）
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

// 解析 iOS XML 为树结构
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

// 递归解析 XML 节点
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

  // 计算边界字符串
  const x = parseInt(element.x)
  const y = parseInt(element.y)
  const w = parseInt(element.width)
  const h = parseInt(element.height)
  element.rect = `[${x},${y}][${x + w},${y + h}]`

  // 解析子节点
  for (let i = 0; i < node.children.length; i++) {
    const child = parseXmlNode(node.children[i])
    if (child) {
      element.children.push(child)
    }
  }

  return element
}

// 查找指定坐标的元素
const findElementAtPosition = (x, y) => {
  if (!uiHierarchy.value) return null

  const element = findSmallestElementAt(uiHierarchy.value, x, y)
  hoverElement.value = element
  return element
}

// 递归查找最小匹配元素
const findSmallestElementAt = (node, x, y) => {
  if (!node) return null

  const allMatches = collectAllMatchingElements(node, x, y)

  if (allMatches.length === 0) return null

  // 找到面积最小的元素
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

// 收集所有匹配的元素
const collectAllMatchingElements = (node, x, y, matches = []) => {
  if (!node) return matches

  const nx = parseInt(node.x)
  const ny = parseInt(node.y)
  const nw = parseInt(node.width)
  const nh = parseInt(node.height)

  // 检查点是否在元素内
  if (x >= nx && x <= nx + nw && y >= ny && y <= ny + nh) {
    matches.push({
      element: node,
      area: nw * nh
    })

    // 继续查找子节点
    if (node.children && node.children.length > 0) {
      for (const child of node.children) {
        collectAllMatchingElements(child, x, y, matches)
      }
    }
  }

  return matches
}

// 添加操作日志
const addOperationLog = (action, data) => {
  operationLogs.value.unshift({
    action,
    ...data
  })

  // 限制日志数量
  if (operationLogs.value.length > 5) {
    operationLogs.value = operationLogs.value.slice(0, 5)
  }
}

// 清空操作日志
const clearOperationLogs = () => {
  operationLogs.value = []
  ElMessage.success('操作日志已清空')
}

// 格式化时间
const formatTime = (timestamp) => {
  const date = new Date(timestamp)
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const seconds = String(date.getSeconds()).padStart(2, '0')
  return `${hours}:${minutes}:${seconds}`
}

// 复制到剪贴板
const copyToClipboard = (text, label) => {
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success(`${label}已复制`)
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

// 调度 XML 检查
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
  const wsUrl = config.baseURL.replace('http', 'ws') + '/ws/device/hold'
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
      username: userStore.userInfo?.username || ''
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

onMounted(() => {
  connectDevice()
  window.addEventListener('resize', handleWindowResize)
  // 启动设备占用心跳
  if (deviceSerial.value) {
    startHoldHeartbeat()
  }
})

onBeforeUnmount(() => {
  if (resizeTimer) clearTimeout(resizeTimer)
  if (wheelDebounceTimer) clearTimeout(wheelDebounceTimer)
  if (xmlCheckTimer) clearTimeout(xmlCheckTimer)
  if (elementHoverTimer) clearTimeout(elementHoverTimer)
  stopHoldHeartbeat()
  window.removeEventListener('resize', handleWindowResize)
  if (controlWs) controlWs.close()
  if (screenWs) screenWs.close()
  if (inspectorWs) inspectorWs.close()
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
  overflow-y: auto;
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
