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
                <el-tag v-else type="danger">未连接</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="代理服务">
                {{ connectionInfo.proxyHost + ":" + connectionInfo.proxyPort || '-' }}
              </el-descriptions-item>
              <el-descriptions-item label="本地调试">
                <el-tag type="success" @click="copyCommand" style="cursor: pointer;">
                  {{ getDebugCommand() }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="本地连接">
                <el-tag type="success" @click="copyConnectionCommand" style="cursor: pointer;">
                  {{ "adb connect " + connectionInfo.connection || '-' }}
                </el-tag>
              </el-descriptions-item>
            </el-descriptions>
          </el-card>
          
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
          <!-- 功能按钮区域移到标题行 -->
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
              type="warning" 
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
          
          <div class="screen-container">
            <div v-if="connecting" class="screen-placeholder">
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
              <!-- 投屏视频 -->
              <video 
                id="screen-player"
                ref="screenVideo" 
                class="screen-video"
                :style="{ aspectRatio: videoResolution.aspectRatio }"
                muted 
                autoplay
                @loadedmetadata="handleVideoMetadata"
                @click="handleScreenClick"
                @mousedown="handleScreenMouseDown"
                @mousemove="handleScreenMouseMove"
                @mouseleave="handleScreenMouseLeave"
                @mouseup="handleScreenMouseUp"
                @touchstart="handleScreenTouchStart"
                @touchmove="handleScreenTouchMove"
                @touchend="handleScreenTouchEnd"
                @wheel.prevent="handleScreenWheel"
              ></video>
              
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
                <!-- 如果有元素信息，显示元素详情 -->
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
                <!-- 如果没有元素信息，只显示坐标或按键 -->
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
import { Monitor, Camera, HomeFilled, Document, Sunny, Search, InfoFilled, Delete } from '@element-plus/icons-vue'
import { deviceApi } from '@/api/device.js'
import { useUserStore } from '@/stores/user'
import JMuxer from 'jmuxer'
import pako from 'pako'
import { ScrcpyController, Action } from '@/utils/device'
import config from '@/config/index.js'

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
    Delete
  },
  setup() {
    const route = useRoute()
    const router = useRouter()
    const userStore = useUserStore()

    // 设备占用心跳
    let holdWs = null
    let holdHeartbeatTimer = null
    
    // 响应式数据
    const loading = ref(false)
    const connecting = ref(false)
    const isConnected = ref(false)
    const wsStatus = ref('disconnected')
    const deviceName = ref(route.query.name || '未知设备')
    const screenVideo = ref(null)

    // 触控状态管理
    let isMouseDown = false
    let startCoords = null
    let lastMoveTime = 0
    let touchAction = null
    let hasDragged = false
    const dragThreshold = 10
    
    // 视频分辨率相关
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
    
    // 连接信息
    const connectionInfo = reactive({
      // 设备基本信息（用于显示）
      id: route.params.id,
      deviceName: route.query.name,
      serial: route.query.serial || '',
      
      // 连接配置
      adbHost: '',
      adbPort: '',
      proxyHost: '',
      proxyPort: '',
      connection: '',
    })
    
    // WebSocket连接
    let scrcpyWs = null  // 投屏WebSocket
    let controlWs = null // 控制WebSocket
    let inspectorWs = null // 元素检查器WebSocket
    let reconnectTimer = null
    let jmu = null
    
    // scrcpy 设备控制器（协议构造 + WebSocket 发送）
    const scrcpy = new ScrcpyController()
    
    // 元素检查器相关状态
    const elementInspectorEnabled = ref(false)
    const operationLogs = ref([])       // 操作日志记录
    const selectedElement = ref(null)   // 点击选中的元素
    const hoverElement = ref(null)      // hover的元素
    const isHovering = ref(false)       // 是否正在hover状态
    const uiHierarchy = ref(null)       // UI层次结构数据
    const lastXmlHash = ref('')         // 上次XML内容哈希
    let xmlChangeTimer = null           // XML变化检测定时器
    
    // 获取连接信息
    const getConnectionInfo = async () => {
      loading.value = true
      try {
        const response = await deviceApi.getDeviceConnection(route.params.id)
        if (response.code === 0) {
          const { id: _connId, ...connData } = response.data // eslint-disable-line no-unused-vars
          Object.assign(connectionInfo, connData)
        } else {
          ElMessage.error(response.msg || '获取连接信息失败')
        }
      } catch (error) {
        ElMessage.error('获取连接信息失败')
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

    // 释放设备
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
      const res = await deviceApi.deviceHold({
        id: connectionInfo.id,
        holder: null
      })
      if (res.code !== 0) {
        ElMessage.error('设备释放失败')
      }else {
        try {
          // 1. 主动关闭所有WebSocket连接
          disconnectWebSocket()
          
          // 2. 清理所有定时器
          if (reconnectTimer) {
            clearTimeout(reconnectTimer)
            reconnectTimer = null
          }
          
          if (resizeTimer) {
            clearTimeout(resizeTimer)
            resizeTimer = null
          }
          
          // 清理hover定时器
          if (window.elementHoverTimer) {
            clearTimeout(window.elementHoverTimer)
            window.elementHoverTimer = null
          }
          
          // 3. 停止XML变化检测
          stopXmlChangeDetection()
          
          // 4. 清理视频播放器
          if (jmu) {
            jmu.destroy()
            jmu = null
          }
          
          // 5. 重置连接状态
          isConnected.value = false
          connecting.value = false
          wsStatus.value = 'disconnected'
          
          // 6. 清理元素检查器状态
          elementInspectorEnabled.value = false
          selectedElement.value = null
          hoverElement.value = null
          uiHierarchy.value = null
          operationLogs.value = []
          lastXmlHash.value = ''
          
          // 7. 跳转到设备列表页面
          router.push({
            name: 'Devices'
          })
        } catch (error) {
          console.error('设备释放过程中出现错误:', error)
          ElMessage.error('设备释放失败: ' + error.message)
        }
      }
    }
    
    // 断开设备连接
    const disconnectDevice = () => {
      if (isConnected.value) {
        disconnectWebSocket()
      }
    }
    
    // 连接WebSocket
    const connectWebSocket = () => {
      if (!connectionInfo.proxyHost || !connectionInfo.proxyPort || !connectionInfo.serial) {
        ElMessage.error('连接信息不完整，无法建立连接')
        return
      }
      
      connecting.value = true
      wsStatus.value = 'connecting'
      
      // 同时建立投屏和控制连接
      connectScrcpyWebSocket()
      connectControlWebSocket()
      
      // 如果元素检查器启用，也连接检查器WebSocket
      if (elementInspectorEnabled.value) {
        connectInspectorWebSocket()
      }
    }
    
    // 连接投屏WebSocket
    const connectScrcpyWebSocket = () => {
      try {
        // 构建投屏WebSocket URL
        const wsUrl = `ws://${connectionInfo.proxyHost}:${connectionInfo.proxyPort}/devices/${connectionInfo.serial}/scrcpy`
        scrcpyWs = new WebSocket(wsUrl)
        scrcpyWs.binaryType = 'arraybuffer'
        scrcpy.bind(scrcpyWs)
        
        scrcpyWs.onopen = () => {
          wsStatus.value = 'connected'
          isConnected.value = true
          connecting.value = false
          ElMessage.success('投屏连接成功')
          
          // 使用nextTick确保DOM渲染完成，然后初始化JMuxer
          nextTick(() => {
            initMirrorDisplay()
          })
          
          // 发送开始投屏消息
          scrcpyWs.send(JSON.stringify({
            type: 'start_stream'
          }))
        }
        
        scrcpyWs.onclose = () => {
          wsStatus.value = 'disconnected'
          isConnected.value = false
          connecting.value = false
        }
        
        scrcpyWs.onerror = () => {
          wsStatus.value = 'error'
          connecting.value = false
          ElMessage.error('投屏连接失败')
        }
        
      } catch (error) {
        connecting.value = false
        wsStatus.value = 'error'
        ElMessage.error('投屏连接失败')
      }
    }
    
    // 连接控制WebSocket
    const connectControlWebSocket = () => {
      try {
        // 构建控制WebSocket URL
        const wsUrl = `ws://${connectionInfo.proxyHost}:${connectionInfo.proxyPort}/devices/${connectionInfo.serial}/control`

        controlWs = new WebSocket(wsUrl)
        
        controlWs.onopen = () => {}
        
        controlWs.onmessage = (event) => {
          try {
            const message = JSON.parse(event.data)
            handleControlMessage(message)
          } catch (error) {
            console.error('控制消息解析失败', error)
          }
        }
        
        controlWs.onclose = () => {}
        
        controlWs.onerror = (error) => {
          console.error('设备控制连接失败:', error)
        }
        
      } catch (error) {
        console.error('设备控制连接失败:', error)
      }
    }
    
    // 连接元素检查器WebSocket
    const connectInspectorWebSocket = () => {
      try {
        // 构建检查器WebSocket URL
        const wsUrl = `ws://${connectionInfo.proxyHost}:${connectionInfo.proxyPort}/devices/${connectionInfo.serial}/inspector`

        inspectorWs = new WebSocket(wsUrl)
        
        inspectorWs.onopen = () => {
          // 元素检查器连接成功
        }
        
        inspectorWs.onmessage = (event) => {
          try {
            const message = JSON.parse(event.data)
            handleInspectorMessage(message)
          } catch (error) {
            console.error('检查器消息解析失败', error)
          }
        }
        
        inspectorWs.onclose = () => {
          // 元素检查器连接关闭
        }
        
        inspectorWs.onerror = (error) => {
          console.error('元素检查器连接失败:', error)
        }
        
      } catch (error) {
        console.error('元素检查器连接失败:', error)
      }
    }
    
    // 断开WebSocket
    const disconnectWebSocket = () => {
      // 断开投屏WebSocket
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
      
      // 断开控制WebSocket
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
      
      // 断开检查器WebSocket
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
      
      // 清理视频相关资源
      try {
        if (jmu) {
          jmu.destroy()
          jmu = null
        }
        if (screenVideo.value) {
          screenVideo.value.src = ''
          screenVideo.value.load()
        }
      } catch (error) {
        console.error('清理视频资源失败:', error)
      }
      
      // 重置状态
      isMouseDown = false
      isConnected.value = false
      wsStatus.value = 'disconnected'
      
      console.log('所有WebSocket连接已断开')
    }
    
    // 处理投屏WebSocket消息
    const handleWebSocketMessage = (message) => {
      switch (message.type) {
        case 'connected':
          break
        case 'stream_started':
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
        case 'error':
          ElMessage.error(`错误: ${message.message}`)
          break
        default:
          break
      }
    }
    
    // 处理检查器WebSocket消息
    const handleInspectorMessage = (message) => {
      switch (message.type) {
        case 'connected':
          // 确保设备分辨率信息同步
          if (message.device_resolution) {
            deviceWindowSize.width = message.device_resolution[0]
            deviceWindowSize.height = message.device_resolution[1]
          }
          // 连接成功后立即获取UI层次
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
              
              // 提取树结构数据
              uiHierarchy.value = hierarchyData.tree || hierarchyData
              
              // 更新XML哈希
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
    
    // 处理控制WebSocket消息
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
              // 创建下载链接
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
            // 使用统一的解压缩函数
            try {
              // 构造标准的XML数据格式
              const xmlData = {
                xml: message.data.hierarchy,
                compressed: message.data.compressed,
                encoding: message.data.encoding
              }
              
              decompressXml(xmlData).then(xmlContent => {
                // 创建下载链接
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
        case 'click_result':
          if (!message.success) {
            ElMessage.error(`点击失败: ${message.error}`)
          }
          break
        case 'long_click_result':
          if (!message.success) {
            ElMessage.error(`长按失败: ${message.error}`)
          }
          break
        case 'swipe_result':
          if (!message.success) {
            ElMessage.error(`滑动失败: ${message.error}`)
          }
          break
        case 'key_event_result':
          if (!message.success) {
            ElMessage.error(`按键失败: ${message.error}`)
          }
          break
        case 'xml_only':
          // 处理仅XML内容的响应（用于页面变化检测）
          if (message.success && message.data && message.data.xml) {
            // 解压缩XML数据
            decompressXml(message.data).then(xmlContent => {
              // 如果正在进行稳定性检测，优先处理稳定性检测
              if (stabilityCheckTimer !== null || stabilityCheckCount > 0) {
                handleStabilityXmlResponse(xmlContent)
              } else {
                // 普通的XML变化检测
                const currentXmlHash = generateXmlHash(xmlContent)
                if (currentXmlHash && currentXmlHash !== lastXmlHash.value) {
                  // XML发生变化，刷新完整的UI层次
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
    
    // 初始化投屏显示
    const initMirrorDisplay = () => {
      try {
        // 设置投屏WebSocket的onmessage处理
        if (scrcpyWs) {
          scrcpyWs.onmessage = (event) => {
            if (event.data instanceof ArrayBuffer) {
              if (!jmu) {
                if (!screenVideo.value) return
                jmu = new JMuxer({
                  node: screenVideo.value,
                  mode: 'video',
                  flushingTime: 0,
                  bufferSize: 2 * 1024 * 1024,
                  fps: 20,
                  debug: false,
                  video: {
                    codec: 'avc',
                    width: videoResolution.width,
                    height: videoResolution.height,
                    timebase: [1, 1000]
                  },
                  onError: (data) => {
                    console.error('JMuxer错误:', data)
                    jmu.destroy()
                    jmu = null
                  }
                })
              }
              // 处理视频数据
              jmu.feed({ video: new Uint8Array(event.data) })
            }
        else {
              // 处理文本消息
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
    
    const handleVideoMetadata = () => {
      if (screenVideo.value) {
        const newWidth = screenVideo.value.videoWidth
        const newHeight = screenVideo.value.videoHeight
        
        if (newWidth && newHeight && (videoResolution.width !== newWidth || videoResolution.height !== newHeight)) {
          videoResolution.width = newWidth
          videoResolution.height = newHeight
          videoResolution.aspectRatio = newWidth / newHeight
          nextTick(() => adjustScreenContainer())
        }
      }
    }
    
    const adjustScreenContainer = () => {
      const video = screenVideo.value
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
          screenArea.style.maxWidth = `${targetWidth + 24}px`
          screenArea.style.width = 'auto'
        }
      } catch (error) {
        console.error('容器调整失败:', error)
      }
    }

    
    // 获取触控坐标（兼容鼠标和触摸）
    const getTouchCoordinates = (event) => {
      const target = screenVideo.value
      if (!target) return null
      
      const rect = target.getBoundingClientRect()
      let clientX, clientY
      
      if (event.touches && event.touches.length > 0) {
        // 触摸事件
        clientX = event.touches[0].clientX
        clientY = event.touches[0].clientY
      } else {
        // 鼠标事件
        clientX = event.clientX
        clientY = event.clientY
      }
      
      const relativeX = clientX - rect.left
      const relativeY = clientY - rect.top
      const displayWidth = rect.width
      const displayHeight = rect.height
      
      // 边界检查
      if (relativeX < 0 || relativeY < 0 || relativeX > displayWidth || relativeY > displayHeight) {
        return null
      }
      
      return { 
        relativeX, 
        relativeY, 
        displayWidth, 
        displayHeight 
      }
    }
    
    // 发送控制消息
    const sendControlMessage = (message) => {
      if (controlWs && controlWs.readyState === WebSocket.OPEN) {
        controlWs.send(JSON.stringify(message))
      } else {
        ElMessage.error('设备控制连接未建立，无法操作设备')
      }
    }
    
    // 将显示坐标转换为scrcpy设备坐标（用于scrcpy控制协议）
    const toDeviceCoords = (coords) => {
      return scrcpy.toDeviceCoords(coords.relativeX, coords.relativeY, coords.displayWidth, coords.displayHeight)
    }
    
    // 将显示坐标转换为原始设备坐标（用于元素检查器匹配，bounds是原始分辨率）
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
    
    // 处理屏幕点击（仅用于元素检查器日志记录，实际触控已在mouseDown/Up中通过scrcpy完成）
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
    
    // 记录滑动操作日志（滑动本身已通过实时touch事件完成）
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
    
    // 处理鼠标/触摸开始 - 立即发送ACTION_DOWN
    const handleScreenMouseDown = (event) => {
      if (!isConnected.value) return
      
      const coords = getTouchCoordinates(event)
      if (!coords) return
      
      touchAction = null
      hasDragged = false
      isMouseDown = true
      startCoords = coords
      
      sendScrcpyTouch(Action.DOWN, coords)
    }
    
    // 处理鼠标/触摸移动 - 拖拽时实时发送ACTION_MOVE
    const handleScreenMouseMove = (event) => {

      // 如果启用了元素检查器且没有按下鼠标，进行元素查找（使用原始分辨率坐标）
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
          }, 150)
        }
      }
      
      if (!isConnected.value || !isMouseDown || !startCoords) return
      
      const now = Date.now()
      if (now - lastMoveTime < 16) return
      lastMoveTime = now
      
      const coords = getTouchCoordinates(event)
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
      
      // 实时发送ACTION_MOVE到设备
      sendScrcpyTouch(Action.MOVE, coords)
    }
    
    // 处理鼠标/触摸结束 - 立即发送ACTION_UP
    const handleScreenMouseUp = (event) => {
      if (!isConnected.value) return
      
      const wasDragging = startCoords && startCoords.isDragging
      const coords = getTouchCoordinates(event)
      
      isMouseDown = false
      
      // 发送ACTION_UP（无论是点击还是滑动，都需要抬起手指）
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
    
    // 处理屏幕鼠标离开
    const handleScreenMouseLeave = () => {
      if (elementInspectorEnabled.value) {
        isHovering.value = false
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
    
    // 处理触摸开始
    const handleScreenTouchStart = (event) => {
      event.preventDefault() // 防止滚动
      handleScreenMouseDown(event)
    }
    
    // 处理触摸移动
    const handleScreenTouchMove = (event) => {
      event.preventDefault() // 防止滚动
      handleScreenMouseMove(event)
    }
    
    // 处理触摸结束
    const handleScreenTouchEnd = (event) => {
      event.preventDefault()
      handleScreenMouseUp(event)
    }
    
    // HOME键
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
    
    // 唤醒屏幕
    const handleWakeScreen = () => {
      scrcpy.pressPower()
    }
    
    // 截图
    const handleScreenshot = () => {
      sendControlMessage({
        type: 'screenshot'
      })
    }
    
    // Dump XML
    const handleDumpXml = () => {
      sendControlMessage({
        type: 'dump_hierarchy'
      })
    }
    
    // 获取WebSocket URL
    const getWebSocketUrl = () => {
      if (connectionInfo.proxyHost && connectionInfo.proxyPort && connectionInfo.serial) {
        return `ws://${connectionInfo.proxyHost}:${connectionInfo.proxyPort}/devices/${connectionInfo.serial}/ws`
      }
      return '-'
    }
    
    // 获取调试命令
    const getDebugCommand = () => {
      if (connectionInfo.adbHost && connectionInfo.adbPort && connectionInfo.serial) {
        return `adb -H ${connectionInfo.adbHost} -P ${connectionInfo.adbPort} -s ${connectionInfo.serial} shell`
      }
      return '-'
    }
    
    // 复制调试命令
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
    
    // 复制连接命令
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
    
    // 防抖定时器
    let resizeTimer = null
    
    // 窗口大小变化处理（防抖）
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
      }, 100) // 100ms 防抖延迟
    }
    
    // ============= 元素检查器相关函数 =============
    
    // 切换元素检查器
    const toggleElementInspector = () => {
      elementInspectorEnabled.value = !elementInspectorEnabled.value
      
      if (elementInspectorEnabled.value && connectionInfo.proxyHost && connectionInfo.proxyPort && connectionInfo.serial) {
        // 启用检查器时建立WebSocket连接
        connectInspectorWebSocket()
        // 开始XML变化检测
        startXmlChangeDetection()
      } else if (!elementInspectorEnabled.value) {
        // 关闭检查器时断开连接
        if (inspectorWs) {
          inspectorWs.close()
          inspectorWs = null
        }
        // 停止XML变化检测
        stopXmlChangeDetection()
        // 清理状态
        selectedElement.value = null
        hoverElement.value = null
        uiHierarchy.value = null
        lastXmlHash.value = ''
      }
    }
    
    
    // 刷新UI层次结构
    const refreshUIHierarchy = async () => {
      if (!inspectorWs || inspectorWs.readyState !== WebSocket.OPEN) {
        return
      }

      try {
        // 发送获取UI层次的请求
        inspectorWs.send(JSON.stringify({
          type: 'get_ui_hierarchy'
        }))
      } catch (error) {
        console.error('获取UI层次失败:', error)
      }
    }

    // 解析bounds字符串为数值
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

    // 检查点是否在bounds内 - 优化版本
    const isPointInBounds = (x, y, bounds) => {
      if (!bounds) return false
      // 使用更严格的边界检测，避免边界重叠问题
      return x > bounds.left && x < bounds.right && y > bounds.top && y < bounds.bottom
    }

    // 计算元素面积
    const calculateArea = (bounds) => {
      if (!bounds) return Infinity
      return (bounds.right - bounds.left) * (bounds.bottom - bounds.top)
    }

    // 收集所有匹配的元素 - 优化版本
    const collectAllMatchingElements = (node, x, y, matches = []) => {
      if (!node) {
        return matches
      }
      
      const bounds = parseBounds(node.bounds)
      if (!bounds) {
        return matches
      }
      
      if (isPointInBounds(x, y, bounds)) {
        // 计算点到边界中心的距离，用于精度判断
        const centerX = (bounds.left + bounds.right) / 2
        const centerY = (bounds.top + bounds.bottom) / 2
        const distanceToCenter = Math.sqrt(Math.pow(x - centerX, 2) + Math.pow(y - centerY, 2))
        
        // 当前节点匹配，添加到结果中
        matches.push({
          element: node,
          bounds: bounds,
          area: calculateArea(bounds),
          distanceToCenter: distanceToCenter,
          // 计算点在元素中的相对位置（0-1）
          relativeX: (x - bounds.left) / (bounds.right - bounds.left),
          relativeY: (y - bounds.top) / (bounds.bottom - bounds.top)
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

    // 递归查找最精确匹配元素 - 优化版本
    const findSmallestElementAt = (node, x, y) => {
      // 收集所有匹配的元素
      const allMatches = collectAllMatchingElements(node, x, y)
      
      if (allMatches.length === 0) {
        return null
      }
      
      // 多重筛选条件找到最合适的元素
      let bestMatch = null
      let bestScore = -1
      
      for (const match of allMatches) {
        const element = match.element
        const area = match.area
        const distanceToCenter = match.distanceToCenter
        const relativeX = match.relativeX
        const relativeY = match.relativeY
        
        // 计算元素的优先级分数
        let score = 0
        
        // 1. 面积越小，分数越高（主要条件）
        const maxArea = Math.max(...allMatches.map(m => m.area))
        score += (maxArea - area) / maxArea * 1000
        
        // 新增：点击位置越接近元素中心，分数越高
        const maxDistance = Math.max(...allMatches.map(m => m.distanceToCenter))
        if (maxDistance > 0) {
          score += (maxDistance - distanceToCenter) / maxDistance * 200
        }
        
        // 新增：点击位置在元素中心区域的加分
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

    // 真实元素查找
    const findElementAtPosition = (x, y) => {
      if (!uiHierarchy.value) {
        // 如果没有UI层次数据，先获取
        refreshUIHierarchy()
        return null
      }
      
      // 在UI层次中查找匹配的元素
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
        // 找到匹配的元素
        
        // 更新hover状态（仅更新属性显示，不记录日志）
        hoverElement.value = foundElement
        isHovering.value = true
        return foundElement
      } else {
        // 清空hover状态
        hoverElement.value = null
        isHovering.value = false
      }

      return null
    }
    
    
    // 执行元素操作
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
    
    // 处理树节点点击
    // 记录操作日志
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

      // 限制日志数量，最多保留5条
      if (operationLogs.value.length > 5) {
        operationLogs.value = operationLogs.value.slice(0, 5)
      }
    }

    // 清空操作日志
    const clearOperationLogs = () => {
      operationLogs.value = []
      ElMessage.success('操作日志已清空')
    }

    // 复制到剪贴板
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

    // 格式化时间显示
    const formatTime = (date) => {
      return date.toLocaleTimeString('zh-CN', {
        hour12: false,
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit'
      })
    }

    // XML数据解压缩函数
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

    // 生成XML内容哈希
    const generateXmlHash = (xmlContent) => {
      if (!xmlContent) return null
      
      // 简单的字符串哈希算法
      let hash = 0
      for (let i = 0; i < xmlContent.length; i++) {
        const char = xmlContent.charCodeAt(i)
        hash = ((hash << 5) - hash) + char
        hash = hash & hash // 转换为32位整数
      }
      return hash.toString()
    }

    // 获取当前XML并检测变化 - 优化版本
    const checkXmlChange = async () => {
      // 先检查连接状态
      if (!monitorWebSocketConnection()) {
        return
      }

      try {
        // 使用control WebSocket获取XML
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

    // 页面稳定性检测状态 - 优化版本
    let stabilityCheckTimer = null
    let stabilityCheckCount = 0
    let lastStabilityHash = null
    let consecutiveStableCount = 0 // 连续稳定次数
    let xmlRequestRetryCount = 0 // XML请求重试次数
    const MAX_STABILITY_CHECKS = 12 // 最大检测次数
    const MAX_XML_RETRY = 3 // 最大重试次数
    const REQUIRED_STABLE_COUNT = 2 // 需要连续稳定的次数
    
    // 等待页面稳定的XML检测 - 优化版本（基准XML存储在web端）
    const waitForPageStability = (initialDelay = 1000) => {
      // 清除之前的稳定性检测
      if (stabilityCheckTimer) {
        clearTimeout(stabilityCheckTimer)
        stabilityCheckTimer = null
      }
      
      // 重置检测状态
      stabilityCheckCount = 0
      consecutiveStableCount = 0
      xmlRequestRetryCount = 0
      
      // 使用当前的XML hash作为基准（如果存在）
      if (lastXmlHash.value) {
        lastStabilityHash = lastXmlHash.value
        // 延迟后开始检测
        setTimeout(() => {
          checkPageStability()
        }, initialDelay + 1000)
      } else {
        // 如果没有基准XML，先获取一次
        setTimeout(() => {
          getBaselineXmlForStability()
        }, initialDelay + 1000)
      }
    }
    
    // 获取稳定性检测的基准XML - 仅在没有基准时使用
    const getBaselineXmlForStability = () => {
      try {
        // 使用control WebSocket获取XML作为基准
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
    
    // 检测页面是否稳定 - 优化版本
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
    
    // 处理稳定性检测的XML响应 - 优化版本（基准XML存储在web端）
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
          // 页面真正稳定
          
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
          // 还需要继续确认稳定性
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

    // 智能XML变化检测 - 只在需要时检测
    const scheduleXmlCheck = (delay = 1000) => {
      // 清除之前的定时器
      if (xmlChangeTimer) {
        clearTimeout(xmlChangeTimer)
      }
      
      // 使用页面稳定性检测替代简单的延迟检测
      waitForPageStability(delay)
    }

    // 开始XML变化检测 - 优化兜底检测频率
    const startXmlChangeDetection = () => {
      if (!elementInspectorEnabled.value) return
      
      // 提高兜底检测频率到5秒，确保不错过页面变化
      xmlChangeTimer = setInterval(() => {
        checkXmlChange()
        
        // 额外的强制刷新机制：如果UI层次为空且连接正常，强制刷新
        if (!uiHierarchy.value && monitorWebSocketConnection()) {
          console.warn('检测到UI层次为空但连接正常，强制刷新')
          refreshUIHierarchy()
        }
      }, 5000) // 每5秒检测一次作为兜底
    }

    // 停止XML变化检测
    const stopXmlChangeDetection = () => {
      // 清理常规检测定时器
      if (xmlChangeTimer) {
        clearInterval(xmlChangeTimer)
        clearTimeout(xmlChangeTimer)
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
      
      const isConnected = inspectorWs.readyState === WebSocket.OPEN
      if (!isConnected) {
        console.warn('WebSocket连接异常，状态:', inspectorWs.readyState)
        // 尝试重新连接
        if (elementInspectorEnabled.value) {
          setTimeout(() => {
            connectInspectorWebSocket()
          }, 3000) // 3秒后重连
        }
      }
      return isConnected
    }
    
    // 获取节点显示名称
    
    // 判断是否为输入元素
    const isInputElement = (element) => {
      if (!element) return false
      const className = element.class || ''
      return className.includes('EditText') ||
             className.includes('TextField') ||
             (element.clickable && element.enabled)
    }
    
    // 显示输入对话框
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
      if (holdWs && holdWs.readyState === WebSocket.OPEN && connectionInfo.serial) {
        holdWs.send(JSON.stringify({
          serial: connectionInfo.serial,
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

    // 组件挂载
    onMounted(async () => {
      // 自动获取连接信息并连接设备
      await getConnectionInfo()
      
      // 如果连接信息获取成功，自动连接设备
      if (connectionInfo.proxyHost && connectionInfo.proxyPort && connectionInfo.serial) {
        connectDevice()
      }

      // 启动设备占用心跳
      if (connectionInfo.serial) {
        startHoldHeartbeat()
      }

      // 监听窗口大小变化
      window.addEventListener('resize', handleWindowResize)
    })
    
    onUnmounted(() => {
      disconnectWebSocket()
      stopHoldHeartbeat()
      deviceApi.deviceHold({ id: connectionInfo.id, holder: null }).catch(() => {})
      
      // 清理所有定时器
      if (reconnectTimer) {
        clearTimeout(reconnectTimer)
      }
      
      if (resizeTimer) {
        clearTimeout(resizeTimer)
      }
      
      // 清理hover定时器
      if (window.elementHoverTimer) {
        clearTimeout(window.elementHoverTimer)
        window.elementHoverTimer = null
      }
      
      // 停止XML变化检测
      stopXmlChangeDetection()
      
      if (jmu) {
        jmu.destroy()
        jmu = null
      }
      
      // 移除窗口大小变化监听
      window.removeEventListener('resize', handleWindowResize)
      
      // 清理状态
      selectedElement.value = null
      hoverElement.value = null
    })
    
    return {
      loading,
      connecting,
      isConnected,
      wsStatus,
      deviceName,
      connectionInfo,
      screenVideo,
      videoResolution,
      deviceWindowSize,
      getConnectionInfo,
      connectDevice,
      disconnectDevice,
      handleVideoMetadata,
      adjustScreenContainer,
      handleScreenClick,
      handleScreenMouseDown,
      handleScreenMouseMove,
      handleScreenMouseLeave,
      handleScreenMouseUp,
      handleScreenTouchStart,
      handleScreenTouchMove,
      handleScreenTouchEnd,
      handleScreenWheel,
      handleHomeKey,
      handleWakeScreen,
      handleScreenshot,
      handleDumpXml,
      getWebSocketUrl,
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

.header-controls {
  display: flex;
  gap: 8px;
  align-items: center;
}

.header-controls .control-btn {
  width: 32px !important;
  /* height: 32px !important; */
  /* min-width: 32px !important;
  max-width: 32px !important; */
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
  gap: 8px;
  padding: 0;
}

.screen-controls {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 6px;
  background-color: #f8f9fa;
  border-left: 1px solid #e6e6e6;
  min-width: 32px;
  align-items: flex-start;
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
}

/* 强制隐藏video控制栏 */
.screen-video::-webkit-media-controls {
  display: none !important;
}

.screen-video::-webkit-media-controls-panel {
  display: none !important;
}

.screen-video::-webkit-media-controls-play-button {
  display: none !important;
}

.screen-video::-webkit-media-controls-timeline {
  display: none !important;
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
  overflow: hidden; /* 防止内容溢出 */
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

.key-info {
  color: #606266;
  font-family: monospace;
  font-size: 11px;
  cursor: pointer;
  padding: 1px 2px;
  border-radius: 2px;
  transition: background-color 0.2s;
  word-break: break-all;
  word-wrap: break-word;
  max-width: 100%;
  display: inline-block;
}

.key-info:hover {
  background: #f4f4f5;
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


