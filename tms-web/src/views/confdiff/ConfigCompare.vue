<template>
  <div class="conf-compare">
    <el-card shadow="never" class="form-card">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="90px">
        <el-form-item label="项目" prop="projectId">
          <el-select v-model="form.projectId" placeholder="选择项目" filterable style="width: 360px" @change="onProjectChange">
            <el-option v-for="p in projects" :key="p.id" :label="`${p.name} (${p.machineName || '机器#' + p.machineId})`" :value="p.id" />
          </el-select>
          <el-button style="margin-left: 8px" :loading="loadingBranches" @click="loadBranches" :disabled="!ready">刷新分支</el-button>
        </el-form-item>

        <!-- 项目准备状态 -->
        <el-alert v-if="form.projectId && prepare.status === 'PREPARING'" type="info" :closable="false" show-icon style="margin-bottom: 12px">
          <template #title>
            <span v-loading="true" class="preparing-text">项目首次初始化（克隆）中，可能耗时较久，请稍候…</span>
          </template>
        </el-alert>
        <el-alert v-else-if="form.projectId && prepare.status === 'NOT_PREPARED'" type="warning" :closable="false" show-icon style="margin-bottom: 12px">
          <template #title>
            <span>项目尚未在物理机上初始化。{{ prepare.message }}</span>
            <el-button type="primary" size="small" style="margin-left: 12px" @click="doPrepare">准备项目（首次克隆）</el-button>
          </template>
        </el-alert>
        <el-alert v-else-if="form.projectId && prepare.status === 'FAILED'" type="error" :closable="false" show-icon style="margin-bottom: 12px">
          <template #title>
            <span>准备失败：{{ prepare.message }}</span>
            <el-button type="primary" size="small" style="margin-left: 12px" @click="doPrepare">重试</el-button>
          </template>
        </el-alert>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-divider content-position="left">基准侧 (A)</el-divider>
            <el-form-item label="分支" prop="refA.branch">
              <el-select v-model="form.refA.branch" placeholder="选择分支" filterable style="width: 100%"
                :loading="loadingBranches" :disabled="!ready" @change="onBranchChange('A')">
                <el-option v-for="b in branches" :key="b" :label="b" :value="b" />
              </el-select>
            </el-form-item>
            <el-form-item label="commit">
              <el-select v-model="form.refA.commit" placeholder="留空取分支最新" filterable clearable style="width: 100%"
                :loading="loadingCommitsA">
                <el-option v-for="c in commitsA" :key="c.hash" :label="`${c.shortHash} · ${c.message}`" :value="c.hash">
                  <span style="font-family: monospace">{{ c.shortHash }}</span>
                  <span style="margin-left: 8px; color: #909399">{{ c.message }}</span>
                  <span style="float: right; color: #c0c4cc; font-size: 12px">{{ c.date }}</span>
                </el-option>
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-divider content-position="left">目标侧 (B)</el-divider>
            <el-form-item label="分支" prop="refB.branch">
              <el-select v-model="form.refB.branch" placeholder="选择分支" filterable style="width: 100%"
                :loading="loadingBranches" :disabled="!ready" @change="onBranchChange('B')">
                <el-option v-for="b in branches" :key="b" :label="b" :value="b" />
              </el-select>
            </el-form-item>
            <el-form-item label="commit">
              <el-select v-model="form.refB.commit" placeholder="留空取分支最新" filterable clearable style="width: 100%"
                :loading="loadingCommitsB">
                <el-option v-for="c in commitsB" :key="c.hash" :label="`${c.shortHash} · ${c.message}`" :value="c.hash">
                  <span style="font-family: monospace">{{ c.shortHash }}</span>
                  <span style="margin-left: 8px; color: #909399">{{ c.message }}</span>
                  <span style="float: right; color: #c0c4cc; font-size: 12px">{{ c.date }}</span>
                </el-option>
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item>
          <el-button type="primary" @click="run" :loading="running" :disabled="!ready">开始对比</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 历史对比 -->
    <el-card v-if="form.projectId && history.length" shadow="never" class="history-card">
      <template #header>
        <div class="history-header">
          <span>历史对比（最近 {{ history.length }} 次）</span>
          <el-button link type="primary" @click="loadHistory">刷新</el-button>
        </div>
      </template>
      <el-table :data="history" size="small" @row-click="viewHistory" class="history-table">
        <el-table-column prop="createTime" label="时间" width="160" />
        <el-table-column prop="refA" label="基准侧 A" show-overflow-tooltip />
        <el-table-column prop="refB" label="目标侧 B" show-overflow-tooltip />
        <el-table-column label="结果" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'RUNNING'" type="info" size="small">进行中</el-tag>
            <el-tag v-else-if="row.status === 'FAILED'" type="danger" size="small">失败</el-tag>
            <el-tag v-else :type="row.consistent ? 'success' : 'warning'" size="small">{{ row.consistent ? '一致' : '有差异' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small"
              :disabled="row.status === 'RUNNING' || row.status === 'FAILED' || row.consistent"
              @click.stop="viewHistory(row)">查看报告</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-alert v-if="running && !result" type="info" :closable="false" show-icon class="running-alert">
      <template #title>
        <span v-loading="true">对比执行中，可离开页面，稍后在历史记录查看/打开报告…</span>
      </template>
    </el-alert>

    <el-card v-if="result" shadow="never" class="result-card" v-loading="running">
      <template #header>
        <div class="result-header">
          <span>对比结果</span>
          <el-tag :type="result.consistent ? 'success' : 'danger'" size="large">
            {{ result.consistent ? '完全一致' : '存在差异' }}
          </el-tag>
          <span class="elapsed">耗时 {{ result.elapsedMs }} ms</span>
          <el-button v-if="result.reportUrl" type="primary" link @click="openReport">下载报告</el-button>
        </div>
      </template>

      <el-descriptions :column="2" border size="small" class="meta">
        <el-descriptions-item label="项目" :span="2">{{ result.projectName }}</el-descriptions-item>
        <el-descriptions-item label="基准侧 A">{{ result.refA }}</el-descriptions-item>
        <el-descriptions-item label="实际 commit A">{{ result.resolvedCommitA }}</el-descriptions-item>
        <el-descriptions-item label="目标侧 B">{{ result.refB }}</el-descriptions-item>
        <el-descriptions-item label="实际 commit B">{{ result.resolvedCommitB }}</el-descriptions-item>
      </el-descriptions>
      <div class="hint">以下均以基准侧 A 为基准，描述目标侧 B 相对 A 缺少 / 多出了什么。</div>

      <!-- 模块一:目录对比 -->
      <div class="module">
        <div class="module-title">模块一 · 目录对比</div>
        <div v-if="dirEmpty" class="ok">✓ 目录结构一致</div>
        <template v-else>
          <diff-block label="目标侧缺少的目录" type="missing" :count="result.dirCompare.missingInTarget.length">
            <div v-for="e in result.dirCompare.missingInTarget" :key="'dm' + e.path" class="entry">
              <span class="path">{{ e.path }}</span>
              <span class="ctx">父目录：{{ e.parent || '(根目录)' }}</span>
            </div>
          </diff-block>
          <diff-block label="目标侧多出的目录" type="extra" :count="result.dirCompare.extraInTarget.length">
            <div v-for="e in result.dirCompare.extraInTarget" :key="'de' + e.path" class="entry">
              <span class="path">{{ e.path }}</span>
              <span class="ctx">父目录：{{ e.parent || '(根目录)' }}</span>
            </div>
          </diff-block>
        </template>
      </div>

      <!-- 模块二:文件对比 -->
      <div class="module">
        <div class="module-title">模块二 · 文件对比</div>
        <div v-if="fileEmpty" class="ok">✓ 文件清单一致</div>
        <template v-else>
          <diff-block label="目标侧缺少的文件" type="missing" :count="result.fileCompare.missingInTarget.length">
            <div v-for="e in result.fileCompare.missingInTarget" :key="'fm' + e.path" class="entry">
              <span class="path">{{ e.path }}</span>
              <span class="ctx">所在目录：{{ e.dir || '(根目录)' }}</span>
            </div>
          </diff-block>
          <diff-block label="目标侧多出的文件" type="extra" :count="result.fileCompare.extraInTarget.length">
            <div v-for="e in result.fileCompare.extraInTarget" :key="'fe' + e.path" class="entry">
              <span class="path">{{ e.path }}</span>
              <span class="ctx">所在目录：{{ e.dir || '(根目录)' }}</span>
            </div>
          </diff-block>
          <diff-block label="内容不同的文件(非Excel,按md5对比)" type="changed" :count="result.fileCompare.contentChanged.length">
            <div v-for="e in result.fileCompare.contentChanged" :key="'fc' + e.path" class="entry entry-md5">
              <span class="path">{{ e.path }}</span>
              <span class="ctx">所在目录：{{ e.dir || '(根目录)' }}</span>
              <span class="md5" v-if="e.md5A || e.md5B">A: {{ e.md5A }} → B: {{ e.md5B }}</span>
            </div>
          </diff-block>
        </template>
      </div>

      <!-- 模块三:文件内容对比 -->
      <div class="module">
        <div class="module-title">模块三 · 文件内容对比（Excel 数据行）</div>
        <div v-if="contentEmpty" class="ok">✓ 文件内容一致</div>
        <el-collapse v-else v-model="activeContentFiles" class="content-collapse">
          <el-collapse-item v-for="fd in result.contentCompare.files" :key="fd.path" :name="fd.path">
            <template #title>
              <span class="cf-title">📄 {{ fd.path }}</span>
              <span class="cf-sum">{{ fileSummary(fd) }}</span>
            </template>
            <div v-if="fd.sheetsMissingInTarget.length" class="sheet-line">
              <el-tag size="small" type="warning">缺少 sheet</el-tag>
              <span>{{ fd.sheetsMissingInTarget.join('、') }}</span>
            </div>
            <div v-if="fd.sheetsExtraInTarget.length" class="sheet-line">
              <el-tag size="small" type="success">多出 sheet</el-tag>
              <span>{{ fd.sheetsExtraInTarget.join('、') }}</span>
            </div>
            <div v-for="sd in fd.sheets" :key="fd.path + '#' + sd.sheet" class="sheet-block">
              <div class="sheet-name">sheet：{{ sd.sheet }}</div>
              <diff-block label="目标侧缺少的数据行" type="missing" :count="sd.rowsMissingInTarget.length">
                <row-table :rows="sd.rowsMissingInTarget" :peer="sd.rowsExtraInTarget" :headers="sd.headerA" kind="missing" />
              </diff-block>
              <diff-block label="目标侧多出的数据行" type="extra" :count="sd.rowsExtraInTarget.length">
                <row-table :rows="sd.rowsExtraInTarget" :peer="sd.rowsMissingInTarget" :headers="sd.headerB" kind="extra" />
              </diff-block>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-card>
  </div>
</template>

<script>
import { ref, reactive, onMounted, onUnmounted, computed, h } from 'vue'
import { ElMessage } from 'element-plus'
import { confCompareApi, confProjectApi } from '@/api/confdiff'

// 后端整行单元格分隔符(0x01)
const CELL_SEP = String.fromCharCode(1)

// 差异块子组件:有内容才展示,标题带数量与方向符号
const DiffBlock = {
  name: 'DiffBlock',
  props: {
    label: String,
    count: { type: Number, default: 0 },
    type: { type: String, default: 'missing' } // missing | extra | changed
  },
  setup(props, { slots }) {
    const colorMap = { missing: '#e6a23c', extra: '#67c23a', changed: '#f56c6c' }
    const signMap = { missing: '−', extra: '＋', changed: '≠' }
    return () => {
      if (!props.count) return null
      return h('div', { class: 'diff-block' }, [
        h('div', { class: 'diff-block-title', style: { color: colorMap[props.type] } },
          `${signMap[props.type]} ${props.label} (${props.count})`),
        h('div', { class: 'diff-block-body' }, slots.default ? slots.default() : [])
      ])
    }
  }
}

// 数据行表格:首列真实 Excel 行号,表头取该 sheet 首行,并按对侧同行号判定 新增/删除/更新
const RowTable = {
  name: 'RowTable',
  props: {
    rows: { type: Array, default: () => [] },
    peer: { type: Array, default: () => [] },
    headers: { type: Array, default: () => [] },
    kind: { type: String, default: 'missing' } // missing(基准侧有) | extra(目标侧有)
  },
  setup(props) {
    return () => {
      if (!props.rows.length) return null
      const peerNums = new Set((props.peer || []).map((r) => r.rowNum))
      const matrix = props.rows.map((r) => ({
        rowNum: r.rowNum,
        cells: String(r.content != null ? r.content : '').split(CELL_SEP),
        updated: peerNums.has(r.rowNum)
      }))
      const maxCols = matrix.reduce((m, r) => Math.max(m, r.cells.length), (props.headers || []).length)
      const colName = (i) => {
        const hs = props.headers || []
        return i < hs.length && hs[i] ? hs[i] : '列' + (i + 1)
      }
      const labelOf = (u) => (u ? '更新' : props.kind === 'missing' ? '删除' : '新增')
      const clsOf = (u) => (u ? 'st-upd' : props.kind === 'missing' ? 'st-del' : 'st-add')
      const header = h('tr', [
        h('th', '状态'), h('th', '行号'),
        ...Array.from({ length: maxCols }, (_, i) => h('th', colName(i)))
      ])
      const body = matrix.map((r) =>
        h('tr', [
          h('td', { class: 'st ' + clsOf(r.updated) }, labelOf(r.updated)),
          h('td', { class: 'idx' }, r.rowNum),
          ...Array.from({ length: maxCols }, (_, ci) => h('td', r.cells[ci] != null ? r.cells[ci] : ''))
        ])
      )
      return h('table', { class: 'row-table' }, [h('thead', [header]), h('tbody', body)])
    }
  }
}

export default {
  name: 'ConfigCompare',
  components: { DiffBlock, RowTable },
  setup() {
    const projects = ref([])
    const branches = ref([])
    const commitsA = ref([])
    const commitsB = ref([])
    const loadingBranches = ref(false)
    const loadingCommitsA = ref(false)
    const loadingCommitsB = ref(false)
    const running = ref(false)
    const result = ref(null)
    const activeContentFiles = ref([]) // 模块三按文件折叠,默认全部折叠
    const history = ref([])
    const formRef = ref(null)

    // 设置结果并默认折叠所有文件内容块
    const setResult = (data) => {
      result.value = data
      activeContentFiles.value = []
    }

    const fileSummary = (fd) => {
      let miss = 0
      let extra = 0
      ;(fd.sheets || []).forEach((s) => {
        miss += (s.rowsMissingInTarget || []).length
        extra += (s.rowsExtraInTarget || []).length
      })
      const parts = []
      if (fd.sheetsMissingInTarget && fd.sheetsMissingInTarget.length) parts.push(`缺少 sheet ${fd.sheetsMissingInTarget.length}`)
      if (fd.sheetsExtraInTarget && fd.sheetsExtraInTarget.length) parts.push(`多出 sheet ${fd.sheetsExtraInTarget.length}`)
      if (miss) parts.push(`缺少 ${miss} 行`)
      if (extra) parts.push(`多出 ${extra} 行`)
      return parts.join(' · ')
    }
    const prepare = reactive({ status: null, message: '' }) // 项目准备状态
    let pollTimer = null
    let resultTimer = null
    const form = reactive({
      projectId: null,
      refA: { branch: '', commit: '' },
      refB: { branch: '', commit: '' }
    })

    const ready = computed(() => !!form.projectId && prepare.status === 'READY')

    const rules = {
      projectId: [{ required: true, message: '请选择项目', trigger: 'change' }],
      'refA.branch': [{ required: true, message: '请选择基准侧分支', trigger: 'change' }],
      'refB.branch': [{ required: true, message: '请选择目标侧分支', trigger: 'change' }]
    }

    const loadProjects = async () => {
      const res = await confProjectApi.list({ pageNo: 1, pageSize: 500 })
      if (res.code === 0 && res.data) projects.value = res.data.list || []
    }

    const resetRefs = () => {
      branches.value = []
      commitsA.value = []
      commitsB.value = []
      form.refA = { branch: '', commit: '' }
      form.refB = { branch: '', commit: '' }
    }

    const onProjectChange = () => {
      stopPoll()
      stopResultPoll()
      running.value = false
      resetRefs()
      result.value = null
      history.value = []
      prepare.status = null
      prepare.message = ''
      if (form.projectId) {
        checkPrepare()
        loadHistory()
      }
    }

    const loadHistory = async () => {
      if (!form.projectId) return
      try {
        const res = await confCompareApi.history(form.projectId)
        history.value = res.code === 0 ? (res.data || []) : []
      } catch (e) {
        history.value = []
      }
    }

    // 历史结果:直接打开 MinIO 中存储的 HTML 报告(不在本页渲染)
    const viewHistory = async (row) => {
      if (row.status === 'RUNNING') {
        ElMessage.info('该次对比尚在执行中，请稍候')
        return
      }
      if (row.status === 'FAILED') {
        ElMessage.error('该次对比失败：' + (row.message || ''))
        return
      }
      if (row.consistent) {
        ElMessage.info('该次对比结果一致，未生成报告')
        return
      }
      // 先同步打开空标签页,避免异步后被弹窗拦截
      const win = window.open('', '_blank')
      try {
        const res = await confCompareApi.result(row.id)
        const url = res.code === 0 && res.data ? res.data.reportUrl : null
        if (url) {
          if (win) win.location = url
          else window.open(url, '_blank')
        } else {
          if (win) win.close()
          ElMessage.warning(res.msg || '报告不存在或已过期')
        }
      } catch (e) {
        if (win) win.close()
        ElMessage.error('打开报告失败：' + (e?.message || ''))
      }
    }

    const stopPoll = () => {
      if (pollTimer) { clearTimeout(pollTimer); pollTimer = null }
    }

    const stopResultPoll = () => {
      if (resultTimer) { clearTimeout(resultTimer); resultTimer = null }
    }

    const applyPrepare = (data) => {
      prepare.status = data?.status || 'NOT_PREPARED'
      prepare.message = data?.message || ''
      if (prepare.status === 'READY') {
        stopPoll()
        loadBranches()
      } else if (prepare.status === 'PREPARING') {
        pollTimer = setTimeout(checkPrepare, 5000) // 轮询准备进度
      } else {
        stopPoll()
      }
    }

    // 查询准备状态(选项目时调用)
    const checkPrepare = async () => {
      if (!form.projectId) return
      try {
        const res = await confCompareApi.prepareStatus(form.projectId)
        if (res.code === 0) applyPrepare(res.data)
        else { prepare.status = 'NOT_PREPARED'; prepare.message = res.msg || '' }
      } catch (e) {
        prepare.status = 'NOT_PREPARED'
        prepare.message = e?.message || '状态检测失败'
      }
    }

    // 触发首次克隆(后台异步)
    const doPrepare = async () => {
      if (!form.projectId) return
      try {
        const res = await confCompareApi.prepare(form.projectId)
        if (res.code === 0) {
          applyPrepare(res.data)
          ElMessage.info('已开始初始化，正在后台克隆…')
        } else {
          ElMessage.error(res.msg || '触发准备失败')
        }
      } catch (e) {
        ElMessage.error('触发准备失败：' + (e?.message || ''))
      }
    }

    const loadBranches = async () => {
      if (!form.projectId) return
      loadingBranches.value = true
      try {
        const res = await confCompareApi.branches(form.projectId)
        if (res.code === 0) {
          branches.value = res.data || []
        } else {
          ElMessage.error(res.msg || '获取分支失败')
        }
      } catch (e) {
        ElMessage.error('获取分支失败：' + (e?.message || '请检查机器连接'))
      } finally {
        loadingBranches.value = false
      }
    }

    const loadCommits = async (side) => {
      const branch = side === 'A' ? form.refA.branch : form.refB.branch
      const target = side === 'A' ? commitsA : commitsB
      const loading = side === 'A' ? loadingCommitsA : loadingCommitsB
      if (!form.projectId || !branch) { target.value = []; return }
      loading.value = true
      try {
        const res = await confCompareApi.commits(form.projectId, branch, 100)
        target.value = res.code === 0 ? (res.data || []) : []
      } catch (e) {
        target.value = []
      } finally {
        loading.value = false
      }
    }

    const onBranchChange = (side) => {
      if (side === 'A') { form.refA.commit = ''; loadCommits('A') }
      else { form.refB.commit = ''; loadCommits('B') }
    }

    // 触发异步对比:立即返回 id,后台执行,前端轮询结果
    const run = async () => {
      await formRef.value.validate()
      running.value = true
      result.value = null
      try {
        const res = await confCompareApi.run(form)
        if (res.code === 0 && res.data && res.data.id) {
          ElMessage.success('对比已提交，后台执行中…可离开页面，稍后在历史记录查看')
          loadHistory()
          pollResult(res.data.id)
        } else {
          running.value = false
          ElMessage.error(res.msg || '提交对比失败')
        }
      } catch (e) {
        running.value = false
        ElMessage.error('提交对比失败：' + (e?.message || ''))
      }
    }

    // 轮询某次对比结果:完成则内联展示,失败则提示;期间刷新历史状态
    const pollResult = (id) => {
      const tick = async () => {
        try {
          const res = await confCompareApi.result(id)
          if (res.code === 0 && res.data) {
            const st = res.data.status
            loadHistory()
            if (st === 'SUCCESS') {
              running.value = false
              setResult(res.data)
              ElMessage.success(res.data.consistent ? '对比完成：完全一致' : '对比完成：存在差异')
              return
            }
            if (st === 'FAILED') {
              running.value = false
              ElMessage.error('对比失败：' + (res.data.message || ''))
              return
            }
          }
        } catch (e) {
          // 忽略瞬时错误,继续轮询
        }
        resultTimer = setTimeout(tick, 4000)
      }
      tick()
    }

    const openReport = () => {
      if (result.value?.reportUrl) window.open(result.value.reportUrl, '_blank')
    }

    const rowText = (row) => String(row).split(CELL_SEP).join(' | ')

    const dirEmpty = computed(() => {
      const d = result.value?.dirCompare
      return !d || ((d.missingInTarget || []).length === 0 && (d.extraInTarget || []).length === 0)
    })
    const fileEmpty = computed(() => {
      const f = result.value?.fileCompare
      return !f || ((f.missingInTarget || []).length === 0 && (f.extraInTarget || []).length === 0 && (f.contentChanged || []).length === 0)
    })
    const contentEmpty = computed(() => {
      const c = result.value?.contentCompare
      return !c || (c.files || []).length === 0
    })

    onMounted(loadProjects)
    onUnmounted(() => { stopPoll(); stopResultPoll() })

    return {
      projects, branches, commitsA, commitsB,
      loadingBranches, loadingCommitsA, loadingCommitsB,
      running, result, history, activeContentFiles, formRef, form, rules,
      prepare, ready, doPrepare, fileSummary,
      loadProjects, onProjectChange, loadBranches, onBranchChange,
      loadHistory, viewHistory,
      run, openReport, rowText, dirEmpty, fileEmpty, contentEmpty
    }
  }
}
</script>

<style scoped>
.conf-compare { padding: 16px; }
.form-card { margin-bottom: 16px; }
.result-header { display: flex; align-items: center; gap: 12px; }
.result-header .elapsed { color: #909399; font-size: 13px; }
.meta { margin-bottom: 8px; }
.hint { color: #909399; font-size: 13px; margin: 8px 0 16px; }

.module { margin-bottom: 20px; border: 1px solid #ebeef5; border-radius: 6px; overflow: hidden; }
.module-title {
  background: #f5f7fa; padding: 10px 14px; font-weight: 600; color: #303133;
  border-bottom: 1px solid #ebeef5;
}
.module .ok { color: #67c23a; padding: 12px 14px; }
.module > :deep(.diff-block) { padding: 0 14px; }
.content-file { padding: 10px 14px; }

:deep(.diff-block) { margin: 10px 0; }
:deep(.diff-block-title) { font-size: 13px; font-weight: 600; margin-bottom: 4px; }
:deep(.diff-block-body) { padding-left: 14px; }

.entry { display: flex; gap: 12px; align-items: baseline; padding: 2px 0; font-size: 13px; }
.entry .path { color: #303133; font-family: monospace; }
.entry .ctx { color: #909399; font-size: 12px; }
.entry.entry-md5 { flex-wrap: wrap; }
.entry .md5 { width: 100%; color: #c0c4cc; font-family: monospace; font-size: 12px; padding-left: 0; }

.content-file { border-top: 1px dashed #ebeef5; }
.content-file:first-child { border-top: none; }
.content-file-title { font-weight: 600; margin-bottom: 6px; }
.content-collapse { padding: 0 14px; }
.cf-title { font-weight: 600; }
.cf-sum { margin-left: 12px; color: #909399; font-size: 12px; }
.sheet-line { display: flex; align-items: center; gap: 8px; margin: 4px 0; font-size: 13px; color: #606266; }
.sheet-block { padding-left: 12px; border-left: 2px solid #ebeef5; margin: 8px 0; }
.sheet-name { color: #606266; margin: 4px 0; font-weight: 500; }
.row-line {
  font-family: monospace; font-size: 12px; color: #606266;
  padding-left: 12px; white-space: pre-wrap; word-break: break-all;
}

.history-card { margin-bottom: 16px; }
.history-header { display: flex; justify-content: space-between; align-items: center; }
.history-table :deep(.el-table__row) { cursor: pointer; }

:deep(.row-table) {
  border-collapse: collapse;
  margin: 4px 0 8px 12px;
  font-size: 12px;
}
:deep(.row-table th),
:deep(.row-table td) {
  border: 1px solid #ebeef5;
  padding: 3px 8px;
  text-align: left;
  font-family: monospace;
  white-space: pre-wrap;
  word-break: break-all;
  vertical-align: top;
}
:deep(.row-table thead th) { background: #fafafa; color: #909399; font-weight: 600; text-align: center; }
:deep(.row-table td.idx) { color: #909399; text-align: right; width: 48px; }
:deep(.row-table td.st) { width: 44px; text-align: center; font-weight: 600; }
:deep(.row-table td.st-del) { color: #e6a23c; }
:deep(.row-table td.st-add) { color: #67c23a; }
:deep(.row-table td.st-upd) { color: #409eff; }
</style>
