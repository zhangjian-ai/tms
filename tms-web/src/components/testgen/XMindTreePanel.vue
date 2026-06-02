<template>
  <div class="xmind-tree-panel" :class="{ 'tree-disabled': disabled }">
    <div class="toolbar">
      <el-button size="small" @click="expandAll">展开全部</el-button>
      <el-button size="small" @click="collapseAll">折叠全部</el-button>
      <el-button size="small" @click="collapseAllCases">折叠用例</el-button>
      <el-button size="small" @click="zoomIn">放大</el-button>
      <el-button size="small" @click="zoomOut">缩小</el-button>
      <el-button size="small" @click="fitView">适应画布</el-button>
      <span v-if="disabled && disabledTip" class="disabled-tip">
        <el-icon class="is-loading" v-if="!readonlyOnly"><Loading /></el-icon>
        {{ disabledTip }}
      </span>
    </div>
    <div class="container-wrap">
      <div ref="container" class="mind-container" tabindex="0"></div>
      <div v-show="disabled" class="edit-blocker" @mousedown.stop @click.stop @dblclick.stop @contextmenu.stop></div>
    </div>
  </div>
</template>

<script>
import { ref, watch, onMounted, onUnmounted, nextTick, computed } from 'vue'
import MindElixir from 'mind-elixir'
import { Loading } from '@element-plus/icons-vue'

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
  components: { Loading },
  props: {
    treeData: { type: Object, default: null },
    generatingPointIds: { type: Object, default: () => new Set() },
    disabled: { type: Boolean, default: false },
    disabledTip: { type: String, default: '' }
  },
  emits: ['update', 'generate-point'],
  setup(props, { emit }) {
    const container = ref(null)
    const readonlyOnly = computed(() => /只读/.test(props.disabledTip || ''))
    let mind = null
    let isInternalUpdate = false
    // 会话级折叠状态：用户点"折叠用例"后置 true，下次 initMind / toME 时把所有 case 节点渲染为折叠
    // 不写入 store/Redis，纯本地 UI 行为
    let collapseAllCasesFlag = false

    // 优先级配置
    const PRIORITY_CONFIG = {
      'priority-1': { label: 'P0', color: '#f56c6c', number: '0' },
      'priority-2': { label: 'P1', color: '#f78989', number: '1' },
      'priority-3': { label: 'P2', color: '#f0ad4e', number: '2' },
      'priority-4': { label: 'P3', color: '#67c23a', number: '3' }
    }

    // ---- 加载态管理 ----

    /**
     * mind.findEle 在节点不存在/被折叠时会抛异常，统一容错。
     * 找不到返回 null，调用方自行判空。
     */
    function safeFindEle(nodeId) {
      if (!mind || !nodeId) return null
      try {
        return mind.findEle(nodeId)
      } catch (e) {
        return null
      }
    }

    function applyLoadingState(nodeId) {
      if (!mind) return
      var tpcEl = safeFindEle(nodeId)
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
      var tpcEl = safeFindEle(nodeId)
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
      var failed = Array.isArray(node.icons) && node.icons.indexOf('failed') >= 0
      var baseStyle = isFree
        ? { background: 'transparent', color: '#333' }
        : { background: NODE_COLORS[node.type] || NODE_COLORS.step, color: '#fff' }
      if (failed) {
        baseStyle.border = '2px solid #f56c6c'
      }
      const me = {
        topic: failed ? (node.title || '') + '  ⚠ 生成失败，可右键重试' : (node.title || ''),
        id: node.id,
        style: baseStyle,
        nodeType: node.type,
        failed: failed
      }
      if (isRoot) me.root = true
      // 折叠状态：所有节点都正常透传数据里的 expanded；
      // case 节点额外受会话级 flag（"折叠用例"按钮）强制折叠
      if (node.expanded === false || (node.type === 'case' && collapseAllCasesFlag)) {
        me.expanded = false
      }
      // 保留 icons 数据（不嵌入 HTML，而是在渲染后手动插入徽章）
      // failed 不算优先级，用边框单独表达
      if (node.icons && node.icons.length > 0) {
        var firstNonFailed = node.icons.find(function(i) { return i !== 'failed' })
        if (firstNonFailed) me.priority = firstNonFailed
      }
      if (node.children && node.children.length > 0) {
        me.children = node.children.map(function(c) { return toME(c, false) })
      }
      return me
    }

    function fromME(me) {
      if (!me) return null
      var icons = []
      if (me.priority) icons.push(me.priority)
      if (me.failed) icons.push('failed')
      // 标题里的"⚠ 生成失败，可右键重试"是展示装饰，写回时去掉
      var title = (me.topic || '').replace(/\s*⚠ 生成失败，可右键重试\s*$/, '')
      var node = {
        id: me.id,
        title: title,
        type: me.nodeType || 'free',
        icons: icons.length ? icons : null,
        expanded: me.expanded !== false,
        children: []
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

    /**
     * 把指定节点居中到画布。优先使用 toCenterByEle（mind-elixir 高版本 API），
     * 回退到操纵 me-tpc 元素的 scrollIntoView，最次回退到 toCenter。
     */
    function centerOnNode(nodeId) {
      if (!mind || !nodeId) return
      try {
        if (typeof mind.toCenterByEle === 'function') {
          var ele = mind.findEle(nodeId)
          if (ele) {
            mind.toCenterByEle(ele)
            return
          }
        }
        // 回退方案：直接定位 DOM
        var dom = container.value && container.value.querySelector(
                'me-tpc[data-nodeid="me' + nodeId + '"]')
        if (dom && dom.scrollIntoView) {
          dom.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' })
          return
        }
      } catch (e) {
        // 忽略，回退到 toCenter
      }
      if (mind.toCenter) mind.toCenter()
    }

    /**
     * 找到当前树里"最末新增"的关注节点居中。优先级：
     * 1) 最末一个 case 节点（生成用例阶段最新增）
     * 2) 最末一个 point 节点（提取阶段最新增）
     * 3) 整棵树居中
     */
    function centerOnLatestNode() {
      if (!mind || !mind.nodeData) return
      var latestCase = null
      var latestPoint = null
      function walk(node) {
        if (!node) return
        if (node.nodeType === 'case') latestCase = node
        else if (node.nodeType === 'point') latestPoint = node
        if (node.children) node.children.forEach(walk)
      }
      walk(mind.nodeData)
      var target = latestCase || latestPoint
      if (target) centerOnNode(target.id)
      else if (mind.toCenter) mind.toCenter()
    }

    function initMind() {
      if (!props.treeData || !container.value) return

      var nodeData = toME(props.treeData, true)
      if (!nodeData) return

      var data = { nodeData: nodeData, arrows: [], summaries: [], direction: MindElixir.RIGHT }

      if (mind) {
        mind.refresh(data)
        setTimeout(function() {
          renderPriorityBadges()
        }, 0)
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

      // 渲染完成后插入优先级徽章
      setTimeout(function() {
        renderPriorityBadges()
      }, 0)

      // 生成中（disabled=true）：每次重建后把视图定位到最近新增的节点，避免被推出可视范围
      if (props.disabled) {
        setTimeout(function() { centerOnLatestNode() }, 0)
      }

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

      // 选中节点后重新应用闪动 class（防止被 Mind Elixir 清除），并重建徽章布局
      mind.bus.addListener('selectNode', function() {
        setTimeout(function() {
          syncLoadingStates()
          renderPriorityBadges()
        }, 0)
      })
      mind.bus.addListener('unselectNode', function() {
        setTimeout(function() {
          syncLoadingStates()
          renderPriorityBadges()
        }, 0)
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
          // 用 offsetWidth/offsetHeight 而非 getBoundingClientRect，
          // 因为 Mind Elixir 通过 transform: scale 实现缩放，rect 是缩放后的视口尺寸，
          // 而 inline style 是缩放前的布局尺寸，混用会导致编辑框尺寸与节点对不上
          setTimeout(function() {
            var tpcEl = operation.obj ? safeFindEle(operation.obj.id) : null
            var inputBox = document.getElementById('input-box')
            if (inputBox && tpcEl) {
              var width = tpcEl.offsetWidth
              var height = tpcEl.offsetHeight
              inputBox.style.width = width + 'px'
              inputBox.style.minWidth = width + 'px'
              inputBox.style.maxWidth = width + 'px'
              inputBox.style.height = height + 'px'
              inputBox.style.minHeight = height + 'px'
              inputBox.style.maxHeight = height + 'px'
              inputBox.style.overflow = 'auto'
              inputBox.style.zIndex = '1000'
            }
          }, 10)
        } else if (operation && (operation.name === 'addChild' || operation.name === 'addSibling' || operation.name === 'addParent')) {
          // 新创建的节点，设置为自由节点样式
          if (operation.obj) {
            operation.obj.nodeType = 'free'
            operation.obj.style = { background: 'transparent', color: '#333' }
            var tpcEl = safeFindEle(operation.obj.id)
            if (tpcEl) {
              tpcEl.style.background = 'transparent'
              tpcEl.style.color = '#333'
            }
          }
          // 新增节点会触发父节点重新布局，徽章 wrapper 可能丢失，需重新渲染
          setTimeout(function() { renderPriorityBadges() }, 0)
          emitUpdate()
        } else if (operation && operation.name === 'finishEdit') {
          // 编辑结束后 Mind Elixir 会重置节点 DOM，需要重新渲染徽章和包装
          setTimeout(function() { renderPriorityBadges() }, 0)
          emitUpdate()
        } else {
          // 其它操作（移动、删除等）也可能影响节点 DOM，统一防御性重新渲染
          setTimeout(function() { renderPriorityBadges() }, 0)
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

      // 设为用例时，自动添加 P2 优先级（如果没有优先级）
      if (newType === 'case' && !nodeData.priority) {
        nodeData.priority = 'priority-3'
      }

      // 设为自由节点时，移除优先级
      if (newType === 'free') {
        nodeData.priority = null
      }

      var tpcEl = safeFindEle(nodeId)
      if (tpcEl) {
        tpcEl.style.background = isFree ? 'transparent' : (NODE_COLORS[newType] || NODE_COLORS.step)
        tpcEl.style.color = isFree ? '#333' : '#fff'
      }

      // 重新渲染徽章
      renderPriorityBadges()

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

        // 设为用例时，自动添加 P2 优先级（如果没有优先级）
        if (newType === 'case' && !child.priority) {
          child.priority = 'priority-3'
        }

        // 设为自由节点时，移除优先级
        if (newType === 'free') {
          child.priority = null
        }

        // 节点可能被祖先折叠（如 case 折叠状态下设置为自由节点），DOM 不存在时只同步数据
        var tpcEl = safeFindEle(child.id)
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

    // ---- 优先级编辑器 ----

    function renderPriorityBadges() {
      if (!mind || !container.value) return

      // 清理已有徽章
      container.value.querySelectorAll('.priority-badge').forEach(function(badge) {
        badge.remove()
      })
      // 清理 flex 标记并解包装：把 .me-tpc-content 内的子节点放回 me-tpc
      // 注意：Mind Elixir 选中节点时会用 className = "selected" 整体覆盖 class，
      // 所以这里改用 data-attribute 做标记，避免被覆盖
      container.value.querySelectorAll('me-tpc[data-has-priority-badge]').forEach(function(tpc) {
        tpc.removeAttribute('data-has-priority-badge')
        var wrapper = tpc.querySelector(':scope > .me-tpc-content')
        if (wrapper) {
          while (wrapper.firstChild) {
            tpc.insertBefore(wrapper.firstChild, wrapper)
          }
          wrapper.remove()
        }
      })

      // 遍历所有节点，为有优先级的节点插入徽章
      function traverseAndRender(nodeData) {
        if (!nodeData) return

        if (nodeData.priority) {
          var tpcEl = safeFindEle(nodeData.id)
          if (tpcEl && !tpcEl.querySelector('.priority-badge')) {
            var config = PRIORITY_CONFIG[nodeData.priority]
            if (config) {
              // 把 me-tpc 现有子节点（排除绝对定位的辅助元素）整体包进 .me-tpc-content
              var wrapper = document.createElement('span')
              wrapper.className = 'me-tpc-content'
              var toWrap = []
              for (var i = 0; i < tpcEl.childNodes.length; i++) {
                var child = tpcEl.childNodes[i]
                if (child.nodeType === 1) {
                  if (child.classList.contains('generating-mask')) continue
                  if (child.classList.contains('insert-preview')) continue
                  if (child.classList.contains('priority-badge')) continue
                }
                toWrap.push(child)
              }
              toWrap.forEach(function(n) { wrapper.appendChild(n) })
              tpcEl.appendChild(wrapper)

              var badge = document.createElement('span')
              badge.className = 'priority-badge'
              badge.setAttribute('data-priority', nodeData.priority)
              badge.setAttribute('data-node-id', nodeData.id)
              badge.style.cssText = 'background-color: ' + config.color + '; ' +
                'color: #fff; ' +
                'display: inline-flex; ' +
                'align-items: center; ' +
                'justify-content: center; ' +
                'padding: 0 6px; ' +
                'height: 18px; ' +
                'border-radius: 3px; ' +
                'font-size: 11px; ' +
                'font-weight: bold; ' +
                'margin-right: 6px; ' +
                'cursor: pointer; ' +
                'user-select: none; ' +
                'flex-shrink: 0; ' +
                'pointer-events: auto; ' +
                'z-index: 10;'
              badge.textContent = config.label

              // 直接在徽章上绑定事件监听器，阻止 Mind Elixir 拦截
              var stopAndShow = function(e) {
                e.stopPropagation()
                e.preventDefault()
                e.stopImmediatePropagation()
              }

              badge.addEventListener('mousedown', stopAndShow, true)
              badge.addEventListener('pointerdown', stopAndShow, true)
              badge.addEventListener('click', function(e) {
                e.stopPropagation()
                e.preventDefault()
                e.stopImmediatePropagation()

                var currentPriority = badge.getAttribute('data-priority')
                var nodeId = badge.getAttribute('data-node-id')
                if (!nodeId) return

                showPrioritySelector(badge, nodeId, currentPriority)
              }, true)

              // 徽章作为第一个 flex item 放在内容包装前
              tpcEl.insertBefore(badge, wrapper)
              tpcEl.setAttribute('data-has-priority-badge', 'true')
            }
          }
        }

        if (nodeData.children) {
          nodeData.children.forEach(traverseAndRender)
        }
      }

      traverseAndRender(mind.nodeData)

      // 徽章插入后节点宽度变化，重新绘制连接线
      mind.linkDiv()
    }

    function showPrioritySelector(badgeEl, nodeId, currentPriority) {
      // 移除已存在的选择器
      var existingSelector = document.querySelector('.priority-selector')
      if (existingSelector) existingSelector.remove()

      // 创建选择器
      var selector = document.createElement('div')
      selector.className = 'priority-selector'
      selector.style.cssText = 'position: absolute; background: #fff; border: 1px solid #dcdfe6; ' +
        'border-radius: 4px; box-shadow: 0 2px 12px 0 rgba(0,0,0,.1); padding: 4px 0; z-index: 9999;'

      var priorities = ['priority-1', 'priority-2', 'priority-3', 'priority-4']
      priorities.forEach(function(p) {
        var config = PRIORITY_CONFIG[p]
        var item = document.createElement('div')
        item.className = 'priority-option'
        item.setAttribute('data-priority', p)
        item.style.cssText = 'padding: 8px 16px; cursor: pointer; display: flex; align-items: center; ' +
          'font-size: 14px; color: #606266; transition: background-color 0.2s;'
        if (p === currentPriority) {
          item.style.backgroundColor = '#f5f7fa'
        }

        var badge = document.createElement('span')
        badge.style.cssText = 'display: inline-flex; align-items: center; justify-content: center; ' +
          'padding: 0 6px; height: 18px; border-radius: 3px; color: #fff; font-size: 11px; ' +
          'font-weight: bold; background-color: ' + config.color + ';'
        badge.textContent = config.label

        item.appendChild(badge)

        item.addEventListener('mouseenter', function() {
          if (p !== currentPriority) {
            item.style.backgroundColor = '#f5f7fa'
          }
        })
        item.addEventListener('mouseleave', function() {
          if (p !== currentPriority) {
            item.style.backgroundColor = ''
          }
        })
        item.addEventListener('click', function() {
          updateNodePriority(nodeId, p)
          selector.remove()
        })

        selector.appendChild(item)
      })

      // 定位选择器
      var rect = badgeEl.getBoundingClientRect()
      selector.style.left = rect.left + 'px'
      selector.style.top = (rect.bottom + 4) + 'px'

      document.body.appendChild(selector)

      // 点击外部关闭
      setTimeout(function() {
        var closeHandler = function(e) {
          if (!selector.contains(e.target)) {
            selector.remove()
            document.removeEventListener('click', closeHandler)
          }
        }
        document.addEventListener('click', closeHandler)
      }, 0)
    }

    function updateNodePriority(nodeId, newPriority) {
      if (!mind) return

      var nodeData = mind.getObjById(nodeId, mind.nodeData)
      if (!nodeData) return

      // 更新节点的优先级数据
      nodeData.priority = newPriority

      // 重新渲染徽章
      renderPriorityBadges()

      emitUpdate()
    }

    // ---- 工具栏 ----

    function expandAll() {
      if (!mind) return
      // 清掉折叠 flag，让后续 initMind / toME 不再强制折叠 case
      collapseAllCasesFlag = false
      var rootTpc = container.value.querySelector('me-root me-tpc')
      if (rootTpc) mind.expandNodeAll(rootTpc, true)
    }

    function collapseAll() {
      if (!mind) return
      var rootTpc = container.value.querySelector('me-root me-tpc')
      if (rootTpc) mind.expandNodeAll(rootTpc, false)
    }

    /**
     * 一键折叠所有用例：把 nodeType === 'case' 的节点折起来（隐藏前置条件/步骤等子节点），
     * 模块和测试点保持当前展开状态。
     *
     * 实现说明：直接改 mind.nodeData 上 case 节点的 expanded=false，再调 mind.layout() 重排。
     * 不用 mind.expandNode(el, false) 逐个折叠 —— mind-elixir 5.11 的 expandNode 在折叠后
     * 会做 this.move(dx, dy) 来"保持节点视觉位置不动"，循环调用会累积平移把整张图推出可视区域，
     * 表现为"折叠后面板全空"。
     */
    function collapseAllCases() {
      if (!mind || !mind.nodeData) return
      collapseAllCasesFlag = true
      function walk(node) {
        if (!node) return
        if (node.nodeType === 'case') node.expanded = false
        if (node.children) node.children.forEach(walk)
      }
      walk(mind.nodeData)
      mind.layout()
      mind.linkDiv()
      // layout 会清空 nodes 容器并重建 DOM，徽章 wrapper 会丢失，需重新渲染
      setTimeout(function() { renderPriorityBadges() }, 0)
      // 居中回到根节点附近，避免历次平移残留导致的偏移
      if (mind.toCenter) mind.toCenter()
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

      // 重新渲染优先级徽章
      setTimeout(function() {
        renderPriorityBadges()
        syncLoadingStates()
      }, 50)

      // 生成期间：把刚加的最后一条用例居中（无用例时回退到 point 节点）
      if (props.disabled) {
        setTimeout(function() {
          var lastCase = cases && cases.length ? cases[cases.length - 1] : null
          centerOnNode(lastCase ? lastCase.id : pointId)
        }, 60)
      }

      // 注意：流式中间态不调 emitUpdate 写回 store，避免 isInternalUpdate 屏蔽
      // 后续接收的 POINTS_GENERATED 整树推送会作为权威更新触发 watch + 重建
    }

    // ---- 生命周期 ----

    /**
     * 强制销毁并重建 mind-elixir 实例。
     * 用例生成阶段会高频调用 updatePointCases（直接改 nodeData + 局部 layout），mind-elixir 内部
     * 的 DOM↔nodeObj 映射、selection、disposable 等会逐步累积漂移；之后用户一缩放/再操作就可能把
     * 画布推出可视区或画成空白。生成结束这一刻销毁重建相当于"重进页面"，把状态清干净。
     */
    function rebuild() {
      if (!props.treeData || !container.value) return
      if (mind) {
        try {
          if (typeof mind.destroy === 'function') mind.destroy()
        } catch (e) {
          // mind-elixir 内部异常忽略，下面的 innerHTML 兜底会清掉残留
        }
        mind = null
      }
      // 兜底：destroy 可能没清干净 DOM
      if (container.value) container.value.innerHTML = ''
      nextTick(function() {
        initMind()
        setTimeout(syncLoadingStates, 50)
      })
    }

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
      container, readonlyOnly,
      expandAll, collapseAll, collapseAllCases, zoomIn, zoomOut, fitView,
      updatePointCases, centerOnNode, rebuild
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
  align-items: center;
  background: #fafafa;
  flex-shrink: 0;
}
.disabled-tip {
  margin-left: auto;
  font-size: 12px;
  color: #e6a23c;
  display: flex;
  align-items: center;
  gap: 4px;
}
.container-wrap {
  flex: 1;
  position: relative;
  display: flex;
  min-height: 0;
}
.mind-container {
  flex: 1;
  overflow: hidden;
  background: #f9f9f9;
  position: relative;
  min-height: 400px;
  width: 100%;
}
.edit-blocker {
  position: absolute;
  inset: 0;
  background: transparent;
  cursor: not-allowed;
  z-index: 10;
}
.tree-disabled .mind-container { opacity: 0.85; }
</style>

<style>
/* Mind Elixir 样式微调 */
.map-container me-root me-tpc {
  font-size: 16px !important;
  font-weight: 600 !important;
}
/* 非 root 节点最大宽度调整为原 35em 的 2/3，短内容仍自适应 */
.map-container me-parent me-tpc {
  max-width: 23.3em !important;
  word-break: break-word;
}
/* 带优先级徽章的节点：用 flex 布局让徽章保持在节点前端整体的垂直中心，文本可换行 */
/* 用 data-attribute 而非 class，避免被 Mind Elixir 选中时的 className 重置覆盖 */
.map-container me-parent me-tpc[data-has-priority-badge] {
  display: flex !important;
  align-items: center;
}
.map-container me-parent me-tpc[data-has-priority-badge] .me-tpc-content {
  flex: 1 1 auto;
  min-width: 0;
  word-break: break-word;
  white-space: pre-wrap;
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

/* 编辑框样式：让 Mind Elixir 自动复制原节点的 background/color，
   节点是什么颜色，编辑时就是什么颜色，体验上等同于"在节点里直接输入"。
   仅加 outline 作为编辑态视觉提示。 */
.map-container #input-box {
  z-index: 1000 !important;
  overflow: auto !important;
  box-sizing: border-box !important;
  outline: 2px solid #409eff !important;
  outline-offset: 1px !important;
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
