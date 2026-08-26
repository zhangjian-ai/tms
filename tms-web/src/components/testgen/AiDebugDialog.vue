<template>
  <el-dialog
    :model-value="modelValue"
    title="AI 生成 / 调整用例"
    width="860px"
    top="6vh"
    :close-on-click-modal="false"
    :close-on-press-escape="!proposing && !applying"
    :show-close="!proposing && !applying"
    destroy-on-close
    @update:model-value="close"
    @open="onOpen"
  >
    <!-- 输入阶段 -->
    <div v-if="stage === 'input'" class="ai-body" v-loading="proposing" element-loading-text="AI 正在处理任务，请不要关闭当前页面...">
      <div class="row">
        <span class="label">历史记录</span>
        <el-select
          v-model="historyPick"
          size="small"
          clearable
          placeholder="选择一条历史提示词回填"
          class="history-select"
          @change="onPickHistory"
        >
          <el-option
            v-for="(h, i) in history"
            :key="i"
            :value="i"
            :label="(h.time || '') + '  ' + shorten(h.userPrompt)"
          />
        </el-select>
      </div>

      <div class="field">
        <div class="label">用户提示词</div>
        <div class="var-bar">
          <span class="var-bar-label">点击插入变量：</span>
          <el-tag
            v-for="v in vars"
            :key="v.token"
            size="small"
            effect="plain"
            class="var-tag"
            :title="v.desc"
            @click="insertVar(v.token)"
          >{{ v.token }}</el-tag>
          <span class="var-bar-tip">（{{ vOutline }}、{{ vCases }} 取下方所选参考材料；未选则为空）</span>
        </div>
        <div class="var-bar">
          <span class="var-bar-tip">用自然语言描述你的诉求，AI 会据此对用例进行「新增 / 修改 / 删除」</span>
        </div>
        <el-input
          ref="userPromptRef"
          v-model="userPrompt"
          type="textarea"
          :autosize="{ minRows: 6, maxRows: 14 }"
        />
      </div>

      <div class="field">
        <div class="label">参考材料</div>
        <div class="material-row">
          <span class="material-name">大纲摘要</span>
          <span class="material-count">已选 {{ selectedChapterIds.length }} / {{ chapters.length }} 条</span>
          <el-button size="small" @click="outlinePickerVisible = true">选择大纲…</el-button>
          <span class="material-hint">拼入 <code>{{ vOutline }}</code></span>
        </div>
        <div class="material-row">
          <span class="material-name">用例</span>
          <span class="material-count">已选 {{ selectedCaseIds.length }} 条</span>
          <el-button size="small" @click="casePickerVisible = true">选择用例…</el-button>
          <span class="material-hint">拼入 <code>{{ vCases }}</code></span>
        </div>
      </div>
    </div>

    <!-- 结果阶段 -->
    <div v-else class="ai-body" v-loading="applying" element-loading-text="正在应用变更...">
      <el-alert
        :title="`本轮结果：将删除 ${result.deletes.length} 条，修改 ${result.updates.length} 条，新增 ${result.additions.length} 条`"
        type="info"
        :closable="false"
        show-icon
      />

      <div class="field" v-if="result.deletes.length">
        <div class="label danger">删除（{{ result.deletes.length }}）</div>
        <ul class="case-list">
          <li v-for="d in result.deletes" :key="d.id">{{ d['用例名称'] }}</li>
        </ul>
      </div>

      <div class="field" v-if="result.updates.length">
        <div class="label warning">修改（{{ result.updates.length }}，保留原目录）</div>
        <ul class="case-list">
          <li v-for="u in result.updates" :key="u.id">
            <el-tag v-if="u['优先级']" size="small" effect="plain">{{ u['优先级'] }}</el-tag>
            {{ u['用例名称'] }}
          </li>
        </ul>
      </div>

      <div class="field" v-if="result.additions.length">
        <div class="label success">新增（{{ result.additions.length }}）—— 请为每条选择挂载目录</div>
        <div class="add-toolbar">
          <span class="add-toolbar-label">批量设置目录</span>
          <el-tree-select
            v-model="batchTarget"
            :data="dirTree"
            :props="{ label: 'label', children: 'children' }"
            node-key="id"
            check-strictly
            :render-after-expand="false"
            size="small"
            clearable
            placeholder="选择一个目录"
            class="dir-select"
          />
          <el-button size="small" :disabled="!batchTarget" @click="applyBatchTarget">应用到全部</el-button>
          <el-button size="small" type="primary" plain @click="openNewDir">新建目录…</el-button>
        </div>
        <div class="add-list">
          <div v-for="a in result.additions" :key="a.tempId" class="add-row">
            <div class="add-name">
              <el-tag v-if="a['优先级']" size="small" effect="plain">{{ a['优先级'] }}</el-tag>
              {{ a['用例名称'] }}
            </div>
            <el-tree-select
              v-model="additionTargets[a.tempId]"
              :data="dirTree"
              :props="{ label: 'label', children: 'children' }"
              node-key="id"
              check-strictly
              :render-after-expand="false"
              size="small"
              clearable
              placeholder="默认：AI调试新增目录"
              class="dir-select"
            />
          </div>
        </div>
      </div>

      <div v-if="!result.deletes.length && !result.updates.length && !result.additions.length" class="empty-tip">
        本轮模型未提出任何变更。
      </div>
    </div>

    <template #footer>
      <template v-if="stage === 'input'">
        <el-button @click="close" :disabled="proposing">取消</el-button>
        <el-button type="primary" :loading="proposing" @click="doPropose">开始</el-button>
      </template>
      <template v-else>
        <el-button @click="stage = 'input'" :disabled="applying">返回</el-button>
        <el-button
          type="primary"
          :loading="applying"
          :disabled="!hasChanges"
          @click="doApply"
        >应用</el-button>
      </template>
    </template>
  </el-dialog>

  <!-- 二级：选择大纲摘要 -->
  <el-dialog
    v-model="outlinePickerVisible"
    title="选择大纲摘要"
    width="640px"
    top="8vh"
    append-to-body
    destroy-on-close
  >
    <div class="picker-toolbar">
      <el-checkbox
        :model-value="outlineAllChecked"
        :indeterminate="outlineIndeterminate"
        :disabled="!chapters.length"
        @change="toggleAllOutline"
      >全选</el-checkbox>
      <span class="picker-count">已选 {{ selectedChapterIds.length }} / {{ chapters.length }}</span>
    </div>
    <div class="outline-box-lg">
      <el-checkbox-group v-model="selectedChapterIds" class="outline-cards">
        <el-checkbox
          v-for="c in chapters"
          :key="c.id"
          :value="c.id"
          class="outline-card"
        >
          <div class="oc-body">
            <div class="oc-name">{{ c.name }}</div>
            <div class="oc-scope" v-if="c.scope">{{ c.scope }}</div>
          </div>
        </el-checkbox>
      </el-checkbox-group>
      <div v-if="!chapters.length" class="empty-tip">暂无大纲</div>
    </div>
    <template #footer>
      <el-button type="primary" @click="outlinePickerVisible = false">确定</el-button>
    </template>
  </el-dialog>

  <!-- 二级：选择用例 -->
  <el-dialog
    v-model="casePickerVisible"
    title="选择用例"
    width="760px"
    top="8vh"
    append-to-body
    destroy-on-close
  >
    <div class="picker-toolbar">
      <el-button
        size="small"
        :disabled="!selectedCaseIds.length"
        @click="clearCases"
      >清空已选</el-button>
      <span class="picker-count">已选 {{ selectedCaseIds.length }} 条</span>
    </div>
    <CaseSelectTree ref="caseTreeRef" v-model="selectedCaseIds" :tree-data="treeData" />
    <template #footer>
      <el-button type="primary" @click="casePickerVisible = false">确定</el-button>
    </template>
  </el-dialog>

  <!-- 二级：新建目录（就地新建，应用时随用例一起落库） -->
  <el-dialog
    v-model="newDirVisible"
    title="新建目录"
    width="480px"
    top="12vh"
    append-to-body
    destroy-on-close
  >
    <div class="field">
      <div class="label">上级目录</div>
      <el-tree-select
        v-model="newDirParent"
        :data="dirTree"
        :props="{ label: 'label', children: 'children' }"
        node-key="id"
        check-strictly
        :render-after-expand="false"
        clearable
        placeholder="默认：挂到根目录下"
        style="width: 100%"
      />
    </div>
    <div class="field">
      <div class="label">目录名称</div>
      <el-input v-model="newDirName" placeholder="请输入目录名称" maxlength="50" show-word-limit />
    </div>
    <template #footer>
      <el-button @click="newDirVisible = false">取消</el-button>
      <el-button type="primary" @click="confirmNewDir">创建</el-button>
    </template>
  </el-dialog>
