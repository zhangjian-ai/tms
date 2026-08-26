<template>
  <div class="case-select-tree">
    <div class="tools">
      <el-select
        v-model="filterLevels"
        multiple
        collapse-tags
        clearable
        size="small"
        placeholder="按优先级筛选（默认全部）"
        class="level-filter"
        @change="applyFilter"
      >
        <el-option v-for="lv in LEVELS" :key="lv" :label="lv" :value="lv" />
      </el-select>
      <el-checkbox
        :model-value="allChecked"
        :indeterminate="indeterminate"
        :disabled="visibleCount === 0"
        @change="toggleAll"
      >全选</el-checkbox>
    </div>

    <div class="tree-wrap">
      <el-tree
        ref="treeRef"
        :key="treeKey"
        :data="treeOptions"
        node-key="id"
        show-checkbox
        :default-expanded-keys="expandedKeys"
        :expand-on-click-node="false"
        :props="{ label: 'label', children: 'children' }"
        @check="onCheck"
      >
        <template #default="{ data }">
          <span class="node-label">
            <el-tag
              v-if="data.nodeType === 'case' && data.level"
              size="small"
              :type="levelTagType(data.level)"
              effect="plain"
            >{{ data.level }}</el-tag>
            <span class="node-text">{{ data.label }}</span>
          </span>
        </template>
      </el-tree>
      <div v-if="totalCases === 0" class="empty-tip">当前面板没有可选用例</div>
      <div v-else-if="visibleCount === 0" class="empty-tip">没有符合筛选条件的用例</div>
    </div>

    <div class="summary">
      共 {{ totalCases }} 个用例<template v-if="filterLevels.length">，当前显示 {{ visibleCount }} 个</template>，已选 {{ checkedCaseCount }} 个
    </div>
  </div>
</template>

<script>
import { ref, computed, onMounted, watch } from 'vue'

const LEVELS = ['P0', 'P1', 'P2', 'P3']

// 优先级图标 → 用例等级：priority-1..4 ↔ P0..P3
function levelFromIcons(icons) {
  if (!Array.isArray(icons)) return ''
  for (const ic of icons) {
    if (typeof ic === 'string' && ic.startsWith('priority-')) {
      const n = parseInt(ic.slice('priority-'.length), 10)
      if (!Number.isNaN(n)) return 'P' + (n - 1)
    }
  }
  return ''
}

