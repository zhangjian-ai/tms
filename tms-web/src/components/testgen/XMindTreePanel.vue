<template>
  <div class="xmind-tree-panel">
    <div class="toolbar">
      <el-button size="small" @click="expandAll">展开全部</el-button>
      <el-button size="small" @click="collapseAll">折叠全部</el-button>
      <el-button size="small" @click="zoomIn">放大</el-button>
      <el-button size="small" @click="zoomOut">缩小</el-button>
      <el-button size="small" @click="fitView">适应画布</el-button>
    </div>
    <div ref="container" class="mind-container" tabindex="0"></div>
  </div>
</template>

<script>
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import MindElixir from 'mind-elixir'

const NODE_COLORS = {
  root: '#2c3e50',
  module: '#3498db',
  point: '#27ae60',
  case: '#8e44ad',
  step: '#e67e22',
  free: 'transparent'
}

export default {
  name: 'XMindTreePanel',
  props: {
    treeData: { type: Object, default: null },
    generatingPointIds: { type: Object, default: () => new Set() }
  },
  emits: ['update', 'generate-point'],
  setup(props, { emit }) {
    const container = ref(null)
    let mind = null
    let isInternalUpdate = false

    // ---- 加载态管理 ----

    function applyLoadingState(nodeId) {
      if (!mind) return
      var tpcEl = mind.findEle(nodeId)
      if (!tpcEl) return
      tpcEl.classList.add('point-generating')
      // 添加一个真实的子元素作为闪动蒙层（不用伪元素，避免被选中状态覆盖）
      if (!tpcEl.querySelector('.generating-mask')) {
        var mask = document.createElement('div')
        mask.className = 'generating-mask'
        tpcEl.appendChild(mask)
        // 确保 tpcEl 是 relative 定位
        if (getComputedStyle(tpcEl).position === 'static') {
          tpcEl.style.position = 'relative'
        }
      }
    }

    function removeLoadingState(nodeId) {
      if (!mind) return
      var tpcEl = mind.findEle(nodeId)
      if (!tpcEl) return
      tpcEl.classList.remove('point-generating')
      var mask = tpcEl.querySelector('.generating-mask')
      if (mask) mask.remove()
    }

    function syncLoadingStates() {
      if (!mind || !props.generatingPointIds) return
      props.generatingPointIds.forEach(function(id) {
        applyLoadingState(id)
      })
    }

    // ---- 数据转换 ----

    function toME(node, isRoot) {
      if (!node) return null
      var isFree = node.type === 'free'
      const me = {
        topic: node.title || '',
        id: node.id,
        style: isFree
          ? { background: 'transparent', color: '#333' }
          : { background: NODE_COLORS[node.type] || NODE_COLORS.step, color: '#fff' },
        nodeType: node.type
      }
      if (isRoot) me.root = true
      if (node.marker) {
        var priorityNum = node.marker.replace('priority-', '')
        var pLevel = parseInt(priorityNum) - 1
        me.topic = '[P' + pLevel + '] ' + me.topic
      }
      if (node.children && node.children.length > 0) {
        me.children = node.children.map(function(c) { return toME(c, false) })
      }
      return me
    }

    function fromME(me) {
      if (!me) return null
      var title = me.topic || ''
      var marker = null
      var m = title.match(/^\[(P\d)\]\s*/)
      if (m) {
        var pLevel = parseInt(m[1].replace('P', ''))
        marker = 'priority-' + (pLevel + 1)
        title = title.replace(m[0], '')
      }
      var node = {
        id: me.id, title: title,
        type: me.nodeType || 'free', marker: marker,
        expanded: me.expanded !== false, children: []
      }
      if (me.children && me.children.length > 0) {
        node.children = me.children.map(fromME)
      }
      return node
    }

    // ---- Mind Elixir 初始化 ----

    function closeContextMenu() {
      var overlay = container.value.querySelector('.context-menu')
      if (overlay) overlay.click()
    }

    function initMind() {
      if (!props.treeData || !container.value) return

      var nodeData = toME(props.treeData, true)
      if (!nodeData) return

      var data = { nodeData: nodeData, arrows: [], summaries: [], direction: MindElixir.RIGHT }

      if (mind) {
        mind.refresh(data)
        return
      }

      mind = new MindElixir({
        el: container.value,
        direction: MindElixir.RIGHT,
        draggable: true,
        editable: true,
        contextMenu: {
          focus: true,
          link: true,
          extend: [
            {
              name: '生成用例',
              onclick: function() {
                var selectedNode = mind.currentNode
                if (selectedNode && selectedNode.nodeObj.nodeType === 'point') {
                  emit('generate-point', selectedNode.nodeObj.id)
                }
                closeContextMenu()
              }
            },
            {
              name: '设为目录',
              onclick: function() {
                var selectedNode = mind.currentNode
                if (selectedNode && canSetType(selectedNode.nodeObj, 'module')) {
                  setNodeType(selectedNode.nodeObj.id, 'module')
                }
                closeContextMenu()
              }
            },
            {
              name: '设为测试点',
              onclick: function() {
                var selectedNode = mind.currentNode
                if (selectedNode && canSetType(selectedNode.nodeObj, 'point')) {
                  setNodeType(selectedNode.nodeObj.id, 'point')
                }
                closeContextMenu()
              }
            },
            {
              name: '设为用例',
              onclick: function() {
                var selectedNode = mind.currentNode
                if (selectedNode && canSetType(selectedNode.nodeObj, 'case')) {
                  setNodeType(selectedNode.nodeObj.id, 'case')
                  setChildrenType(selectedNode.nodeObj, 'step')
                }
                closeContextMenu()
              }
            },
            {
              name: '设为自由节点',
              onclick: function() {
                var selectedNode = mind.currentNode
                if (selectedNode && canResetToFree(selectedNode.nodeObj)) {
                  setNodeType(selectedNode.nodeObj.id, 'free')
                  setChildrenType(selectedNode.nodeObj, 'free')
                }
                closeContextMenu()
              }
            }
          ]
        },
        toolBar: false,
        nodeMenu: true,
        keypress: true,
        locale: 'zh_CN',
        overflowHidden: false,
        mainNodeVerticalGap: 15,
        mainNodeHorizontalGap: 65,
        mouseSelectionButton: 2
      })

      mind.init(data)

      // 拦截正在生成测试点的右键菜单
      container.value.addEventListener('contextmenu', function(e) {
        var target = e.target
        // 向上查找到 me-tpc 节点元素
        while (target && target.tagName !== 'ME-TPC') {
          target = target.parentElement
          if (!target || target === container.value) break
        }
        if (target && target.tagName === 'ME-TPC') {
          var nodeId = target.getAttribute('data-nodeid')
          // Mind Elixir 的 id 前缀是 'me'，需要去掉
          if (nodeId && nodeId.startsWith('me')) {
            nodeId = nodeId.substring(2)
          }
          if (nodeId && props.generatingPointIds && props.generatingPointIds.has(nodeId)) {
            e.preventDefault()
            e.stopPropagation()
            e.stopImmediatePropagation()
            return false
          }
        }
      }, true)

      // 选中节点后重新应用闪动 class（防止被 Mind Elixir 清除）
      mind.bus.addListener('selectNode', function() {
        setTimeout(syncLoadingStates, 0)
      })
      mind.bus.addListener('unselectNode', function() {
        setTimeout(syncLoadingStates, 0)
      })

      // 监听右键菜单显示，动态控制菜单项可见性和顺序
      mind.bus.addListener('showContextMenu', function() {
        var selectedNode = mind.currentNode
        var nodeObj = selectedNode ? selectedNode.nodeObj : null

        // 如果是正在生成的测试点，阻止菜单显示
        if (nodeObj && nodeObj.nodeType === 'point' &&
            props.generatingPointIds && props.generatingPointIds.has(nodeObj.id)) {
          return
        }

        setTimeout(function() {
          var menu = container.value.querySelector('.context-menu .menu-list')
          if (!menu) return

          var menuItems = Array.from(menu.querySelectorAll('li'))
          var selectedNode = mind.currentNode
          var nodeObj = selectedNode ? selectedNode.nodeObj : null

          // 自定义菜单项名称列表（按期望顺序）
          var customMenus = ['生成用例', '设为目录', '设为测试点', '设为用例', '设为自由节点']
          var customItems = []
          var nativeItems = []

          menuItems.forEach(function(li) {
            var text = li.querySelector('span')?.textContent
            if (customMenus.includes(text)) {
              customItems.push(li)
            } else {
              nativeItems.push(li)
            }
          })

          // 重新排序：自定义菜单在前，原生菜单在后
          customItems.forEach(function(item) { menu.appendChild(item) })
          nativeItems.forEach(function(item) { menu.appendChild(item) })

          // 控制菜单项可见性
          menuItems.forEach(function(li) {
            var text = li.querySelector('span')?.textContent
            if (text === '生成用例') {
              var isPoint = nodeObj && nodeObj.nodeType === 'point'
              var isGenerating = props.generatingPointIds && props.generatingPointIds.has(nodeObj && nodeObj.id)
              var spanEl = li.querySelector('span')
              if (isPoint && !isGenerating) {
                li.style.display = ''
                li.style.opacity = ''
                li.style.pointerEvents = ''
                if (spanEl) spanEl.style.color = ''
              } else if (isPoint && isGenerating) {
                li.style.display = ''
                li.style.opacity = ''
                li.style.pointerEvents = 'none'
                if (spanEl) spanEl.style.color = '#999'
              } else {
                li.style.display = 'none'
              }
            } else if (text === '设为目录') {
              li.style.display = (nodeObj && canSetType(nodeObj, 'module')) ? '' : 'none'
            } else if (text === '设为测试点') {
              li.style.display = (nodeObj && canSetType(nodeObj, 'point')) ? '' : 'none'
            } else if (text === '设为用例') {
              li.style.display = (nodeObj && canSetType(nodeObj, 'case')) ? '' : 'none'
            } else if (text === '设为自由节点') {
              li.style.display = (nodeObj && canResetToFree(nodeObj)) ? '' : 'none'
            }
          })
        }, 10)
      })

      // 监听节点操作
      mind.bus.addListener('operation', function(operation) {
        if (operation && operation.name === 'beginEdit') {
          // 如果是正在生成的测试点，阻止编辑
          if (operation.obj && operation.obj.nodeType === 'point' &&
              props.generatingPointIds && props.generatingPointIds.has(operation.obj.id)) {
            var inputBox = document.getElementById('input-box')
            if (inputBox) inputBox.remove()
            return
          }

          // 编辑开始，固定编辑框尺寸
          setTimeout(function() {
            var tpcEl = operation.obj ? mind.findEle(operation.obj.id) : null
            var inputBox = document.getElementById('input-box')
            if (inputBox && tpcEl) {
              var rect = tpcEl.getBoundingClientRect()
              inputBox.style.width = rect.width + 'px'
              inputBox.style.minWidth = rect.width + 'px'
              inputBox.style.maxWidth = rect.width + 'px'
              inputBox.style.height = rect.height + 'px'
              inputBox.style.minHeight = rect.height + 'px'
              inputBox.style.maxHeight = rect.height + 'px'
              inputBox.style.overflow = 'auto'
              inputBox.style.zIndex = '1000'
            }
          }, 10)
        } else if (operation && (operation.name === 'addChild' || operation.name === 'addSibling' || operation.name === 'addParent')) {
          // 新创建的节点，设置为自由节点样式
          if (operation.obj) {
            operation.obj.nodeType = 'free'
            operation.obj.style = { background: 'transparent', color: '#333' }
            var tpcEl = mind.findEle(operation.obj.id)
            if (tpcEl) {
              tpcEl.style.background = 'transparent'
              tpcEl.style.color = '#333'
            }
          }
          emitUpdate()
        } else if (operation && operation.name === 'finishEdit') {
          emitUpdate()
        } else {
          emitUpdate()
        }
      })
    }

    // ---- 节点类型设置 ----

    function canSetType(nodeObj, targetType) {
      if (!nodeObj || nodeObj.root) return false

      // 只有自由节点可以设置类型
      if (nodeObj.nodeType !== 'free') return false

      // 根据父节点类型判断
      var parentType = findParentType(nodeObj.id)

      if (targetType === 'case') {
        return parentType === 'point'
      } else if (targetType === 'module' || targetType === 'point') {
        return parentType === 'module' || parentType === 'root'
      }

      return false
    }

    function setNodeType(nodeId, newType) {
      if (!mind) return

      var nodeData = mind.getObjById(nodeId, mind.nodeData)
      if (!nodeData) return

      var isFree = newType === 'free'
      nodeData.nodeType = newType
      nodeData.style = isFree
        ? { background: 'transparent', color: '#333' }
        : { background: NODE_COLORS[newType] || NODE_COLORS.step, color: '#fff' }

      var tpcEl = mind.findEle(nodeId)
      if (tpcEl) {
        tpcEl.style.background = isFree ? 'transparent' : (NODE_COLORS[newType] || NODE_COLORS.step)
        tpcEl.style.color = isFree ? '#333' : '#fff'
      }

      emitUpdate()
    }

    function setChildrenType(nodeObj, newType) {
      if (!nodeObj || !nodeObj.children) return
      var isFree = newType === 'free'
      var bg = isFree ? 'transparent' : (NODE_COLORS[newType] || NODE_COLORS.step)
      var fg = isFree ? '#333' : '#fff'
      for (var i = 0; i < nodeObj.children.length; i++) {
        var child = nodeObj.children[i]
        child.nodeType = newType
        child.style = { background: bg, color: fg }
        var tpcEl = mind.findEle(child.id)
        if (tpcEl) {
          tpcEl.style.background = bg
          tpcEl.style.color = fg
        }
        setChildrenType(child, newType)
      }
    }

    function canResetToFree(nodeObj) {
      if (!nodeObj || nodeObj.root) return false
      return nodeObj.nodeType !== 'free' && nodeObj.nodeType !== 'step'
    }

    function findParentType(nodeId) {
      function search(node, targetId) {
        if (!node.children) return null
        for (var i = 0; i < node.children.length; i++) {
          if (node.children[i].id === targetId) {
            return node.nodeType || (node.root ? 'root' : 'free')
          }
          var found = search(node.children[i], targetId)
          if (found) return found
        }
        return null
      }
      return search(mind.nodeData, nodeId)
    }

    // ---- 工具栏 ----

    function expandAll() {
      if (!mind) return
      var rootTpc = container.value.querySelector('me-root me-tpc')
      if (rootTpc) mind.expandNodeAll(rootTpc, true)
    }

    function collapseAll() {
      if (!mind) return
      var rootTpc = container.value.querySelector('me-root me-tpc')
      if (rootTpc) mind.expandNodeAll(rootTpc, false)
    }

    function zoomIn() { if (mind) mind.scale(mind.scaleVal + 0.1) }
    function zoomOut() { if (mind) mind.scale(mind.scaleVal - 0.1) }
    function fitView() { if (mind) mind.toCenter() }

    function emitUpdate() {
      isInternalUpdate = true
      setTimeout(function() {
        if (!mind) return
        var updated = fromME(mind.nodeData)
        if (updated) emit('update', updated)
        setTimeout(function() { isInternalUpdate = false }, 200)
      }, 100)
    }

    // 增量更新测试点的用例（不触发全量渲染）
    function updatePointCases(pointId, cases) {
      if (!mind) return

      // 找到测试点节点数据
      var pointNodeData = mind.getObjById(pointId, mind.nodeData)
      if (!pointNodeData) return

      // 更新子节点（用例列表）
      pointNodeData.children = cases.map(function(c) { return toME(c, false) })

      // 局部重新布局和渲染连接线，不触发全量刷新
      mind.layout()
      mind.linkDiv()

      // 重新应用加载态
      setTimeout(syncLoadingStates, 50)

      // 保存更新后的数据
      emitUpdate()
    }

    // ---- 生命周期 ----

    watch(function() { return props.treeData }, function(n, o) {
      if (isInternalUpdate) return
      if (n !== o) nextTick(function() {
        initMind()
        setTimeout(syncLoadingStates, 50)
      })
    })

    watch(function() { return props.generatingPointIds }, function(newIds, oldIds) {
      if (!mind) return
      if (oldIds) {
        oldIds.forEach(function(id) {
          if (!newIds || !newIds.has(id)) removeLoadingState(id)
        })
      }
      if (newIds) {
        newIds.forEach(function(id) { applyLoadingState(id) })
      }
    })

    onMounted(function() {
      nextTick(initMind)
    })

    onUnmounted(function() {
      // 清理工作
    })

    return {
      container,
      expandAll, collapseAll, zoomIn, zoomOut, fitView,
      updatePointCases
    }
  }
}
</script>