</template>

<script>
import { ref, reactive, computed, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { testgenApi } from '@/api/testgen'
import CaseSelectTree from '@/components/testgen/CaseSelectTree.vue'

export default {
  name: 'AiDebugDialog',
  components: { CaseSelectTree },
  props: {
    modelValue: { type: Boolean, default: false },
    treeData: { type: Object, default: null },
    taskId: { type: [String, Number], required: true }
  },
  emits: ['update:modelValue'],
  setup(props, { emit }) {
    const stage = ref('input')          // 'input' | 'result'
    const proposing = ref(false)
    const applying = ref(false)

    // 可引用变量字面量（避免在模板里直接写 {{}} 触发编译器插值）
    const vDoc = '{{需求文档}}'
    const vOutline = '{{大纲}}'
    const vCases = '{{用例}}'
    const vars = [
      { token: vDoc, desc: '汇总后的全量需求文档' },
      { token: vOutline, desc: '下方勾选的大纲条目（拼为 markdown）' },
      { token: vCases, desc: '下方勾选的用例（拼为 markdown 表格）' }
    ]
    // 默认提示词模板（打开对话框时预填进输入框，用户在此基础上直接修改）
    const defaultPrompt =
      '\n' +
      '【目标】本轮想达成什么，例如：去除重复用例、按新需求补齐用例、补充异常与边界场景\n' +
      '【范围】聚焦哪些用例或模块（可配合下方勾选「大纲 / 用例」缩小范围）\n' +
      '【要求】具体规则，例如：\n' +
      '  1. 合并语义重复的用例，保留更完整的一条\n' +
      '  2. 每条用例需给出可校验的预期结果\n' +
      '  3. 新增用例优先级不低于 P2\n\n' +
      '【需求文档】\n' + '  ' + vDoc + '\n\n' +
      '【大纲摘要】\n' + '  ' + vOutline + '\n\n' +
      '【测试用例】\n' + '  ' + vCases + '\n\n'
    const userPromptRef = ref(null)

    // 点击变量：优先插入到用户提示词光标处，否则追加到末尾
    function insertVar(token) {
      const cur = userPrompt.value || ''
      const ta = userPromptRef.value && userPromptRef.value.textarea
      if (ta && typeof ta.selectionStart === 'number') {
        const s = ta.selectionStart
        const e = ta.selectionEnd
        userPrompt.value = cur.slice(0, s) + token + cur.slice(e)
        nextTick(() => {
          ta.focus()
          const pos = s + token.length
          ta.setSelectionRange(pos, pos)
        })
      } else {
        userPrompt.value = cur ? cur + token : token
      }
    }

    const userPrompt = ref('')
    const selectedChapterIds = ref([])
    const selectedCaseIds = ref([])
    const chapters = ref([])
    const history = ref([])
    const historyPick = ref(null)

    // 二级对话框可见性
    const outlinePickerVisible = ref(false)
    const casePickerVisible = ref(false)
    const caseTreeRef = ref(null)

    // 清空用例已选（联动 CaseSelectTree 内部勾选状态）
    function clearCases() {
      if (caseTreeRef.value && caseTreeRef.value.clearAll) caseTreeRef.value.clearAll()
      else selectedCaseIds.value = []
    }

    // 大纲全选（作用于全部大纲条目）
    const outlineAllChecked = computed(() =>
      chapters.value.length > 0 && selectedChapterIds.value.length === chapters.value.length
    )
    const outlineIndeterminate = computed(() =>
      selectedChapterIds.value.length > 0 && selectedChapterIds.value.length < chapters.value.length
    )
    function toggleAllOutline(val) {
      selectedChapterIds.value = val ? chapters.value.map(c => c.id) : []
    }

    const result = reactive({ deletes: [], updates: [], additions: [] })
    const additionTargets = reactive({})   // tempId -> targetNodeId
    const dirTree = ref([])

    // 批量设置目录 & 就地新建目录
    const batchTarget = ref('')
    const newDirVisible = ref(false)
    const newDirParent = ref('')
    const newDirName = ref('')
    const newDirs = ref([])                 // [{ tempId, parentId, name }]
    let newDirSeq = 0

    // 批量把所选目录应用到全部新增用例
    function applyBatchTarget() {
      if (!batchTarget.value) return
      result.additions.forEach(a => { additionTargets[a.tempId] = batchTarget.value })
    }

    // 在目录树里按 id 查找节点（含新建目录）
    function findDirNode(nodes, id) {
      for (const n of nodes) {
        if (n.id === id) return n
        if (n.children && n.children.length) {
          const f = findDirNode(n.children, id)
          if (f) return f
        }
      }
      return null
    }

    function openNewDir() {
      newDirParent.value = ''
      newDirName.value = ''
      newDirVisible.value = true
    }

    // 就地新建目录：记入 newDirs（apply 时随用例一起落库），并即时插入本地目录树供选择
    function confirmNewDir() {
      const name = (newDirName.value || '').trim()
      if (!name) { ElMessage.warning('请输入目录名称'); return }
      const tempId = 'newdir_' + (++newDirSeq)
      const parentId = newDirParent.value || null
      newDirs.value.push({ tempId, parentId, name })
      const node = { id: tempId, label: name + '（新）', children: [], isNew: true }
      let parentNode = null
      if (parentId) parentNode = findDirNode(dirTree.value, parentId)
      if (!parentNode) parentNode = dirTree.value[0]   // 无上级或未找到 → 挂到 root 节点下
      if (parentNode) {
        if (!parentNode.children) parentNode.children = []
        parentNode.children.push(node)
      } else {
        dirTree.value.push(node)   // 极端兜底：无 root 时作为顶层
      }
      newDirVisible.value = false
      ElMessage.success('目录已创建，应用时生效')
    }

    const hasChanges = computed(() =>
      result.deletes.length || result.updates.length || result.additions.length
    )

    function shorten(s) {
      const t = (s || '').replace(/\s+/g, ' ').trim()
      return t.length > 24 ? t.slice(0, 24) + '…' : t
    }

    // 目录树（root + module），供新增用例选择挂载位置
    function buildDirTree(node) {
      if (!node) return null
      const type = node.type
      if (type !== 'root' && type !== 'module') return null
      const children = []
      if (Array.isArray(node.children)) {
        for (const c of node.children) {
          const d = buildDirTree(c)
          if (d) children.push(d)
        }
      }
      return { id: node.id, label: node.title || '(未命名目录)', children }
    }

    async function onOpen() {
      stage.value = 'input'
      userPrompt.value = defaultPrompt
      selectedChapterIds.value = []
      selectedCaseIds.value = []
      historyPick.value = null
      outlinePickerVisible.value = false
      casePickerVisible.value = false
      result.deletes = []; result.updates = []; result.additions = []
      Object.keys(additionTargets).forEach(k => delete additionTargets[k])
      batchTarget.value = ''
      newDirVisible.value = false
      newDirParent.value = ''
      newDirName.value = ''
      newDirs.value = []
      newDirSeq = 0
      const d = buildDirTree(props.treeData)
      dirTree.value = d ? [d] : []
      // 拉取大纲 + 历史
      try {
        const res = await testgenApi.getOutline(props.taskId)
        chapters.value = (res.code === 0 && res.data && res.data.chapters) ? res.data.chapters : []
      } catch (e) { chapters.value = [] }
      try {
        const res = await testgenApi.getAiDebugHistory(props.taskId)
        history.value = (res.code === 0 && Array.isArray(res.data)) ? res.data : []
      } catch (e) { history.value = [] }
    }

    function onPickHistory(idx) {
      if (idx === '' || idx == null) return
      const h = history.value[idx]
      if (!h) return
      userPrompt.value = h.userPrompt || ''
    }

    async function doPropose() {
      const text = userPrompt.value || ''
      // 校验1：用户提示词必填，且不能全为空白/无效字符
      const meaningful = text.replace(/[\s\u200b-\u200f\u2028\u2029\ufeff]/g, '')
      if (!meaningful) {
        ElMessage.warning('请填写用户提示词，不能为空或全为空白字符')
        return
      }
      // 校验2：至少包含一个动态变量，否则后端无上下文可注入，不提交
      const hasVar = vars.some(v => text.includes(v.token))
      if (!hasVar) {
        ElMessage.warning(`提示词中至少需包含一个动态变量：${vars.map(v => v.token).join('、')}（点击上方标签插入）`)
        return
      }
      proposing.value = true
      try {
        const res = await testgenApi.aiDebugPropose(props.taskId, {
          userPrompt: userPrompt.value,
          chapterIds: selectedChapterIds.value,
          caseIds: selectedCaseIds.value
        })
        if (res.code !== 0 || !res.data) {
          ElMessage.error(res.message || 'AI 处理失败')
          return
        }
        result.deletes = res.data.deletes || []
        result.updates = res.data.updates || []
        result.additions = res.data.additions || []
        // 新增用例目标目录默认空（后端兜底到「AI调试新增」）
        Object.keys(additionTargets).forEach(k => delete additionTargets[k])
        result.additions.forEach(a => { additionTargets[a.tempId] = '' })
        stage.value = 'result'
        // 刷新历史（本次已记录）
        try {
          const hr = await testgenApi.getAiDebugHistory(props.taskId)
          history.value = (hr.code === 0 && Array.isArray(hr.data)) ? hr.data : history.value
        } catch (e) { /* 忽略 */ }
      } catch (e) {
        console.error(e)
        ElMessage.error('AI 处理失败，请重试')
      } finally {
        proposing.value = false
      }
    }

    function toCaseData(o) {
      return {
        用例名称: o['用例名称'],
        优先级: o['优先级'],
        前置条件: o['前置条件'],
        测试步骤: o['测试步骤'] || []
      }
    }

    async function doApply() {
      if (!hasChanges.value) { ElMessage.warning('没有可应用的变更'); return }
      applying.value = true
      let ok = false
      try {
        const body = {
          deletes: result.deletes.map(d => d.id),
          updates: result.updates.map(u => ({ id: u.id, ...toCaseData(u) })),
          additions: result.additions.map(a => ({
            targetNodeId: additionTargets[a.tempId] || null,
            caseData: toCaseData(a)
          })),
          newDirs: newDirs.value.map(d => ({ tempId: d.tempId, parentId: d.parentId, name: d.name }))
        }
        const res = await testgenApi.aiDebugApply(props.taskId, body)
        if (res.code !== 0) {
          ElMessage.error(res.message || '应用失败')
          return
        }
        // 树的刷新统一由后端 WS TREE_UPDATED 推送驱动（保证与其它在线会话一致、单一数据源）
        ok = true
        ElMessage.success('已应用 AI 结果')
      } catch (e) {
        console.error(e)
        ElMessage.error('应用失败，请重试')
      } finally {
        applying.value = false
      }
      // 仅在应用成功后主动关闭对话框（此时 applying 已置回 false，close 才不会被拦截）；失败则保留对话框
      if (ok) close()
    }

    function close() {
      // AI 处理 / 应用变更进行中，禁止关闭（拦住 x、ESC、取消按钮、遮罩点击等一切路径）
      if (proposing.value || applying.value) return
      emit('update:modelValue', false)
    }

    return {
      stage, proposing, applying,
      vDoc, vOutline, vCases, vars, userPromptRef, insertVar,
      userPrompt, selectedChapterIds, selectedCaseIds,
      outlinePickerVisible, casePickerVisible, caseTreeRef, clearCases,
      outlineAllChecked, outlineIndeterminate, toggleAllOutline,
      chapters, history, historyPick, result, additionTargets, dirTree, hasChanges,
      batchTarget, applyBatchTarget,
      newDirVisible, newDirParent, newDirName, openNewDir, confirmNewDir,
      shorten, onOpen, onPickHistory, doPropose, doApply, close
    }
  }
}
</script>

<style scoped>
.ai-body { display: flex; flex-direction: column; gap: 14px; max-height: 70vh; overflow-y: auto; padding-right: 4px; }
.row { display: flex; align-items: center; gap: 10px; }
.history-select { width: 420px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.label { font-size: 14px; font-weight: 600; color: #303133; }
.label.danger { color: #f56c6c; }
.label.warning { color: #e6a23c; }
.label.success { color: #67c23a; }
.hint { font-weight: 400; font-size: 12px; color: #909399; margin-left: 8px; }
.hint code, .label code { background: #f5f7fa; padding: 1px 4px; border-radius: 3px; color: #409eff; }
.var-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; }
.var-bar-label { font-size: 12px; color: #606266; }
.var-tag { cursor: pointer; }
.var-tag:hover { opacity: 0.8; }
.var-bar-tip { font-size: 12px; color: #909399; }
.material-row {
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px 12px;
}
.material-name { font-size: 13px; font-weight: 600; color: #303133; min-width: 64px; }
.material-count { font-size: 12px; color: #606266; }
.material-hint { font-size: 12px; color: #909399; margin-left: auto; }
.material-hint code { background: #f5f7fa; padding: 1px 4px; border-radius: 3px; color: #409eff; }
.picker-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.picker-count { font-size: 12px; color: #909399; }
.outline-box-lg {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
  max-height: 56vh;
  overflow: auto;
}
/* 大纲条目卡片式（与「确认大纲」阶段的 module-card 观感一致） */
.outline-cards { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.outline-card {
  display: flex;
  align-items: flex-start;
  width: 100%;
  height: auto;
  margin: 0;
  padding: 10px 12px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  background: #fafbfc;
  white-space: normal;
  transition: border-color .2s, background-color .2s;
}
.outline-card:hover { border-color: #c6e2ff; }
.outline-card.is-checked { border-color: #409eff; background: #ecf5ff; }
.outline-card :deep(.el-checkbox__input) { margin-top: 2px; }
.outline-card :deep(.el-checkbox__label) {
  flex: 1;
  padding-left: 8px;
  line-height: 1.6;
  white-space: normal;
  word-break: break-word;
}
.oc-name { font-weight: 600; color: #303133; font-size: 13px; }
.oc-scope { color: #606266; font-size: 12px; line-height: 1.6; margin-top: 4px; white-space: pre-wrap; }
.case-list { margin: 0; padding-left: 18px; color: #606266; font-size: 13px; }
.case-list li { margin: 3px 0; }
.add-list { display: flex; flex-direction: column; gap: 8px; }
.add-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  margin-bottom: 8px;
  background: #f5f7fa;
  border-radius: 6px;
}
.add-toolbar-label { font-size: 13px; color: #606266; }
.add-row {
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px 10px;
}
.add-name { flex: 1; font-size: 13px; color: #303133; word-break: break-all; }
.dir-select { width: 260px; flex-shrink: 0; }
.empty-tip { color: #909399; font-size: 13px; padding: 12px; text-align: center; }
</style>