export default {
  name: 'CaseSelectTree',
  props: {
    treeData: { type: Object, default: null },
    // 已选用例 id 数组（v-model）
    modelValue: { type: Array, default: () => [] }
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const treeRef = ref(null)
    const treeKey = ref(0)
    const fullTree = ref(null)
    const treeOptions = ref([])
    const expandedKeys = ref([])
    const allCaseIds = ref([])
    const levelMap = ref({})
    const selectedIds = ref(new Set())
    const filterLevels = ref([])
    const checkedCaseCount = ref(0)
    const visibleCount = ref(0)
    const visibleCheckedCount = ref(0)

    const totalCases = computed(() => allCaseIds.value.length)
    const allChecked = computed(() => visibleCount.value > 0 && visibleCheckedCount.value === visibleCount.value)
    const indeterminate = computed(() => visibleCheckedCount.value > 0 && visibleCheckedCount.value < visibleCount.value)

    function buildOptions(node, caseIds, lvMap) {
      if (!node) return null
      const type = node.type
      if (type === 'case') {
        const level = levelFromIcons(node.icons)
        caseIds.push(node.id)
        lvMap[node.id] = level
        return { id: node.id, label: node.title || '(未命名用例)', nodeType: 'case', level }
      }
      if (type === 'step' || type === 'free') return null
      const children = []
      if (Array.isArray(node.children)) {
        for (const c of node.children) {
          const opt = buildOptions(c, caseIds, lvMap)
          if (opt) children.push(opt)
        }
      }
      if (type === 'module' && children.length === 0) return null
      return { id: node.id, label: node.title || '(未命名目录)', nodeType: type, children }
    }

    function filterTree(opt, levelSet) {
      if (!opt) return null
      if (opt.nodeType === 'case') return levelSet.has(opt.level) ? opt : null
      const children = []
      for (const c of (opt.children || [])) {
        const kept = filterTree(c, levelSet)
        if (kept) children.push(kept)
      }
      if (opt.nodeType === 'module' && children.length === 0) return null
      return { ...opt, children }
    }

    function foldedKeys(opt, out, isRoot) {
      if (!opt || !Array.isArray(opt.children) || opt.children.length === 0) return
      const hasDirChild = opt.children.some(c => c.nodeType === 'root' || c.nodeType === 'module')
      if (isRoot || hasDirChild) out.push(opt.id)
      opt.children.forEach(c => foldedKeys(c, out, false))
    }

    function allDirKeys(opt, out) {
      if (!opt || opt.nodeType === 'case') return
      out.push(opt.id)
      ;(opt.children || []).forEach(c => allDirKeys(c, out))
    }

    function visibleCaseIds() {
      const f = filterLevels.value
      if (!f.length) return allCaseIds.value.slice()
      return allCaseIds.value.filter(id => f.includes(levelMap.value[id]))
    }

    function emitSelection() {
      emit('update:modelValue', [...selectedIds.value])
    }

    function syncCount() {
      checkedCaseCount.value = selectedIds.value.size
      const vis = visibleCaseIds()
      visibleCount.value = vis.length
      visibleCheckedCount.value = vis.reduce((n, id) => n + (selectedIds.value.has(id) ? 1 : 0), 0)
    }

    function applyFilter() {
      const f = filterLevels.value
      const exp = []
      if (!f.length) {
        treeOptions.value = fullTree.value ? [fullTree.value] : []
        if (fullTree.value) foldedKeys(fullTree.value, exp, true)
      } else {
        const ft = fullTree.value ? filterTree(fullTree.value, new Set(f)) : null
        treeOptions.value = ft ? [ft] : []
        if (ft) allDirKeys(ft, exp)
      }
      expandedKeys.value = exp
      treeKey.value++
      // 重挂载后回填「当前可见 ∩ 已选」
      requestAnimationFrame(() => {
        if (treeRef.value) {
          const vis = visibleCaseIds()
          treeRef.value.setCheckedKeys(vis.filter(id => selectedIds.value.has(id)))
          syncCount()
        }
      })
    }

    function rebuild() {
      const ids = []
      const lvMap = {}
      fullTree.value = buildOptions(props.treeData, ids, lvMap)
      allCaseIds.value = ids
      levelMap.value = lvMap
      // 初始化选择：取交集（保留 modelValue 中仍存在的用例）
      const valid = new Set(ids)
      selectedIds.value = new Set((props.modelValue || []).filter(id => valid.has(id)))
      filterLevels.value = []
      applyFilter()
      emitSelection()
    }

    // el-tree 勾选变化 → 同步 selectedIds：仅覆盖当前可见用例，保留被筛选隐藏的已选项
    function onCheck() {
      if (!treeRef.value) return
      const vis = new Set(visibleCaseIds())
      const checkedVisible = new Set((treeRef.value.getCheckedKeys(true) || []).filter(k => vis.has(k)))
      const next = new Set()
      selectedIds.value.forEach(id => { if (!vis.has(id)) next.add(id) })
      checkedVisible.forEach(id => next.add(id))
      selectedIds.value = next
      syncCount()
      emitSelection()
    }

    function toggleAll(val) {
      if (!treeRef.value) return
      const vis = visibleCaseIds()
      treeRef.value.setCheckedKeys(val ? vis : [])
      onCheck()
    }

    // 清空全部已选（含被筛选隐藏的项）；供外部工具条调用
    function clearAll() {
      selectedIds.value = new Set()
      if (treeRef.value) treeRef.value.setCheckedKeys([])
      syncCount()
      emitSelection()
    }

    function levelTagType(level) {
      return { P0: 'danger', P1: 'warning', P2: '', P3: 'info' }[level] || 'info'
    }

    onMounted(rebuild)
    watch(() => props.treeData, rebuild)

    return {
      LEVELS,
      treeRef, treeKey, treeOptions, expandedKeys, filterLevels,
      totalCases, checkedCaseCount, visibleCount, allChecked, indeterminate,
      applyFilter, onCheck, toggleAll, clearAll, levelTagType
    }
  }
}
</script>

<style scoped>
.case-select-tree { display: flex; flex-direction: column; gap: 8px; }
.tools { display: flex; align-items: center; gap: 12px; justify-content: flex-end; }
.level-filter { width: 220px; }
.tree-wrap {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  max-height: 320px;
  overflow: auto;
  padding: 6px;
  position: relative;
}
.node-label { display: inline-flex; align-items: center; gap: 6px; }
.node-text { word-break: break-all; }
.empty-tip { color: #909399; font-size: 13px; padding: 16px; text-align: center; }
.summary { font-size: 12px; color: #909399; }
</style>
