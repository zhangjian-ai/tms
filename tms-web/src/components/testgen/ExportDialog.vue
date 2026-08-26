<template>
  <el-dialog
    :model-value="modelValue"
    title="导出用例"
    width="640px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="close"
    @open="onOpen"
  >
    <div class="export-body">
      <div class="section-title">选择用例</div>
      <CaseSelectTree v-model="selectedIds" :tree-data="treeData" />

      <div class="section-title">导出格式</div>
      <el-checkbox-group v-model="formats">
        <el-checkbox value="excel" label="excel">Excel (.xlsx)</el-checkbox>
        <el-checkbox value="xmind" label="xmind">XMind (.xmind)</el-checkbox>
      </el-checkbox-group>
    </div>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="exporting" @click="doExport">导出</el-button>
    </template>
  </el-dialog>
</template>

<script>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { testgenApi } from '@/api/testgen'
import CaseSelectTree from '@/components/testgen/CaseSelectTree.vue'

export default {
  name: 'ExportDialog',
  components: { CaseSelectTree },
  props: {
    modelValue: { type: Boolean, default: false },
    treeData: { type: Object, default: null },
    taskId: { type: [String, Number], required: true }
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const selectedIds = ref([])
    const formats = ref(['excel'])
    const exporting = ref(false)

    function onOpen() {
      selectedIds.value = []
      formats.value = ['excel']
    }

    // 剪枝：保留 root、通往选中用例的 module、以及选中的 case（含 step 子树），保证目录层级完整
    function pruneNode(node, idSet) {
      if (!node) return null
      const type = node.type
      if (type === 'case') {
        return idSet.has(node.id) ? JSON.parse(JSON.stringify(node)) : null
      }
      if (type === 'step' || type === 'free') return null
      const children = []
      if (Array.isArray(node.children)) {
        for (const c of node.children) {
          const kept = pruneNode(c, idSet)
          if (kept) children.push(kept)
        }
      }
      if (type === 'module' && children.length === 0) return null
      return { ...node, children }
    }

    function safeBase() {
      const t = (props.treeData && props.treeData.title) ? props.treeData.title : ('export_' + props.taskId)
      return String(t).replace(/[\\/:*?"<>|]/g, '_').trim() || ('export_' + props.taskId)
    }

    function download(blob, name) {
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = name
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      setTimeout(() => URL.revokeObjectURL(url), 2000)
    }

    function close() {
      emit('update:modelValue', false)
    }

    async function doExport() {
      const ids = new Set(selectedIds.value)
      if (ids.size === 0) { ElMessage.warning('请至少选择一个用例'); return }
      if (formats.value.length === 0) { ElMessage.warning('请至少选择一种导出格式'); return }
      const pruned = pruneNode(props.treeData, ids)
      if (!pruned) { ElMessage.warning('所选用例无法导出'); return }

      exporting.value = true
      try {
        const base = safeBase()
        for (const fmt of formats.value) {
          const blob = await testgenApi.exportTemp(props.taskId, fmt, pruned)
          const ext = fmt === 'excel' ? '.xlsx' : '.xmind'
          download(blob, base + ext)
        }
        ElMessage.success('导出完成，已开始下载')
        close()
      } catch (e) {
        console.error(e)
        ElMessage.error('导出失败，请重试')
      } finally {
        exporting.value = false
      }
    }

    return { selectedIds, formats, exporting, onOpen, doExport, close }
  }
}
</script>

<style scoped>
.export-body { display: flex; flex-direction: column; gap: 10px; }
.section-title { font-size: 14px; font-weight: 600; color: #303133; }
</style>
