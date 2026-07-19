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
                <el-tag v-else-if="loading || connecting" type="info">连接中...</el-tag>
                <el-tag v-else type="danger">未连接</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="代理服务">
                {{ connectionInfo.proxyHost ? connectionInfo.proxyHost + ":" + connectionInfo.proxyPort : '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="本地调试">
                <el-tag type="success" @click="copyCommand" style="cursor: pointer;">
                  {{ getDebugCommand() }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="本地连接">
                <el-tag type="success" @click="copyConnectionCommand" style="cursor: pointer;">
                  {{ connectionInfo.connection ? "adb connect " + connectionInfo.connection : '-' }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>

          <!-- 应用管理面板 -->
          <DeviceAppPanel
            v-if="appManagerEnabled"
            :proxy-host="connectionInfo.proxyHost"
            :proxy-port="connectionInfo.proxyPort"
            :serial="connectionInfo.serial"
            platform="android"
          />

          <!-- 元素属性面板 -->
          <el-card v-if="elementInspectorEnabled" class="info-card">
            <template #header>
              <div class="card-header">
                <h3>元素属性</h3>
              </div>
            </template>
            <div class="properties-content">
              <div v-if="selectedElement || hoverElement" class="element-info">
                <el-descriptions :column="1" border size="small">
                  <el-descriptions-item label="类名">
                    {{ (selectedElement || hoverElement)?.class || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="文本">
                    {{ (selectedElement || hoverElement)?.text || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="资源ID">
                    {{ (selectedElement || hoverElement)?.resource_id || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="内容描述">
                    {{ (selectedElement || hoverElement)?.content_desc || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="坐标">
                    {{ (selectedElement || hoverElement)?.bounds || '-' }}
                  </el-descriptions-item>
                  <el-descriptions-item label="可点击">
                    <el-tag :type="(selectedElement || hoverElement)?.clickable ? 'success' : 'info'" size="small">
                      {{ (selectedElement || hoverElement)?.clickable ? '是' : '否' }}
                    </el-tag>
                  </el-descriptions-item>
                  <el-descriptions-item label="可用">
                    <el-tag :type="(selectedElement || hoverElement)?.enabled ? 'success' : 'danger'" size="small">
                      {{ (selectedElement || hoverElement)?.enabled ? '是' : '否' }}
                    </el-tag>
                  </el-descriptions-item>
                  <el-descriptions-item label="可滚动">
                    <el-tag :type="(selectedElement || hoverElement)?.scrollable ? 'success' : 'info'" size="small">
                      {{ (selectedElement || hoverElement)?.scrollable ? '是' : '否' }}
                    </el-tag>
                  </el-descriptions-item>
                </el-descriptions>
                
                <!-- 元素操作按钮 -->
                <div class="element-actions" v-if="selectedElement && (selectedElement.clickable || selectedElement.enabled)">
                  <el-button 
                    size="small" 
                    type="primary" 
                    @click="performElementAction('click')"
                    :disabled="!selectedElement.clickable"
                  >
                    点击
                  </el-button>
                  <el-button 
                    size="small" 
                    type="warning" 
                    @click="performElementAction('long_click')"
                    :disabled="!selectedElement.clickable"
                  >
                    长按
                  </el-button>
                  <el-button 
                    v-if="isInputElement(selectedElement)"
                    size="small" 
                    type="success" 
                    @click="showInputDialog"
                  >
                    输入文本
                  </el-button>
                </div>
              </div>
              
              <div v-else class="no-selection">
                <el-icon size="24"><InfoFilled /></el-icon>
                <p>请点击或悬停选择元素</p>
              </div>
            </div>
          </el-card>
        </div>
      </div>

      <!-- 中间投屏区域 -->
      <div class="screen-area">
        <div class="screen-header">
          <h3>设备投屏</h3>
        </div>

        <div class="screen-main">
          <div class="side-controls" v-if="isConnected && videoResolution.width > 0">
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
              type="warning"
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

          <div class="screen-container">
            <div v-if="connecting || loading" class="screen-placeholder">
              <el-icon size="48" color="#409EFF">
                <Monitor />
              </el-icon>
              <p>正在连接设备...</p>
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
              <!-- 投屏画面（WebCodecs 解码 → canvas） -->
              <canvas
                id="screen-player"
                ref="screenCanvas"
                class="screen-video"
                :style="{ aspectRatio: videoResolution.aspectRatio }"
                @click="handleScreenClick"
                @pointerdown="handleScreenPointerDown"
                @pointermove="handleScreenPointerMove"
                @pointerup="handleScreenPointerUp"
                @pointercancel="handleScreenPointerCancel"
                @pointerleave="handleScreenPointerLeave"
                @wheel.prevent="handleScreenWheel"
              ></canvas>

            </div>
          </div>
        </div>
      </div>

      <!-- 右侧XML树面板 -->
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
                  <!-- 文本信息单独一行 -->
                  <div v-if="log.element.text" class="element-text-row">
                    <span class="element-text" @click="copyToClipboard(log.element.text, '文本')">
                      <span class="property-label">文本:</span>{{ log.element.text }}
                    </span>
                  </div>
                  <!-- 其他属性信息 -->
                  <div class="element-main">
                    <span v-if="log.element.class" class="element-class" @click="copyToClipboard(log.element.class, '类名')">
                      <span class="property-label">类名:</span>{{ log.element.class }}
                    </span>
                    <span v-if="log.element.resource_id" class="element-id" @click="copyToClipboard(log.element.resource_id, '资源ID')">
                      <span class="property-label">ID:</span>{{ log.element.resource_id }}
                    </span>
                  </div>
                  <div class="element-meta">
                    <span v-if="log.coordinates" class="coordinates" @click="copyToClipboard(log.coordinates, '坐标')">
                      <span class="property-label">坐标:</span>{{ log.coordinates }}
                    </span>
                    <span v-if="log.element.bounds" class="bounds" @click="copyToClipboard(log.element.bounds, '边界')">
                      <span class="property-label">边界:</span>{{ log.element.bounds }}
                    </span>
                    <span v-if="log.element.clickable === 'true'" class="clickable">可点击</span>
                    <span v-if="log.element.enabled === 'false'" class="disabled">禁用</span>
                  </div>
                </div>
                <!-- 无元素信息时只显示坐标或按键 -->
                <div v-else class="action-info">
                  <span v-if="log.coordinates" class="coordinates" @click="copyToClipboard(log.coordinates, '坐标')">
                    <span class="property-label">坐标:</span>{{ log.coordinates }}
                  </span>
                  <span v-if="log.key" class="key-info" @click="copyToClipboard(log.key, '按键')">
                    <span class="property-label">按键:</span>{{ log.key }}
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

<script>
import { ref, reactive, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Monitor, Camera, HomeFilled, Document, Sunny, Search, InfoFilled, Delete, Grid } from '@element-plus/icons-vue'
import { deviceApi } from '@/api/device.js'
import DeviceAppPanel from '@/views/devices/DeviceAppPanel.vue'
import { useUserStore } from '@/stores/user'
import { useDeviceSessionStore } from '@/stores/deviceSession.js'
import { useIdleRelease } from '@/composables/useIdleRelease'
import pako from 'pako'
import { ScrcpyController, Action, WebCodecsPlayer } from '@/utils/device'
import config from '@/config/index.js'

const genSessionId = () => {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') {
    return window.crypto.randomUUID()
  }
  return 'sess-' + Math.random().toString(36).slice(2) + Date.now().toString(36)
}

export default {
  name: 'AndroidDevice',
  components: {
    Monitor,
    Camera,
    HomeFilled,
    Document,
    Sunny,
    Search,
    InfoFilled,
    Delete,
    Grid,
    DeviceAppPanel
  },
  setup() {
    const route = useRoute()
    const router = useRouter()
    const userStore = useUserStore()
    const deviceSessionStore = useDeviceSessionStore()

    // 本页会话令牌：优先沿用列表页占用时的 sessionId，否则 onMounted 时自行占用
    let sessionId = deviceSessionStore.getSession(route.params.id)

    // 设备占用心跳
    let holdWs = null
    let holdHeartbeatTimer = null

    const loading = ref(false)
    const connecting = ref(false)
    const isConnected = ref(false)
    const screenCanvas = ref(null)

    let isMouseDown = false
    let startCoords = null
    let lastMoveTime = 0
    let touchAction = null
    let hasDragged = false
    let activePointerId = null
    const dragThreshold = 10

    const videoResolution = reactive({
      width: 0,
      height: 0,
      aspectRatio: 16/9 // 默认比例
    })

    // 设备真实窗口尺寸
    const deviceWindowSize = reactive({
      width: 0,
      height: 0
    })

    const connectionInfo = reactive({
      id: route.params.id,
      deviceName: route.query.name,
      serial: route.query.serial || '',

      adbHost: '',
      adbPort: '',
      proxyHost: '',
      proxyPort: '',
      connection: '',
    })

    let scrcpyWs = null
    let controlWs = null
    let inspectorWs = null
    let player = null
    let streamFps = 25 // 由 agent 在 stream_started 上报，作为编码帧率的单一来源

    const scrcpy = new ScrcpyController()

    const elementInspectorEnabled = ref(false)
    const appManagerEnabled = ref(false)     // 应用管理面板开关（与检查器互斥）
    const operationLogs = ref([])
    const selectedElement = ref(null)
    const hoverElement = ref(null)
    const uiHierarchy = ref(null)
    const lastXmlHash = ref('')
    let xmlChangeTimer = null

    const getConnectionInfo = async () => {
      try {
        const response = await deviceApi.getDeviceConnection(route.params.id)
        // 代理未起时 data 可能为 null，静默等待轮询
        if (response.code === 0 && response.data) {
          const { id: _connId, ...connData } = response.data // eslint-disable-line no-unused-vars
          Object.assign(connectionInfo, connData)
        }
      } catch (error) {
        // 轮询期间静默
      }
      return !!(connectionInfo.proxyHost && connectionInfo.proxyPort && connectionInfo.serial)
    }

    // 有界轮询等待代理就绪
    const waitForConnectionReady = async (timeoutMs = 40000, intervalMs = 1500) => {
      loading.value = true
      const start = Date.now()
      try {
        while (Date.now() - start < timeoutMs) {
          if (await getConnectionInfo()) return true
          await new Promise(r => setTimeout(r, intervalMs))
        }
        return await getConnectionInfo()
      } finally {
        loading.value = false
      }
    }
    
    const connectDevice = async () => {
      disconnectWebSocket()
      await getConnectionInfo()
      if (!isConnected.value && !connecting.value) {
        connectWebSocket()
      }
    }

    // 静默释放：调后端释放 + 断连清理，供手动释放与空闲释放复用
    const teardownAndRelease = async () => {
      const res = await deviceApi.deviceHold({
        id: connectionInfo.id,
        holder: null,
        sessionId
      })
      if (res.code !== 0) {
        ElMessage.error('设备释放失败')
        return false
      }
      deviceSessionStore.clearSession(route.params.id)
      disconnectWebSocket()
      stopHoldHeartbeat()
      if (resizeTimer) {
        clearTimeout(resizeTimer)
        resizeTimer = null
      }
      if (window.elementHoverTimer) {
        clearTimeout(window.elementHoverTimer)
        window.elementHoverTimer = null
      }
      stopXmlChangeDetection()
      if (player) {
        player.close()
        player = null
      }
      isConnected.value = false
      connecting.value = false
      elementInspectorEnabled.value = false
      appManagerEnabled.value = false
      selectedElement.value = null
      hoverElement.value = null
      uiHierarchy.value = null
      operationLogs.value = []
      lastXmlHash.value = ''
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
          router.push({ name: 'Devices' })
        }
      } catch (error) {
        console.error('设备释放过程中出现错误:', error)
        ElMessage.error('设备释放失败: ' + error.message)
      }
    }

    // 长时间切走页面则自动释放并在回到页面时提示
    useIdleRelease({
      isActive: () => isConnected.value,
      release: teardownAndRelease,
      onReleased: () => router.push({ name: 'Devices' })
    })

    const connectWebSocket = () => {
      if (!connectionInfo.proxyHost || !connectionInfo.proxyPort || !connectionInfo.serial) {
        ElMessage.error('连接信息不完整，无法建立连接')
        return
      }
      
      connecting.value = true

      connectScrcpyWebSocket()
      connectControlWebSocket()

      if (elementInspectorEnabled.value) {
        connectInspectorWebSocket()
      }
    }

    const connectScrcpyWebSocket = () => {
      try {
        const wsUrl = `ws://${connectionInfo.proxyHost}:${connectionInfo.proxyPort}/devices/${connectionInfo.serial}/scrcpy`
        scrcpyWs = new WebSocket(wsUrl)
        scrcpyWs.binaryType = 'arraybuffer'
        scrcpy.bind(scrcpyWs)

        scrcpyWs.onopen = () => {
          isConnected.value = true
          connecting.value = false
          ElMessage.success('投屏连接成功')

          // 同步安装 onmessage 再请求推流，避免 stream_started 早于处理器到达而丢失
          initMirrorDisplay()
          scrcpyWs.send(JSON.stringify({
            type: 'start_stream'
          }))
        }

        scrcpyWs.onclose = () => {
          isConnected.value = false
          connecting.value = false
        }

        scrcpyWs.onerror = () => {
          connecting.value = false
          ElMessage.error('投屏连接失败')
        }
        
      } catch (error) {
        connecting.value = false
        ElMessage.error('投屏连接失败')
      }
    }
    
    const connectControlWebSocket = () => {
      try {
        const wsUrl = `ws://${connectionInfo.proxyHost}:${connectionInfo.proxyPort}/devices/${connectionInfo.serial}/control`

        controlWs = new WebSocket(wsUrl)

        controlWs.onmessage = (event) => {
          try {
            const message = JSON.parse(event.data)
            handleControlMessage(message)
          } catch (error) {
            console.error('控制消息解析失败', error)
          }
        }

        controlWs.onerror = (error) => {
          console.error('设备控制连接失败:', error)
        }
        
      } catch (error) {
        console.error('设备控制连接失败:', error)
      }
    }
    
    const connectInspectorWebSocket = () => {
      try {
        const wsUrl = `ws://${connectionInfo.proxyHost}:${connectionInfo.proxyPort}/devices/${connectionInfo.serial}/inspector`

        inspectorWs = new WebSocket(wsUrl)

        inspectorWs.onmessage = (event) => {
          try {
            const message = JSON.parse(event.data)
            handleInspectorMessage(message)
          } catch (error) {
            console.error('检查器消息解析失败', error)
          }
        }

        inspectorWs.onerror = (error) => {
          console.error('元素检查器连接失败:', error)
        }
        
      } catch (error) {
        console.error('元素检查器连接失败:', error)
      }
    }
    
    const disconnectWebSocket = () => {
      if (scrcpyWs) {
        try {
          if (scrcpyWs.readyState === WebSocket.OPEN || scrcpyWs.readyState === WebSocket.CONNECTING) {
            scrcpyWs.close(1000, '主动断开连接')
          }
        } catch (error) {
          console.error('关闭投屏WebSocket失败:', error)
        }
        scrcpyWs = null
        scrcpy.unbind()
      }

      if (controlWs) {
        try {
          if (controlWs.readyState === WebSocket.OPEN || controlWs.readyState === WebSocket.CONNECTING) {
            controlWs.close(1000, '主动断开连接')
          }
        } catch (error) {
          console.error('关闭控制WebSocket失败:', error)
        }
        controlWs = null
      }

      if (inspectorWs) {
        try {
          if (inspectorWs.readyState === WebSocket.OPEN || inspectorWs.readyState === WebSocket.CONNECTING) {
            inspectorWs.close(1000, '主动断开连接')
          }
        } catch (error) {
          console.error('关闭检查器WebSocket失败:', error)
        }
        inspectorWs = null
      }

      try {
        if (player) {
          player.close()
          player = null
        }
      } catch (error) {
        console.error('清理视频资源失败:', error)
      }

      isMouseDown = false
      startCoords = null
      activePointerId = null
      isConnected.value = false

      console.log('所有WebSocket连接已断开')
    }
    
    const handleWebSocketMessage = (message) => {
      switch (message.type) {
        case 'connected':
          break
        case 'stream_started':
          if (message.fps) {
            streamFps = message.fps
          }
          if (message.resolution) {
            const { width, height } = message.resolution
            videoResolution.width = width
            videoResolution.height = height
            videoResolution.aspectRatio = width / height

            scrcpy.setResolution(width, height)

            nextTick(() => {
              adjustScreenContainer()
            })
          }
          break
        case 'stream_stopped':
          break
        case 'device_disconnected':
          ElMessage.warning('设备已断开连接')
          isConnected.value = false
          stopHoldHeartbeat()
          break
        case 'error':
          ElMessage.error(`错误: ${message.message}`)
          break
        default:
          break
      }
    }
    
    const handleInspectorMessage = (message) => {
      switch (message.type) {
        case 'connected':
          if (message.device_resolution) {
            deviceWindowSize.width = message.device_resolution[0]
            deviceWindowSize.height = message.device_resolution[1]
          }
          refreshUIHierarchy()
          break
        case 'ui_hierarchy':
          if (message.success && message.data) {
            try {
              // 解析UI层次数据 - 后端返回的数据结构包含tree字段
              let hierarchyData
              if (typeof message.data === 'string') {
                hierarchyData = JSON.parse(message.data)
              } else {
                hierarchyData = message.data
              }

              uiHierarchy.value = hierarchyData.tree || hierarchyData

              if (hierarchyData.xml) {
                const newXmlHash = generateXmlHash(hierarchyData.xml)
                lastXmlHash.value = newXmlHash
              }
            } catch (error) {
              console.error('解析UI层次数据失败:', error)
            }
          } else {
            console.error('获取UI层次失败:', message.error)
          }
          break
        case 'element_action_result':
          if (message.success) {
            ElMessage.success(`操作成功: ${message.data.result}`)
          } else {
            ElMessage.error(`操作失败: ${message.error}`)
          }
          break
        case 'error':
          ElMessage.error(`检查器错误: ${message.message}`)
          break
        default:
          break
      }
    }
    
    const handleControlMessage = (message) => {
      switch (message.type) {
        case 'connected':
          if (message.device_resolution) {
            deviceWindowSize.width = message.device_resolution[0]
            deviceWindowSize.height = message.device_resolution[1]
          }
          break
        case 'screenshot_result':
          if (message.success && message.data) {
            try {
              const link = document.createElement('a')
              link.download = `screenshot_${connectionInfo.serial}_${Date.now()}.png`
              link.href = `data:image/png;base64,${message.data.image}`
              document.body.appendChild(link)
              link.click()
              document.body.removeChild(link)
              ElMessage.success('截图已保存')
            } catch (error) {
              ElMessage.error('截图下载失败')
            }
          } else {
            ElMessage.error('截图失败：' + (message.error || '未知错误'))
          }
          break
        case 'dump_hierarchy_result':
          if (message.success && message.data) {
            try {
              const xmlData = {
                xml: message.data.hierarchy,
                compressed: message.data.compressed,
                encoding: message.data.encoding
              }

              decompressXml(xmlData).then(xmlContent => {
                const blob = new Blob([xmlContent], { type: 'application/xml; charset=utf-8' })
                const url = window.URL.createObjectURL(blob)
                
                const link = document.createElement('a')
                link.download = `ui_hierarchy_${connectionInfo.serial}_${Date.now()}.xml`
                link.href = url
                document.body.appendChild(link)
                link.click()
                document.body.removeChild(link)
                
                window.URL.revokeObjectURL(url)
                ElMessage.success('UI层次结构已保存')
              }).catch(error => {
                ElMessage.error('UI层次结构处理失败: ' + error.message)
              })
            } catch (error) {
              ElMessage.error('UI层次结构处理失败: ' + error.message)
            }
          } else {
            ElMessage.error('UI层次结构获取失败：' + (message.error || '未知错误'))
          }
          break
        case 'xml_only':
          // 处理仅XML内容的响应（用于页面变化检测）
          if (message.success && message.data && message.data.xml) {
            decompressXml(message.data).then(xmlContent => {
              if (stabilityCheckTimer !== null || stabilityCheckCount > 0) {
                handleStabilityXmlResponse(xmlContent)
              } else {
                const currentXmlHash = generateXmlHash(xmlContent)
                if (currentXmlHash && currentXmlHash !== lastXmlHash.value) {
                  refreshUIHierarchy()
                }
              }
            }).catch(error => {
              console.error('处理XML数据失败:', error)
            })
          }
          break
        case 'error':
          ElMessage.error(`控制错误: ${message.message}`)
          break
        default:
          break
      }
    }
    
    const initMirrorDisplay = () => {
      try {
        if (scrcpyWs) {
          scrcpyWs.onmessage = (event) => {
            if (event.data instanceof ArrayBuffer) {
              if (!player) {
                if (!screenCanvas.value) return
                if (!WebCodecsPlayer.isSupported()) {
                  ElMessage.error('当前浏览器不支持实时投屏，请使用 Chrome / Edge')
                  return
                }
                player = new WebCodecsPlayer(screenCanvas.value, {
                  fps: streamFps,
                  onResize: (w, h) => syncResolution(w, h),
                  onError: (e) => console.error('投屏解码错误:', e)
                })
              }
              player.feed(event.data)
            }
        else {
              try {
                const message = JSON.parse(event.data)
                handleWebSocketMessage(message)
              } catch (error) {
                console.error('消息解析失败', error)
              }
          }
      }}}
      catch (error) {
        console.error('投屏显示初始化失败:', error)
      }
    }
    
    // 首帧解码后 / 分辨率变化时，用解码器实际尺寸校准（stream_started 为首要来源）
    const syncResolution = (newWidth, newHeight) => {
      if (newWidth && newHeight && (videoResolution.width !== newWidth || videoResolution.height !== newHeight)) {
        videoResolution.width = newWidth
        videoResolution.height = newHeight
        videoResolution.aspectRatio = newWidth / newHeight
        if (scrcpy) {
          scrcpy.setResolution(newWidth, newHeight)
        }
        nextTick(() => adjustScreenContainer())
      }
    }
    
    const adjustScreenContainer = () => {
      const video = screenCanvas.value
      if (!video || !videoResolution.width || !videoResolution.height) {
        return
      }

      try {
        const vw = videoResolution.width
        const vh = videoResolution.height
        const ratio = vw / vh

        const mainContent = document.querySelector('.main-content')
        if (!mainContent) return

        const availableHeight = mainContent.clientHeight - 24
        const headerEl = document.querySelector('.screen-header')
        const headerHeight = headerEl ? headerEl.offsetHeight : 40
        const maxVideoHeight = availableHeight - headerHeight - 20

        let targetWidth = vw
        let targetHeight = vh

        if (targetHeight > maxVideoHeight) {
          targetHeight = maxVideoHeight
          targetWidth = targetHeight * ratio
        }

        video.style.width = `${targetWidth}px`
        video.style.height = `${targetHeight}px`

        const screenArea = document.querySelector('.screen-area')
        if (screenArea) {
          const sideEl = document.querySelector('.side-controls')
          const sideWidth = sideEl ? sideEl.offsetWidth : 0
          screenArea.style.maxWidth = `${targetWidth + 24 + sideWidth}px`
          screenArea.style.width = 'auto'
          // 左侧栏高度对齐投屏区，使应用管理面板底边与投屏底边一致
          const connectionArea = document.querySelector('.connection-info-area')
          if (connectionArea) {
            connectionArea.style.height = `${screenArea.offsetHeight}px`
          }
        }
      } catch (error) {
        console.error('容器调整失败:', error)
      }
    }

    
    // 获取触控坐标（兼容鼠标/触摸/指针事件）
    // clampToBounds=true 时越界坐标钳制到屏幕边缘（用于拖拽，保证滑到边缘且不丢帧）；
    // 默认 false 时越界返回 null（用于 hover/点击/滚轮等，界外应忽略）
    const getTouchCoordinates = (event, clampToBounds = false) => {
      const target = screenCanvas.value
      if (!target) return null

      const rect = target.getBoundingClientRect()
      let clientX, clientY

      if (event.touches && event.touches.length > 0) {
        clientX = event.touches[0].clientX
        clientY = event.touches[0].clientY
      } else {
        clientX = event.clientX
        clientY = event.clientY
      }

      let relativeX = clientX - rect.left
      let relativeY = clientY - rect.top
      const displayWidth = rect.width
      const displayHeight = rect.height

      const outOfBounds = relativeX < 0 || relativeY < 0 || relativeX > displayWidth || relativeY > displayHeight
      if (outOfBounds) {
        if (!clampToBounds) return null
        relativeX = Math.max(0, Math.min(relativeX, displayWidth))
        relativeY = Math.max(0, Math.min(relativeY, displayHeight))
      }

      return {
        relativeX,
        relativeY,
        displayWidth,
        displayHeight
      }
    }
    
    const sendControlMessage = (message) => {
      if (controlWs && controlWs.readyState === WebSocket.OPEN) {
        controlWs.send(JSON.stringify(message))
      } else {
        ElMessage.error('设备控制连接未建立，无法操作设备')
      }
    }
    
    // 将显示坐标转换为scrcpy设备坐标
    const toDeviceCoords = (coords) => {
      return scrcpy.toDeviceCoords(coords.relativeX, coords.relativeY, coords.displayWidth, coords.displayHeight)
    }

    // 将显示坐标转换为原始设备坐标（用于元素检查器匹配）
    const toOriginalDeviceCoords = (coords) => {
      if (!deviceWindowSize.width || !deviceWindowSize.height) return null
      const scaleX = deviceWindowSize.width / coords.displayWidth
      const scaleY = deviceWindowSize.height / coords.displayHeight
      return {
        x: Math.round(coords.relativeX * scaleX),
        y: Math.round(coords.relativeY * scaleY)
      }
    }
    
    // 通过scrcpy control socket发送触摸事件
    const sendScrcpyTouch = (action, coords) => {
      const dc = toDeviceCoords(coords)
      if (!dc) return
      if (action === Action.DOWN) scrcpy.touchDown(dc.x, dc.y)
      else if (action === Action.MOVE) scrcpy.touchMove(dc.x, dc.y)
      else if (action === Action.UP) scrcpy.touchUp(dc.x, dc.y)
    }
    
    // 处理屏幕点击（元素检查器日志记录）
    const handleScreenClick = (event) => {
      if (!isConnected.value) return
      
      if (touchAction !== 'click') {
        touchAction = null
        return
      }
      
      const coords = getTouchCoordinates(event)
      if (coords && elementInspectorEnabled.value) {
        const oc = toOriginalDeviceCoords(coords)
        if (oc) {
          let clickedElement = null
          if (uiHierarchy.value) {
            clickedElement = findElementAtPosition(oc.x, oc.y)
          }
          
          const logData = {
            coordinates: `(${oc.x}, ${oc.y})`,
            timestamp: new Date()
          }
          
          if (clickedElement) {
            logData.element = { ...clickedElement }
          } else if (hoverElement.value) {
            logData.element = { ...hoverElement.value }
          }
          
          addOperationLog('点击操作', logData)
          
          let clickDelay = 500
          if (clickedElement) {
            const className = clickedElement.class?.toLowerCase() || ''
            if (className.includes('button') || clickedElement.clickable === 'true') {
              clickDelay = 800
            } else if (className.includes('edittext') || className.includes('input')) {
              clickDelay = 300
            }
          }
          scheduleXmlCheck(clickDelay)
        }
      }
      
      touchAction = null
    }
    
    const logSwipeEvent = (startCoords, endCoords) => {
      if (!elementInspectorEnabled.value) return
      const startOc = toOriginalDeviceCoords(startCoords)
      const endOc = toOriginalDeviceCoords(endCoords)
      if (!startOc || !endOc) return
      
      let startElement = null
      if (uiHierarchy.value) {
        startElement = findElementAtPosition(startOc.x, startOc.y)
      }
      
      const logData = {
        coordinates: `从(${startOc.x}, ${startOc.y})到(${endOc.x}, ${endOc.y})`,
        timestamp: new Date()
      }
      
      if (startElement) {
        logData.element = { ...startElement }
      } else if (hoverElement.value) {
        logData.element = { ...hoverElement.value }
      }
      
      addOperationLog('滑动操作', logData)
      scheduleXmlCheck(300)
    }
    
    // 处理指针按下 - 立即发送 ACTION_DOWN，并捕获指针以跟踪越界移动
    const handleScreenPointerDown = (event) => {
      if (!isConnected.value) return
      // 只响应主键（鼠标左键 / 触摸 / 笔），忽略右键、中键
      if (event.button !== undefined && event.button !== 0) return
      // 已有活动手势时，忽略额外指针（单点镜像，避免多指干扰）
      if (isMouseDown) return

      const coords = getTouchCoordinates(event)
      if (!coords) return

      // 捕获指针：后续 move/up 即使移出 canvas 也会投递到此元素
      try {
        screenCanvas.value.setPointerCapture(event.pointerId)
      } catch (e) { /* 部分环境可能不支持，忽略 */ }
      activePointerId = event.pointerId

      touchAction = null
      hasDragged = false
      isMouseDown = true
      startCoords = coords

      sendScrcpyTouch(Action.DOWN, coords)
    }

    // 处理指针移动 - 未按下时用于元素检查器 hover；按下拖拽时实时发送 ACTION_MOVE
    const handleScreenPointerMove = (event) => {
      // hover 路径（仅未按下、开启检查器时）
      if (!isMouseDown && elementInspectorEnabled.value && !connecting.value) {
        const coords = getTouchCoordinates(event)

        if (coords && deviceWindowSize.width && deviceWindowSize.height) {
          const oc = toOriginalDeviceCoords(coords)
          if (!oc) return

          clearTimeout(window.elementHoverTimer)
          window.elementHoverTimer = setTimeout(() => {
            if (oc.x >= 0 && oc.y >= 0 && oc.x <= deviceWindowSize.width && oc.y <= deviceWindowSize.height) {
              if (!uiHierarchy.value) {
                scheduleXmlCheck(100)
              }
              findElementAtPosition(oc.x, oc.y)
            }
          }, 250)
        }
      }

      if (!isConnected.value || !isMouseDown || !startCoords) return
      if (activePointerId !== null && event.pointerId !== activePointerId) return

      const now = Date.now()
      if (now - lastMoveTime < 16) return
      lastMoveTime = now

      // 拖拽用钳制坐标：越界时跟踪到屏幕边缘，不丢帧
      const coords = getTouchCoordinates(event, true)
      if (!coords) return

      const deltaX = Math.abs(coords.relativeX - startCoords.relativeX)
      const deltaY = Math.abs(coords.relativeY - startCoords.relativeY)

      if (deltaX > dragThreshold || deltaY > dragThreshold) {
        if (!hasDragged) {
          hasDragged = true
        }
        if (startCoords) {
          startCoords.isDragging = true
        }
      }

      sendScrcpyTouch(Action.MOVE, coords)
    }

    // 处理指针抬起 - 发送 ACTION_UP 并结束手势
    const handleScreenPointerUp = (event) => {
      if (activePointerId !== null && event.pointerId !== activePointerId) return
      finishTouchGesture(event)
    }

    // 处理指针取消（触摸被系统中断等）- 兜底抬手，避免设备端手指卡住
    const handleScreenPointerCancel = (event) => {
      if (activePointerId !== null && event.pointerId !== activePointerId) return
      finishTouchGesture(event)
    }

    // 结束一次触摸手势：发送 UP、释放捕获、分类点击/滑动、复位状态
    const finishTouchGesture = (event) => {
      if (!isMouseDown) return

      const wasDragging = startCoords && startCoords.isDragging
      // 抬手用钳制坐标：界外松手也落在边缘，而非回退到起点
      const coords = getTouchCoordinates(event, true)

      isMouseDown = false

      try {
        if (activePointerId !== null) {
          screenCanvas.value.releasePointerCapture(activePointerId)
        }
      } catch (e) { /* 忽略 */ }
      activePointerId = null

      if (coords) {
        sendScrcpyTouch(Action.UP, coords)
      } else if (startCoords) {
        sendScrcpyTouch(Action.UP, startCoords)
      }

      if (wasDragging && startCoords && coords) {
        touchAction = 'swipe'
        logSwipeEvent(startCoords, coords)
      } else if (!hasDragged && startCoords) {
        touchAction = 'click'
      }

      startCoords = null
    }

    const handleScreenPointerLeave = () => {
      // 仅清理检查器 hover 状态；拖拽期间由指针捕获维持，离开元素不影响
      if (!isMouseDown && elementInspectorEnabled.value) {
        hoverElement.value = null
      }
    }

    // 处理鼠标滚轮 - 映射为设备屏幕滚动
    const handleScreenWheel = (event) => {
      if (!isConnected.value) return
      const coords = getTouchCoordinates(event)
      if (!coords) return
      const dc = toDeviceCoords(coords)
      if (!dc) return
      const hScroll = event.deltaX !== 0 ? (event.deltaX > 0 ? -1 : 1) : 0
      const vScroll = event.deltaY !== 0 ? (event.deltaY > 0 ? -1 : 1) : 0
      scrcpy.scroll(dc.x, dc.y, hScroll, vScroll)
    }

    const handleHomeKey = () => {
      scrcpy.pressHome()
      
      if (elementInspectorEnabled.value) {
        addOperationLog('HOME键', {
          key: 'HOME',
          timestamp: new Date()
        })
        scheduleXmlCheck(800)
      }
    }
    
    const handleWakeScreen = () => {
      scrcpy.pressPower()
    }

    const handleScreenshot = () => {
      sendControlMessage({
        type: 'screenshot'
      })
    }

    const handleDumpXml = () => {
      sendControlMessage({
        type: 'dump_hierarchy'
      })
    }

    const getDebugCommand = () => {
      if (connectionInfo.adbHost && connectionInfo.adbPort && connectionInfo.serial) {
        return `adb -H ${connectionInfo.adbHost} -P ${connectionInfo.adbPort} -s ${connectionInfo.serial} shell`
      }
      return '-'
    }
    
    const copyCommand = () => {
      const command = getDebugCommand()
      if (command !== '-') {
        navigator.clipboard.writeText(command).then(() => {
          ElMessage.success('调试命令已复制到剪贴板')
        }).catch(() => {
          ElMessage.error('复制失败')
        })
      }
    }
    
    const copyConnectionCommand = () => {
      const command = connectionInfo.connection ? `adb connect ${connectionInfo.connection}` : '-'
      if (command !== '-') {
        navigator.clipboard.writeText(command).then(() => {
          ElMessage.success('连接命令已复制到剪贴板')
        }).catch(() => {
          ElMessage.error('复制失败')
        })
      }
    }
    
    let resizeTimer = null

    const handleWindowResize = () => {
      if (resizeTimer) {
        clearTimeout(resizeTimer)
      }

      resizeTimer = setTimeout(() => {
        if (videoResolution.width && videoResolution.height) {
          nextTick(() => {
            adjustScreenContainer()
          })
        }
      }, 100)
    }
    
    // ============= 元素检查器相关函数 =============

    // 关闭元素检查器并清理其状态
    const closeElementInspector = () => {
      elementInspectorEnabled.value = false
      if (inspectorWs) {
        inspectorWs.close()
        inspectorWs = null
      }
      stopXmlChangeDetection()
      selectedElement.value = null
      hoverElement.value = null
      uiHierarchy.value = null
      lastXmlHash.value = ''
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
      if (connectionInfo.proxyHost && connectionInfo.proxyPort && connectionInfo.serial) {
        connectInspectorWebSocket()
        startXmlChangeDetection()
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
    
    
    const refreshUIHierarchy = async () => {
      if (!inspectorWs || inspectorWs.readyState !== WebSocket.OPEN) {
        return
      }

      try {
        inspectorWs.send(JSON.stringify({
          type: 'get_ui_hierarchy'
        }))
      } catch (error) {
        console.error('获取UI层次失败:', error)
      }
    }

    const parseBounds = (boundsStr) => {
      if (!boundsStr) return null

      // 格式: [left,top][right,bottom]
      const match = boundsStr.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/)
      if (!match) return null

      return {
        left: parseInt(match[1]),
        top: parseInt(match[2]),
        right: parseInt(match[3]),
        bottom: parseInt(match[4])
      }
    }

    const isPointInBounds = (x, y, bounds) => {
      if (!bounds) return false
      return x > bounds.left && x < bounds.right && y > bounds.top && y < bounds.bottom
    }

    const calculateArea = (bounds) => {
      if (!bounds) return Infinity
      return (bounds.right - bounds.left) * (bounds.bottom - bounds.top)
    }

    const collectAllMatchingElements = (node, x, y, matches = []) => {
      if (!node) {
        return matches
      }

      const bounds = parseBounds(node.bounds)
      if (!bounds) {
        return matches
      }

      if (isPointInBounds(x, y, bounds)) {
        const centerX = (bounds.left + bounds.right) / 2
        const centerY = (bounds.top + bounds.bottom) / 2
        const distanceToCenter = Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2))

        matches.push({
          element: node,
          bounds: bounds,
          area: calculateArea(bounds),
          distanceToCenter: distanceToCenter,
          // 计算点在元素中的相对位置（0-1）
          relativeX: (x - bounds.left) / (bounds.right - bounds.left),
          relativeY: (y - bounds.top) / (bounds.bottom - bounds.top)
        })

        if (node.children && node.children.length > 0) {
          for (const child of node.children) {
            collectAllMatchingElements(child, x, y, matches)
          }
        }
      }

      return matches
    }

    const findSmallestElementAt = (node, x, y) => {
      const allMatches = collectAllMatchingElements(node, x, y)

      if (allMatches.length === 0) {
        return null
      }

      let bestMatch = null
      let bestScore = -1

      for (const match of allMatches) {
        const element = match.element
        const area = match.area
        const distanceToCenter = match.distanceToCenter
        const relativeX = match.relativeX
        const relativeY = match.relativeY

        let score = 0

        // 1. 面积越小，分数越高（主要条件）
        const maxArea = Math.max(...allMatches.map(m => m.area))
        score += (maxArea - area) / maxArea * 1000

        // 越接近元素中心分数越高
        const maxDistance = Math.max(...allMatches.map(m => m.distanceToCenter))
        if (maxDistance > 0) {
          score += (maxDistance - distanceToCenter) / maxDistance * 200
        }

        // 点击位置在元素中心区域的加分
        if (relativeX >= 0.2 && relativeX <= 0.8 && relativeY >= 0.2 && relativeY <= 0.8) {
          score += 150 // 在中心80%区域内
        }

        // 2. 可点击元素优先级更高
        if (element.clickable === 'true') {
          score += 500
        }

        // 3. 有文本内容的元素优先级更高
        if (element.text && element.text.trim().length > 0) {
          score += 300
        }

        // 4. 有资源ID的元素优先级更高
        if (element.resource_id && element.resource_id.trim().length > 0) {
          score += 200
        }

        // 5. 启用状态的元素优先级更高
        if (element.enabled === 'true') {
          score += 100
        }
        
        // 6. 特定类型的元素优先级调整
        if (element.class) {
          const className = element.class.toLowerCase()
          if (className.includes('button')) {
            score += 150
          } else if (className.includes('textview')) {
            score += 100
          } else if (className.includes('imageview')) {
            score += 80
          } else if (className.includes('edittext')) {
            score += 120
          }
        }
        
        // 7. 面积太小的元素（可能是装饰性元素）降低优先级
        if (area < 100) { // 10x10像素以下
          score -= 200
        }
        
        // 8. 面积太大的元素（可能是容器）降低优先级
        if (area > 50000) { // 大于约200x250像素
          score -= 100
        }
        
        if (score > bestScore) {
          bestScore = score
          bestMatch = match
        }
      }
            
      return bestMatch ? bestMatch.element : allMatches[0].element
    }

    const findElementAtPosition = (x, y) => {
      if (!uiHierarchy.value) {
        refreshUIHierarchy()
        return null
      }

      let foundElement = findSmallestElementAt(uiHierarchy.value, x, y)

      // 如果根节点没有找到匹配，尝试直接在子节点中查找
      if (!foundElement && uiHierarchy.value.children && uiHierarchy.value.children.length > 0) {
        let smallestElement = null
        let smallestArea = Infinity

        for (const child of uiHierarchy.value.children) {
          const childResult = findSmallestElementAt(child, x, y)
          if (childResult) {
            const childBounds = parseBounds(childResult.bounds)
            const childArea = calculateArea(childBounds)

            if (childArea < smallestArea) {
              smallestElement = childResult
              smallestArea = childArea
            }
          }
        }

        foundElement = smallestElement
      }

      if (foundElement) {
        hoverElement.value = foundElement
        return foundElement
      } else {
        hoverElement.value = null
      }

      return null
    }


    const performElementAction = (action, text = '') => {
      if (!selectedElement.value || !inspectorWs || inspectorWs.readyState !== WebSocket.OPEN) {
        ElMessage.error('未选择元素或检查器未连接')
        return
      }

      const message = {
        type: 'element_action',
        element: selectedElement.value,
        action: action
      }

      if (text) {
        message.text = text
      }

      inspectorWs.send(JSON.stringify(message))
    }

    const addOperationLog = (action, logData) => {
      if (!elementInspectorEnabled.value) return

      const log = {
        timestamp: logData.timestamp || new Date(),
        action: action,
        coordinates: logData.coordinates,
        key: logData.key,
        element: logData.element ? { ...logData.element } : null,
        type: action.includes('点击') ? 'click' : action.includes('悬停') ? 'hover' : 'action'
      }

      operationLogs.value.unshift(log)

      if (operationLogs.value.length > 5) {
        operationLogs.value = operationLogs.value.slice(0, 5)
      }
    }

    const clearOperationLogs = () => {
      operationLogs.value = []
      ElMessage.success('操作日志已清空')
    }

    const copyToClipboard = async (text, type) => {
      try {
        await navigator.clipboard.writeText(text)
        ElMessage.success(`${type}已复制到剪贴板`)
      } catch (err) {
        console.error('复制失败:', err)
        // 降级方案：使用传统的复制方法
        try {
          const textArea = document.createElement('textarea')
          textArea.value = text
          textArea.style.position = 'fixed'
          textArea.style.opacity = '0'
          document.body.appendChild(textArea)
          textArea.select()
          document.execCommand('copy')
          document.body.removeChild(textArea)
          ElMessage.success(`${type}已复制到剪贴板`)
        } catch (fallbackErr) {
          console.error('降级复制也失败:', fallbackErr)
          ElMessage.error('复制失败，请手动复制')
        }
      }
    }

    const formatTime = (date) => {
      return date.toLocaleTimeString('zh-CN', {
        hour12: false,
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
      })
    }

    const decompressXml = async (xmlData) => {
      if (!xmlData?.xml) {
        throw new Error('无效的XML数据')
      }

      if (!xmlData.compressed) {
        return xmlData.xml
      }

      try {
        const binaryString = atob(xmlData.xml)
        const bytes = new Uint8Array(binaryString.length)
        for (let i = 0; i < binaryString.length; i++) {
          bytes[i] = binaryString.charCodeAt(i)
        }
        return pako.ungzip(bytes, { to: 'string' })
      } catch (error) {
        console.error('XML解压缩失败:', error)
        throw error
      }
    }

    const generateXmlHash = (xmlContent) => {
      if (!xmlContent) return null

      let hash = 0
      for (let i = 0; i < xmlContent.length; i++) {
        const char = xmlContent.charCodeAt(i)
        hash = ((hash << 5) - hash) + char
        hash = hash & hash // 转换为32位整数
      }
      return hash.toString()
    }

    // 获取当前XML并检测变化
    const checkXmlChange = async () => {
      if (!monitorWebSocketConnection()) {
        return
      }

      try {
        sendControlMessage({
          type: 'get_xml_only'  // 只获取XML内容，不解析树结构
        })
      } catch (error) {
        console.error('获取XML失败:', error)
        // 连接异常时尝试重连
        if (elementInspectorEnabled.value) {
          setTimeout(() => {
            connectInspectorWebSocket()
          }, 2000)
        }
      }
    }

    // 页面稳定性检测状态
    let stabilityCheckTimer = null
    let stabilityCheckCount = 0
    let lastStabilityHash = null
    let consecutiveStableCount = 0
    let xmlRequestRetryCount = 0
    const MAX_STABILITY_CHECKS = 12
    const MAX_XML_RETRY = 3
    const REQUIRED_STABLE_COUNT = 2

    // 等待页面稳定的XML检测（基准XML存储在web端）
    const waitForPageStability = (initialDelay = 1000) => {
      if (stabilityCheckTimer) {
        clearTimeout(stabilityCheckTimer)
        stabilityCheckTimer = null
      }

      stabilityCheckCount = 0
      consecutiveStableCount = 0
      xmlRequestRetryCount = 0

      // 使用当前的XML hash作为基准（如果存在）
      if (lastXmlHash.value) {
        lastStabilityHash = lastXmlHash.value
        setTimeout(() => {
          checkPageStability()
        }, initialDelay + 1000)
      } else {
        // 没有基准XML时先获取一次
        setTimeout(() => {
          getBaselineXmlForStability()
        }, initialDelay + 1000)
      }
    }

    // 获取稳定性检测的基准XML - 仅在没有基准时使用
    const getBaselineXmlForStability = () => {
      try {
        sendControlMessage({
          type: 'get_xml_only'
        })

        // 1秒后开始第一次对比检测
        stabilityCheckTimer = setTimeout(() => {
          checkPageStability()
        }, 1000)
        
      } catch (error) {
        console.error('获取基准XML失败:', error)
        // 重试机制
        if (xmlRequestRetryCount < MAX_XML_RETRY) {
          xmlRequestRetryCount++
          setTimeout(() => {
            getBaselineXmlForStability()
          }, 2000) // 2秒后重试
        }
      }
    }
    
    // 检测页面是否稳定
    const checkPageStability = () => {
      if (!inspectorWs || inspectorWs.readyState !== WebSocket.OPEN) {
        console.error('WebSocket连接异常，停止稳定性检测')
        return
      }
      
      if (stabilityCheckCount >= MAX_STABILITY_CHECKS) {
        console.warn('稳定性检测达到最大次数，使用最后一次XML更新UI')
        // 达到最大检测次数，停止检测并清理状态
        if (stabilityCheckTimer) {
          clearTimeout(stabilityCheckTimer)
          stabilityCheckTimer = null
        }
        
        // 重置检测状态
        stabilityCheckCount = 0
        consecutiveStableCount = 0
        xmlRequestRetryCount = 0
        
        // 如果有最后一次的XML hash且与当前不同，则刷新UI
        if (lastStabilityHash && lastStabilityHash !== lastXmlHash.value) {
          console.log('使用最后一次XML更新UI层次')
          refreshUIHierarchy()
        } else {
          console.log('最后一次XML与当前相同，无需更新')
        }
        
        // 清理稳定性hash
        lastStabilityHash = null
        return
      }
      
      stabilityCheckCount++
      
      try {
        // 使用control WebSocket获取XML进行对比
        sendControlMessage({
          type: 'get_xml_only'
        })
        
        // 统一使用1秒检测间隔
        const nextInterval = 1000
        
        // 设置下次检测
        stabilityCheckTimer = setTimeout(() => {
          checkPageStability()
        }, nextInterval)
        
      } catch (error) {
        console.error('稳定性检测失败:', error)
        // 网络异常时也尝试重试
        if (xmlRequestRetryCount < MAX_XML_RETRY) {
          xmlRequestRetryCount++
          setTimeout(() => {
            checkPageStability()
          }, 2000)
        }
      }
    }
    
    // 处理稳定性检测的XML响应（基准XML存储在web端）
    const handleStabilityXmlResponse = (xmlContent) => {
      const currentHash = generateXmlHash(xmlContent)
      
      if (lastStabilityHash === null) {
        // 这是基准XML，记录hash并更新全局基准
        lastStabilityHash = currentHash
        lastXmlHash.value = currentHash // 同时更新全局XML hash
        consecutiveStableCount = 0
        return false // 等待下次对比
      }
      
      // 这是对比XML
      if (currentHash === lastStabilityHash) {
        // Hash相同，页面可能稳定
        consecutiveStableCount++
        
        // 需要连续稳定多次才认为真正稳定
        if (consecutiveStableCount >= REQUIRED_STABLE_COUNT) {
          // 停止稳定性检测
          if (stabilityCheckTimer) {
            clearTimeout(stabilityCheckTimer)
            stabilityCheckTimer = null
          }
          
          // 重置检测状态
          stabilityCheckCount = 0
          lastStabilityHash = null
          consecutiveStableCount = 0
          xmlRequestRetryCount = 0
          
          // 如果XML确实发生了变化，刷新UI层次
          if (currentHash !== lastXmlHash.value) {
            refreshUIHierarchy()
          } 
          
          return true // 页面已稳定
        } else {
          // 继续确认稳定性
          return false // 继续检测
        }
      } else {
        // Hash不同，页面仍在变化
        lastStabilityHash = currentHash
        consecutiveStableCount = 0 // 重置连续稳定计数
        console.log(`页面仍在变化，重置稳定计数`)
        return false // 继续检测
      }
    }

    const scheduleXmlCheck = (delay = 1000) => {
      // 稳定性检测自管理 stabilityCheckTimer；不要在此清除 xmlChangeTimer（那是兜底轮询的 interval）
      waitForPageStability(delay)
    }

    const startXmlChangeDetection = () => {
      if (!elementInspectorEnabled.value) return

      xmlChangeTimer = setInterval(() => {
        checkXmlChange()

        // UI层次为空但连接正常时强制刷新
        if (!uiHierarchy.value && monitorWebSocketConnection()) {
          console.warn('检测到UI层次为空但连接正常，强制刷新')
          refreshUIHierarchy()
        }
      }, 5000) // 每5秒检测一次作为兜底
    }

    const stopXmlChangeDetection = () => {
      // 清理常规检测定时器
      if (xmlChangeTimer) {
        clearInterval(xmlChangeTimer)
        xmlChangeTimer = null
      }
      
      // 清理稳定性检测定时器
      if (stabilityCheckTimer) {
        clearTimeout(stabilityCheckTimer)
        stabilityCheckTimer = null
      }
      
      // 重置稳定性检测状态
      stabilityCheckCount = 0
      lastStabilityHash = null
      consecutiveStableCount = 0
      xmlRequestRetryCount = 0
    }
    
    // WebSocket连接状态监控
    const monitorWebSocketConnection = () => {
      if (!inspectorWs) return false

      const socketConnected = inspectorWs.readyState === WebSocket.OPEN
      if (!socketConnected) {
        console.warn('WebSocket连接异常，状态:', inspectorWs.readyState)
        // 尝试重新连接
        if (elementInspectorEnabled.value) {
          setTimeout(() => {
            connectInspectorWebSocket()
          }, 3000) // 3秒后重连
        }
      }
      return socketConnected
    }

    const isInputElement = (element) => {
      if (!element) return false
      const className = element.class || ''
      return className.includes('EditText') ||
             className.includes('TextField') ||
             (element.clickable && element.enabled)
    }
    
    const showInputDialog = async () => {
      try {
        const { value } = await ElMessageBox.prompt('请输入文本内容', '文本输入', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          inputPattern: /.+/,
          inputErrorMessage: '请输入有效的文本内容'
        })
        
        if (value) {
          performElementAction('input_text', value)
        }
      } catch (error) {
        // 用户取消输入，不做处理
      }
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
      if (holdWs && holdWs.readyState === WebSocket.OPEN && connectionInfo.serial) {
        holdWs.send(JSON.stringify({
          serial: connectionInfo.serial,
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
      if (sessionId) return true // 列表页已占用并传入会话

      const holder = userStore.userInfo?.username || ''
      if (!holder) {
        ElMessage.error('未获取到登录用户信息，无法占用设备')
        return false
      }

      const newSession = genSessionId()
      try {
        const res = await deviceApi.deviceHold({ id: route.params.id, holder, sessionId: newSession, cast: true })
        if (!res || res.code !== 0) return false
      } catch (e) {
        return false
      }

      sessionId = newSession
      deviceSessionStore.setSession(route.params.id, sessionId)
      return true
    }

    // 页面关闭/刷新时以 keepalive 方式释放占用
    const handlePageHide = () => {
      if (sessionId) {
        deviceApi.releaseHoldOnUnload({
          id: connectionInfo.id || route.params.id,
          holder: null,
          sessionId
        })
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

      // 启动占用心跳续约
      if (connectionInfo.serial) {
        startHoldHeartbeat()
      }

      // 等待 agent 启动代理并上报连接信息
      const ready = await waitForConnectionReady()
      if (!ready) {
        ElMessage.error('设备代理启动超时，请重试')
        stopHoldHeartbeat()
        if (sessionId) {
          deviceApi.deviceHold({ id: route.params.id, holder: null, sessionId }).catch(() => {})
          deviceSessionStore.clearSession(route.params.id)
        }
        router.push({ name: 'Devices' })
        return
      }

      // 连接信息就绪，自动连接并投屏
      connectDevice()

      // 页面关闭/刷新时释放占用
      window.addEventListener('pagehide', handlePageHide)

      // 监听窗口大小变化
      window.addEventListener('resize', handleWindowResize)
    })

    onUnmounted(() => {
      // 关闭可能仍打开的消息弹窗
      ElMessageBox.close()
      disconnectWebSocket()
      stopHoldHeartbeat()
      // 仅释放本会话持有的占用
      if (sessionId) {
        deviceApi.deviceHold({ id: connectionInfo.id, holder: null, sessionId }).catch(() => {})
        deviceSessionStore.clearSession(route.params.id)
      }

      if (resizeTimer) {
        clearTimeout(resizeTimer)
      }

      if (window.elementHoverTimer) {
        clearTimeout(window.elementHoverTimer)
        window.elementHoverTimer = null
      }

      stopXmlChangeDetection()

      if (player) {
        player.close()
        player = null
      }

      window.removeEventListener('pagehide', handlePageHide)
      window.removeEventListener('resize', handleWindowResize)

      selectedElement.value = null
      hoverElement.value = null
    })
    
    return {
      loading,
      connecting,
      isConnected,
      connectionInfo,
      screenCanvas,
      videoResolution,
      deviceWindowSize,
      getConnectionInfo,
      connectDevice,
      adjustScreenContainer,
      handleScreenClick,
      handleScreenPointerDown,
      handleScreenPointerMove,
      handleScreenPointerUp,
      handleScreenPointerCancel,
      handleScreenPointerLeave,
      handleScreenWheel,
      handleHomeKey,
      handleWakeScreen,
      handleScreenshot,
      handleDumpXml,
      getDebugCommand,
      copyCommand,
      copyConnectionCommand,
      releaseDevice,
      // 元素检查器相关
      elementInspectorEnabled,
      operationLogs,
      selectedElement,
      hoverElement,
      uiHierarchy,
      toggleElementInspector,
      // 应用管理
      appManagerEnabled,
      toggleAppManager,
      refreshUIHierarchy,
      isInputElement,
      showInputDialog,
      performElementAction,
      clearOperationLogs,
      formatTime,
      copyToClipboard,
      scheduleXmlCheck,
      waitForPageStability
    }
  }
}
</script>

<style scoped>
.device-connection {
  height: 100%;
  display: flex;
  flex-direction: column;
  background-color: #f5f7fa;
  margin: 0;
  padding: 0;
}

.main-content {
  flex: 1;
  display: flex;
  gap: 12px;
  padding: 12px;
  overflow-y: auto;
  align-items: flex-start;
}

.screen-area {
  display: flex;
  flex-direction: column;
  background-color: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  flex: none;
  overflow: hidden;
  margin: 0;
  padding: 0;
  box-sizing: border-box;
  min-height: fit-content;
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

.side-controls {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 8px;
  border-right: 1px solid #e6e6e6;
  align-items: center;
  flex-shrink: 0;
}

.screen-main {
  flex: 1;
  display: flex;
  flex-direction: row;
  padding: 0;
}

.control-btn {
  width: 24px !important;
  height: 24px !important;
  min-width: 24px !important;
  max-width: 24px !important;
  padding: 0 !important;
  margin: 0 !important;
  display: flex !important;
  align-items: center;
  justify-content: center;
  border-radius: 3px;
  box-sizing: border-box !important;
  border-width: 1px !important;
  flex-shrink: 0;
}

/* 重置所有按钮类型的样式差异 */
.control-btn.el-button--primary,
.control-btn.el-button--warning,
.control-btn.el-button--success,
.control-btn.el-button--info {
  width: 24px !important;
  height: 24px !important;
  min-width: 24px !important;
  max-width: 24px !important;
  padding: 0 !important;
  margin: 0 !important;
  border-width: 1px !important;
}

.control-btn .el-icon {
  font-size: 12px;
  margin: 0 !important;
}

.screen-container {
  position: relative;
  display: flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  background-color: #000;
  overflow: hidden;
  margin: 0;
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

.screen-video {
  object-fit: contain;
  cursor: crosshair;
  background-color: #000;
  outline: none;
  display: block;
  margin: 0 auto;
  padding: 0;
  touch-action: none;
}

.connection-info-area {
  width: 480px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
}

.info-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 4px;
}

.info-header h3 {
  margin: 0;
  color: #303133;
}

.info-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
  max-width: 100%;
  flex: 1;
  min-height: 0;
  overflow: hidden; /* 防止内容溢出 */
}

/* 应用管理面板填满连接信息卡片下方的剩余空间，使其底边与投屏区底边对齐 */
.info-content .app-manager-card {
  flex: 1;
  min-height: 0;
}

.info-card {
  border-radius: 8px;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
}

/* 元素属性面板文本换行 */
.info-card .el-descriptions-item__content {
  word-break: break-all;
  word-wrap: break-word;
  max-width: 100%;
  overflow-wrap: break-word;
  min-width: 0; /* 允许内容收缩 */
}

/* 元素操作按钮区域 */
.element-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
  width: 100%;
  max-width: 100%;
  overflow: hidden;
}

.card-header {
  display: flex;
  margin: 0;
  padding: auto;
  justify-content: space-between;
  align-items: center;
}



/* 操作日志样式 */
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
  gap: 3px; /* 减半：6px → 3px */
}

.element-text-row {
  margin-bottom: 2px; /* 减半：4px → 2px */
}

.element-text-row .element-text {
  display: block;
  width: 100%;
  word-break: break-all;
  line-height: 1.3;
  margin-top: 1px; /* 减半：2px → 1px */
}

.element-main {
  display: flex;
  flex-wrap: wrap;
  gap: 4px; /* 减半：8px → 4px */
  align-items: flex-start;
  width: 100%;
  overflow-wrap: break-word;
  margin-bottom: 1px; /* 减半：2px → 1px */
}

.element-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px; /* 减半：8px → 4px */
  font-size: 11px;
  color: #606266;
  width: 100%;
  overflow-wrap: break-word;
  margin-top: 1px; /* 减半：2px → 1px */
}

.action-info {
  display: flex;
  flex-wrap: wrap;
  gap: 4px; /* 减半：8px → 4px */
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
  margin-right: 2px; /* 减半：4px → 2px */
  display: inline-block;
  flex-shrink: 0; /* 防止标签被压缩 */
}

/* 元素属性样式 - 统一样式 */
.element-class,
.element-text,
.element-id,
.coordinates,
.bounds,
.key-info {
  font-size: 11px;
  cursor: pointer;
  padding: 1px 2px; /* 减半：2px 4px → 1px 2px */
  border-radius: 2px; /* 减小：3px → 2px */
  transition: background-color 0.2s;
  word-break: break-all;
  word-wrap: break-word;
  max-width: 100%;
  display: inline-block;
  line-height: 1.3; /* 统一行高 */
  margin: 0; /* 减半：1px 0 → 0 */
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
  font-size: 10px; /* 边界信息稍小一些 */
}

.key-info {
  color: #67c23a;
  font-weight: 500;
}

.key-info:hover {
  background: #f0f9ff;
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

.element-bounds {
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 12px;
  color: #909399;
}

.element-coordinates {
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 12px;
  color: #67c23a;
  font-weight: 500;
}

.element-key {
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 12px;
  color: #e6a23c;
  font-weight: 500;
  background-color: #fdf6ec;
  padding: 2px 6px;
  border-radius: 3px;
}
</style>