<style scoped>
.xmind-tree-panel {
  height: 100%;
  display: flex;
  flex-direction: column;
  position: relative;
}
.toolbar {
  padding: 8px 12px;
  border-bottom: 1px solid #e6e6e6;
  display: flex;
  gap: 8px;
  background: #fafafa;
  flex-shrink: 0;
}
.mind-container {
  flex: 1;
  overflow: hidden;
  background: #f9f9f9;
  position: relative;
  min-height: 400px;
  width: 100%;
}
</style>

<style>
/* Mind Elixir 样式微调 */
.map-container me-root me-tpc {
  font-size: 16px !important;
  font-weight: 600 !important;
}
.map-container me-parent:hover me-tpc {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15) !important;
  transform: translateY(-1px);
}
.map-container .selected me-tpc {
  box-shadow: 0 0 0 2px #409eff !important;
}
.map-container .lines path,
.map-container .subLines path {
  stroke-width: 2 !important;
  stroke: #ddd !important;
}
.map-container me-epd {
  opacity: 0.6 !important;
  transition: opacity 0.2s !important;
}
.map-container me-epd:hover {
  opacity: 1 !important;
}

/* 编辑框样式：确保完全覆盖原节点，处理内容溢出 */
.map-container #input-box {
  z-index: 1000 !important;
  background-color: #2c3e50 !important;
  color: #fff !important;
  overflow: auto !important;
  box-sizing: border-box !important;
}

/* 生成中节点样式：紫色蒙层闪动 */
.generating-mask {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: rgba(21, 13, 231, 0.4);
  pointer-events: none;
  animation: overlay-blink 1.2s ease-in-out infinite;
  border-radius: 3px;
  z-index: 999;
}
@keyframes overlay-blink {
  0%, 100% {
    opacity: 1;
  }
  50% {
    opacity: 0;
  }
}
</style>
