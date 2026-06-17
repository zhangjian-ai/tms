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
                <el-option v-for="c in commitsA" :key="c.hash" :label="commitLabel(c)" :value="c.hash">
                  <span style="font-family: monospace">{{ c.shortHash }}</span>
                  <el-tag v-if="c.tag" size="small" type="success" effect="plain" style="margin-left: 6px">{{ c.tag }}</el-tag>
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
                <el-option v-for="c in commitsB" :key="c.hash" :label="commitLabel(c)" :value="c.hash">
                  <span style="font-family: monospace">{{ c.shortHash }}</span>
                  <el-tag v-if="c.tag" size="small" type="success" effect="plain" style="margin-left: 6px">{{ c.tag }}</el-tag>
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
      <div class="hint">仅展示差异概览，完整明细请点击上方「下载报告」查看。</div>

      <!-- 三维度差异概览 -->
      <el-table :data="dimensions" size="small" border class="dim-table">
        <el-table-column prop="name" label="对比维度" width="200" />
        <el-table-column label="结果" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="row.diff ? 'warning' : 'success'" size="small">{{ row.diff ? '有差异' : '一致' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="差异概况">
          <template #default="{ row }">{{ row.detail || '—' }}</template>
        </el-table-column>
      </el-table>

      <!-- 文件清单变化:目标侧缺少/多出/内容不同的文件 -->
      <div v-if="!fileEmpty" class="file-changes">
        <div v-if="result.fileCompare.missingInTarget.length" class="fc-group">
          <div class="fc-label del">− 目标侧缺少的文件（{{ result.fileCompare.missingInTarget.length }}）</div>
          <div v-for="e in result.fileCompare.missingInTarget" :key="'fm' + e.path" class="fc-item">
            <span class="fname">{{ fileName(e.path) }}</span>
            <span class="ctx">目录：{{ e.dir || '(根目录)' }}</span>
          </div>
        </div>
        <div v-if="result.fileCompare.extraInTarget.length" class="fc-group">
          <div class="fc-label add">＋ 目标侧多出的文件（{{ result.fileCompare.extraInTarget.length }}）</div>
          <div v-for="e in result.fileCompare.extraInTarget" :key="'fe' + e.path" class="fc-item">
            <span class="fname">{{ fileName(e.path) }}</span>
            <span class="ctx">目录：{{ e.dir || '(根目录)' }}</span>
          </div>
        </div>
        <div v-if="result.fileCompare.contentChanged.length" class="fc-group">
          <div class="fc-label chg">≠ 内容不同的文件（非Excel，按md5对比，{{ result.fileCompare.contentChanged.length }}）</div>
          <div v-for="e in result.fileCompare.contentChanged" :key="'fc' + e.path" class="fc-item">
            <span class="fname">{{ fileName(e.path) }}</span>
            <span class="ctx">目录：{{ e.dir || '(根目录)' }}</span>
          </div>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script>
import { ref, reactive, onMounted, onUnmounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { confCompareApi, confProjectApi } from '@/api/confdiff'

export default {
  name: 'ConfigCompare',
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
    const history = ref([])
    const formRef = ref(null)

    const setResult = (data) => {
      result.value = data
    }

    const fileName = (p) => {
      if (!p) return ''
      const i = p.lastIndexOf('/')
      return i < 0 ? p : p.slice(i + 1)
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

    // commit 下拉选中后的显示文案,带 tag
    const commitLabel = (c) => {
      const tag = c.tag ? `[${c.tag}] ` : ''
      return `${c.shortHash} · ${tag}${c.message}`
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

    // 三维度差异概览
    const dimensions = computed(() => {
      const r = result.value
      if (!r) return []
      const d = r.dirCompare || {}
      const f = r.fileCompare || {}
      const c = r.contentCompare || {}
      const n = (x) => (x || []).length
      return [
        {
          name: '目录对比',
          diff: !dirEmpty.value,
          detail: dirEmpty.value ? '' : `缺少 ${n(d.missingInTarget)} · 多出 ${n(d.extraInTarget)}`
        },
        {
          name: '文件对比',
          diff: !fileEmpty.value,
          detail: fileEmpty.value ? '' : `缺少 ${n(f.missingInTarget)} · 多出 ${n(f.extraInTarget)} · 内容不同 ${n(f.contentChanged)}`
        },
        {
          name: '文件内容对比（Excel 数据行）',
          diff: !contentEmpty.value,
          detail: contentEmpty.value ? '' : `${n(c.files)} 个文件存在行差异`
        }
      ]
    })

    onMounted(loadProjects)
    onUnmounted(() => { stopPoll(); stopResultPoll() })

    return {
      projects, branches, commitsA, commitsB,
      loadingBranches, loadingCommitsA, loadingCommitsB,
      running, result, history, formRef, form, rules,
      prepare, ready, doPrepare, commitLabel, fileName, dimensions,
      loadProjects, onProjectChange, loadBranches, onBranchChange,
      loadHistory, viewHistory,
      run, openReport, dirEmpty, fileEmpty, contentEmpty
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

.dim-table { margin-bottom: 16px; }
.file-changes { margin-top: 4px; }
.fc-group { margin-bottom: 12px; }
.fc-label { font-weight: 600; font-size: 13px; margin-bottom: 4px; }
.fc-label.del { color: #e6a23c; }
.fc-label.add { color: #67c23a; }
.fc-label.chg { color: #f56c6c; }
.fc-item { display: flex; gap: 12px; align-items: baseline; padding: 2px 0 2px 14px; font-size: 13px; }
.fc-item .fname { color: #303133; font-family: monospace; }
.fc-item .ctx { color: #909399; font-size: 12px; }

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
:deep(.row-table .old) { color: #f56c6c; text-decoration: line-through; }
:deep(.row-table .new) { color: #67c23a; }
:deep(.row-table .arrow) { color: #909399; }
</style>
