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
import { ref, watch, onMounted, nextTick, computed } from 'vue'
import MindElixir from 'mind-elixir'
import { Loading } from '@element-plus/icons-vue'
import { NODE_COLORS, PLAIN_NODE_TYPES, PLAIN_TEXT_COLOR, PRIORITY_CONFIG, generateMainBranch, generateSubBranch } from './xmindTheme'

export default {
  name: 'XMindTreePanel',
  components: { Loading },
  props: {
    treeData: { type: Object, default: null },
    generatingNodeIds: { type: Object, default: () => new Set() },
    disabled: { type: Boolean, default: false },
    disabledTip: { type: String, default: '' }
  },
  emits: ['update', 'generate-cases'],
  setup(props, { emit }) {
    const container = ref(null)
    const readonlyOnly = computed(() => /只读/.test(props.disabledTip || ''))
    let mind = null
    let isInternalUpdate = false
    let internalUpdateToken = 0
    // 会话级折叠状态：用户点"折叠用例"后置 true，下次 initMind / toME 时把所有 case 节点渲染为折叠
    // 不写入 store/Redis，纯本地 UI 行为
    let collapseAllCasesFlag = false
    // 徽章渲染调度令牌：多次重排重叠触发时，只让最后一次在布局完成后执行
    let badgeRenderToken = 0

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

    // 删除后应选中的目标 id：以最后一个待删节点为基准，先向前再向后找未被同批删除的兄弟，都没有才回退父节点
    function pickSelectionAfterRemove(nodes) {
      try {
        var list = Array.isArray(nodes) ? nodes : (nodes ? [nodes] : [])
        if (list.length === 0) return null
        var base = list[list.length - 1]
        var nodeObj = base && base.nodeObj
        var parent = nodeObj && nodeObj.parent
        if (!parent || !parent.children) return null

        var removingIds = {}
        list.forEach(function(el) {
          if (el && el.nodeObj) removingIds[el.nodeObj.id] = true
        })

        var sibs = parent.children
        var idx = sibs.indexOf(nodeObj)
        var pick = null
        for (var i = idx - 1; i >= 0; i--) {
          if (sibs[i] && !removingIds[sibs[i].id]) { pick = sibs[i]; break }
        }
        if (!pick) {
          for (var j = idx + 1; j < sibs.length; j++) {
            if (sibs[j] && !removingIds[sibs[j].id]) { pick = sibs[j]; break }
          }
        }
        return pick ? pick.id : parent.id
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
      if (!mind || !props.generatingNodeIds) return
      props.generatingNodeIds.forEach(function(id) {
        applyLoadingState(id)
      })
    }

    // ---- 数据转换 ----

    function toME(node, isRoot) {
      if (!node) return null
      var failed = Array.isArray(node.icons) && node.icons.indexOf('failed') >= 0
      var typeColor = NODE_COLORS[node.type] || NODE_COLORS.step
      // 无填充类型（步骤）：透明底 + 深色字，只靠连线着色；其余类型上底色 + 反白字
      var plain = PLAIN_NODE_TYPES.indexOf(node.type) >= 0
      var baseStyle = plain
        ? { background: 'transparent', color: PLAIN_TEXT_COLOR }
        : { background: typeColor, color: '#fff' }
      if (failed) {
        baseStyle.border = '2px solid #f56c6c'
      }
      const me = {
        topic: failed ? (node.title || '') + '  ⚠ 生成失败，可右键重试' : (node.title || ''),
        id: node.id,
        style: baseStyle,
        nodeType: node.type,
        // 连线颜色跟随节点类型色（mind-elixir 优先取 nodeObj.branchColor 给入线着色）
        branchColor: typeColor,
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

    function initMind() {
      if (!props.treeData || !container.value) return

      var nodeData = toME(props.treeData, true)
      if (!nodeData) return

      var data = { nodeData: nodeData, arrows: [], summaries: [], direction: MindElixir.RIGHT }

      if (mind) {
        mind.refresh(data)
        scheduleRenderBadges()
        // 刷新画布：把根节点居中（refresh 不会自动居中）
        if (!props.disabled && mind.toCenter) mind.toCenter()
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
                if (selectedNode && selectedNode.nodeObj.nodeType === 'module') {
                  emit('generate-cases', selectedNode.nodeObj.id)
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
                  // 数据全部改完后重建徽章，清除子节点残留的优先级徽章
                  renderPriorityBadges()
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
        mouseSelectionButton: 2,
        // 直角折线连线（见 xmindTheme.js）；层级横向间距由 CSS 变量统一控制
        generateMainBranch: generateMainBranch,
        generateSubBranch: generateSubBranch
      })

      mind.init(data)

      // 删除后优先选中兄弟节点，无兄弟才回退到父节点；期间禁用 scrollIntoView，避免画布跳动
      var originalRemoveNodes = mind.removeNodes.bind(mind)
      mind.removeNodes = function(nodes) {
        var targetId = pickSelectionAfterRemove(nodes || mind.currentNodes)
        var savedScroll = mind.scrollIntoView
        mind.scrollIntoView = function() {}
        try {
          originalRemoveNodes(nodes)
          if (targetId) {
            var el = safeFindEle(targetId)
            if (el) mind.selectNode(el)
          }
        } finally {
          mind.scrollIntoView = savedScroll
        }
      }

      // 撤销/重做走 refresh 重绘、不触发 operation，需手动补齐徽章、加载态与写回
      if (typeof mind.undo === 'function') {
        var originalUndo = mind.undo.bind(mind)
        mind.undo = function() {
          originalUndo()
          scheduleRenderBadges()
          setTimeout(syncLoadingStates, 50)
          emitUpdate()
        }
      }
      if (typeof mind.redo === 'function') {
        var originalRedo = mind.redo.bind(mind)
        mind.redo = function() {
          originalRedo()
          scheduleRenderBadges()
          setTimeout(syncLoadingStates, 50)
          emitUpdate()
        }
      }

      scheduleRenderBadges()

      // 拦截正在生成用例的节点的右键菜单
      container.value.addEventListener('contextmenu', function(e) {
        var target = e.target
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
          if (nodeId && props.generatingNodeIds && props.generatingNodeIds.has(nodeId)) {
            e.preventDefault()
            e.stopPropagation()
            e.stopImmediatePropagation()
            return false
          }
        }
      }, true)

      // IME 合成（中文拼音选字）中按回车只是确认候选词，捕获阶段拦掉，
      // 避免 mind-elixir 把它当成结束编辑；不 preventDefault，输入法照常工作
      container.value.addEventListener('keydown', function(e) {
        var t = e.target
        if (t && t.id === 'input-box' && (e.isComposing || e.keyCode === 229)) {
          e.stopPropagation()
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

      // 折叠/展开会重建节点 DOM，注入的徽章随之丢失，需重新渲染
      mind.bus.addListener('expandNode', function() {
        scheduleRenderBadges()
        // 单节点折叠/展开也写回，持久化折叠状态（只读/生成中不写回）
        persistFoldState()
      })

      // 监听右键菜单显示，动态控制菜单项可见性和顺序
      mind.bus.addListener('showContextMenu', function() {
        var selectedNode = mind.currentNode
        var nodeObj = selectedNode ? selectedNode.nodeObj : null

        // 如果目标节点正在生成用例，阻止菜单显示
        if (nodeObj && props.generatingNodeIds && props.generatingNodeIds.has(nodeObj.id)) {
          return
        }

        setTimeout(function() {
          var menu = container.value.querySelector('.context-menu .menu-list')
          if (!menu) return

          var menuItems = Array.from(menu.querySelectorAll('li'))
          var selectedNode = mind.currentNode
          var nodeObj = selectedNode ? selectedNode.nodeObj : null

          // 自定义菜单项名称列表（按期望顺序）
          var customMenus = ['生成用例', '设为目录', '设为用例', '设为自由节点']
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

          menuItems.forEach(function(li) {
            var text = li.querySelector('span')?.textContent
            if (text === '生成用例') {
              // 目录（module）节点可生成用例；正在生成时置灰
              var isModule = nodeObj && nodeObj.nodeType === 'module'
              var isGenerating = props.generatingNodeIds && props.generatingNodeIds.has(nodeObj && nodeObj.id)
              var spanEl = li.querySelector('span')
              if (isModule && !isGenerating) {
                li.style.display = ''
                li.style.opacity = ''
                li.style.pointerEvents = ''
                if (spanEl) spanEl.style.color = ''
              } else if (isModule && isGenerating) {
                li.style.display = ''
                li.style.opacity = ''
                li.style.pointerEvents = 'none'
                if (spanEl) spanEl.style.color = '#999'
              } else {
                li.style.display = 'none'
              }
            } else if (text === '设为目录') {
              li.style.display = (nodeObj && canSetType(nodeObj, 'module')) ? '' : 'none'
            } else if (text === '设为用例') {
              li.style.display = (nodeObj && canSetType(nodeObj, 'case')) ? '' : 'none'
            } else if (text === '设为自由节点') {
              li.style.display = (nodeObj && canResetToFree(nodeObj)) ? '' : 'none'
            }
          })
        }, 10)
      })

      mind.bus.addListener('operation', function(operation) {
        if (operation && operation.name === 'beginEdit') {
          if (operation.obj &&
              props.generatingNodeIds && props.generatingNodeIds.has(operation.obj.id)) {
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
            operation.obj.style = { background: NODE_COLORS.free, color: '#fff' }
            operation.obj.branchColor = NODE_COLORS.free
            var tpcEl = safeFindEle(operation.obj.id)
            if (tpcEl) {
              tpcEl.style.background = NODE_COLORS.free
              tpcEl.style.color = '#fff'
            }
          }
          // 新增节点会触发父节点重新布局，徽章 wrapper 可能丢失，需重新渲染
          scheduleRenderBadges()
          emitUpdate()
        } else if (operation && operation.name === 'finishEdit') {
          // 编辑结束后 Mind Elixir 会重置节点 DOM，需要重新渲染徽章和包装
          scheduleRenderBadges()
          emitUpdate()
        } else {
          // 其它操作（移动、删除等）也可能影响节点 DOM，统一防御性重新渲染
          scheduleRenderBadges()
          emitUpdate()
        }
      })
    }

    // ---- 节点类型设置 ----

    function canSetType(nodeObj, targetType) {
      if (!nodeObj || nodeObj.root) return false

      // 只有自由节点可以设置类型
      if (nodeObj.nodeType !== 'free') return false

      var parentType = findParentType(nodeObj.id)

      if (targetType === 'case') {
        // 用例挂在目录（module）节点下（目录可多级）
        return parentType === 'module'
      } else if (targetType === 'module') {
        return parentType === 'module' || parentType === 'root'
      }

      return false
    }

    function setNodeType(nodeId, newType) {
      if (!mind) return

      var nodeData = mind.getObjById(nodeId, mind.nodeData)
      if (!nodeData) return

      var bg = NODE_COLORS[newType] || NODE_COLORS.step
      nodeData.nodeType = newType
      nodeData.style = { background: bg, color: '#fff' }
      nodeData.branchColor = bg

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
        tpcEl.style.background = bg
        tpcEl.style.color = '#fff'
      }

      renderPriorityBadges()

      emitUpdate()
    }

    function setChildrenType(nodeObj, newType) {
      if (!nodeObj || !nodeObj.children) return
      var bg = NODE_COLORS[newType] || NODE_COLORS.step
      var plain = PLAIN_NODE_TYPES.indexOf(newType) >= 0
      for (var i = 0; i < nodeObj.children.length; i++) {
        var child = nodeObj.children[i]
        child.nodeType = newType
        // 无填充类型（步骤）：透明底 + 深色字；连线仍取该类型色
        child.style = plain
          ? { background: 'transparent', color: PLAIN_TEXT_COLOR }
          : { background: bg, color: '#fff' }
        child.branchColor = bg

        // 设为用例时，自动添加 P2 优先级（如果没有优先级）
        if (newType === 'case' && !child.priority) {
          child.priority = 'priority-3'
        }

        // 设为自由节点时，移除优先级
        if (newType === 'free') {
          child.priority = null
        }

        // 节点可能被折叠，DOM 不存在时只同步数据
        var tpcEl = safeFindEle(child.id)
        if (tpcEl) {
          tpcEl.style.background = plain ? 'transparent' : bg
          tpcEl.style.color = plain ? PLAIN_TEXT_COLOR : '#fff'
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
    // 在浏览器完成布局/绘制后再插入徽章
    function scheduleRenderBadges() {
      var token = ++badgeRenderToken
      requestAnimationFrame(function() {
        requestAnimationFrame(function() {
          if (token !== badgeRenderToken) return
          renderPriorityBadges()
        })
      })
    }

    function renderPriorityBadges() {
      if (!mind || !container.value) return

      container.value.querySelectorAll('.priority-badge').forEach(function(badge) {
        badge.remove()
      })
      // 清理 flex 标记并解包装：把 .me-tpc-content 内的子节点放回 me-tpc。
      // 用 data-attribute 而非 class 做标记，避免被 Mind Elixir 选中时的 className 重置覆盖
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
      var existingSelector = document.querySelector('.priority-selector')
      if (existingSelector) existingSelector.remove()

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

      nodeData.priority = newPriority

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
      // expandNodeAll 不触发 expandNode 事件，需手动补徽章
      scheduleRenderBadges()
      persistFoldState()
    }

    function collapseAll() {
      if (!mind) return
      var rootTpc = container.value.querySelector('me-root me-tpc')
      if (rootTpc) mind.expandNodeAll(rootTpc, false)
      scheduleRenderBadges()
      persistFoldState()
    }

    /**
     * 一键折叠所有用例：把 nodeType === 'case' 的节点折起来（隐藏前置条件/步骤等子节点），
     * 模块和用例节点保持当前展开状态。
     *
     * 实现说明：改 mind.nodeData 上 case 节点的 expanded=false 后重排。折叠使整树尺寸骤减，
     * 而画布 map 的 transform（平移量）不会自动跟着变——layout()/linkDiv() 都不改 transform，
     * 只有 init 才 toCenter——于是重排后整棵树相对视口发生位移、被 overflow:hidden 裁掉，
     * 表现为"折叠后用例树整个消失"。
     *
     * 修复：仿照库内 expandNodeAll(xn) 的做法，重排前后记录 root 的屏幕坐标，用差值 move() 补偿，
     * 把 root 锚回原来的屏幕位置（collapse 全部/expandNodeAll 正是靠这个才不跑飞）。
     * 不逐个 expandNode(el,false)：那样每次都会 move 一次、循环累积把图推飞。
     */
    function collapseAllCases() {
      if (!mind || !mind.nodeData) return
      collapseAllCasesFlag = true
      var rootBefore = safeFindEle(mind.nodeData.id)
      var before = rootBefore ? rootBefore.getBoundingClientRect() : null
      function walk(node) {
        if (!node) return
        if (node.nodeType === 'case') node.expanded = false
        if (node.children) node.children.forEach(walk)
      }
      walk(mind.nodeData)
      mind.layout()
      mind.linkDiv()
      // 位移补偿：把 root 拉回折叠前的屏幕位置，避免整树移出可视区
      var rootAfter = safeFindEle(mind.nodeData.id)
      var after = rootAfter ? rootAfter.getBoundingClientRect() : null
      if (before && after) mind.move(before.left - after.left, before.top - after.top)
      // layout 会清空 nodes 容器并重建 DOM，徽章 wrapper 会丢失，需重新渲染
      scheduleRenderBadges()
      persistFoldState()
    }

    function zoomIn() { if (mind) mind.scale(mind.scaleVal + 0.1) }
    function zoomOut() { if (mind) mind.scale(mind.scaleVal - 0.1) }
    function fitView() { if (mind) mind.toCenter() }

    function emitUpdate() {
      var myToken = ++internalUpdateToken
      isInternalUpdate = true
      setTimeout(function() {
        if (!mind) return
        var updated = fromME(mind.nodeData)
        if (updated) emit('update', updated)
        setTimeout(function() {
          // 仅当没有更晚的 emitUpdate 覆盖时才解除屏蔽
          if (myToken === internalUpdateToken) isInternalUpdate = false
        }, 200)
      }, 100)
    }

    // 折叠/展开状态写回（复用 emitUpdate 的全量写回 + 防抖保存链路）。
    // 只读/生成中不写回：避免只读态触发"只读"提示、以及生成期间与流式推送打架。
    function persistFoldState() {
      if (props.disabled) return
      emitUpdate()
    }

    // 增量更新指定节点的子树（不触发全量渲染）。children 为后端推送的该节点最新子树
    function updateNodeChildren(nodeId, children) {
      if (!mind) return

      var nodeData = mind.getObjById(nodeId, mind.nodeData)
      if (!nodeData) return

      nodeData.children = (children || []).map(function(c) { return toME(c, false) })

      // 局部重新布局和渲染连接线，不触发全量刷新
      mind.layout()
      mind.linkDiv()

      scheduleRenderBadges()
      setTimeout(function() {
        syncLoadingStates()
      }, 50)

      // 注意：流式中间态不调 emitUpdate 写回 store，避免 isInternalUpdate 屏蔽
      // 后续接收的 TREE_UPDATED 整树推送会作为权威更新触发 watch + 重建
    }

    // ---- 生命周期 ----

    /**
     * 强制销毁并重建 mind-elixir 实例
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

    watch(function() { return props.generatingNodeIds }, function(newIds, oldIds) {
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

    return {
      container, readonlyOnly,
      expandAll, collapseAll, collapseAllCases, zoomIn, zoomOut, fitView,
      updateNodeChildren, rebuild
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
/* 节点最大宽度：显示与编辑态统一取此值（短节点编辑时也放大到这个宽度） */
.map-container {
  --tms-node-max-width: 20em;
}
.map-container me-root me-tpc {
  font-size: 16px !important;
  font-weight: 600 !important;
}
/* root 与一级节点统一为长方形，与用例目录节点（me-parent me-tpc）一致 */
.map-container me-root me-tpc,
.map-container me-main > me-wrapper > me-parent > me-tpc {
  border: none !important;
  border-radius: 3px !important;
  padding: var(--topic-padding) !important;
}
/* ---- 统一各层级父子横向间距 ---- */
.map-container me-main > me-wrapper > me-parent {
  margin-left: 0 !important;
  margin-right: 0 !important;
  padding: 1px var(--node-gap-x) !important;
}
.map-container me-main > me-wrapper {
  margin-left: var(--node-gap-x) !important;
  margin-right: var(--node-gap-x) !important;
}
/* 非 root 节点最大宽度调整为原 35em 的 2/3，短内容仍自适应 */
.map-container me-parent me-tpc {
  max-width: var(--tms-node-max-width) !important;
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
/* 连线：直角折线 + 跟随节点类型色（stroke 由 branchColor 决定，不再统一覆盖成灰）。
   fill:none 防止折线被闭合填充；miter + butt 让转角保持尖直角。 */
.map-container .lines path,
.map-container .subLines path {
  fill: none !important;
  stroke-width: 2 !important;
  stroke-linejoin: miter !important;
  stroke-linecap: butt !important;
}
.map-container me-epd {
  top: 50% !important;
  transform: translateY(-50%) !important;
  opacity: 0.6 !important;
  transition: opacity 0.2s !important;
}
.map-container me-epd:hover {
  opacity: 1 !important;
}
/* 折叠按钮横向位置：各层级 me-parent 现已统一 padding(--node-gap-x) */
.map-container .rhs me-epd {
  right: calc(var(--node-gap-x) - 18px) !important;
  left: auto !important;
}
.map-container .lhs me-epd {
  left: calc(var(--node-gap-x) - 18px) !important;
  right: auto !important;
}

/* 编辑框样式：让 Mind Elixir 自动复制原节点的 background/color，
   节点是什么颜色，编辑时就是什么颜色，体验上等同于"在节点里直接输入"。
   仅加 outline 作为编辑态视觉提示。 */
.map-container #input-box {
  z-index: 1000 !important;
  box-sizing: border-box !important;
  outline: 2px solid #409eff !important;
  outline-offset: 1px !important;
  /* 编辑框随输入内容自动变宽（width:max-content），达到设定的节点最大宽度后才换行 */
  width: max-content !important;
  max-width: var(--tms-node-max-width) !important;
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
