<template>
  <div class="test-gen-list">
    <div class="action-bar">
      <el-button @click="fetchList">查询</el-button>
      <el-button type="primary" @click="openCreateDialog">新建任务</el-button>
    </div>

    <el-table :data="taskList" v-loading="loading" stripe header-cell-class-name="center-header">
      <el-table-column prop="id" label="ID" min-width="80" align="center" />
      <el-table-column prop="prdName" label="需求文档" min-width="380" align="left">
        <template #default="{ row }">
          <div
            v-for="(name, i) in displayDocLines(row)"
            :key="i"
            class="prd-name-line"
            :title="name"
          >{{ name }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="prdSource" label="需求来源" min-width="110" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="row.prdSource === 'LINK' ? 'warning' : 'success'">{{ prdSourceMap[row.prdSource] || row.prdSource }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="creator" label="创建人" min-width="120" align="center" />
      <el-table-column prop="status" label="状态" min-width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTypeMap[row.status]" size="small">{{ statusTextMap[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" min-width="180" align="center" :formatter="createTimeFormatter" />
      <el-table-column label="操作" min-width="240" align="center" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'NEW'"
            size="small"
            type="primary"
            @click="continueGen(row)"
          >
            开始生成
          </el-button>
          <el-button
            v-if="row.status === 'GENERATING' || row.status === 'PLANNING'"
            size="small"
            type="primary"
            @click="continueGen(row)"
          >
            继续生成
          </el-button>
          <el-button
            v-if="row.status === 'PLAN_REVIEW'"
            size="small"
            type="primary"
            @click="continueGen(row)"
          >
            确认大纲
          </el-button>
          <el-button
            v-if="row.status === 'EDITING'"
            size="small"
            type="primary"
            @click="continueGen(row)"
          >
            继续编辑
          </el-button>
          <el-button
            v-if="row.status === 'FINISHED' || row.status === 'FAILED'"
            size="small"
            @click="regenerate(row)"
          >
            重新生成
          </el-button>
          <el-button size="small" :disabled="!row.xmindFileName" @click="downloadXmind(row)">下载</el-button>
          <el-button size="small" type="danger" @click="deleteTask(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        layout="total, sizes, prev, pager, next"
        :total="total"
        :page-size="query.pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :current-page="query.pageNo"
        @size-change="onSizeChange"
        @current-change="onPageChange"
      />
    </div>

    <!-- 新建任务对话框 -->
    <el-dialog v-model="createDialogVisible" title="新建用例生成任务" width="520px" :close-on-click-modal="false" class="create-dialog">
      <el-form :model="createForm" label-width="130px" :rules="formRules" ref="formRef">
        <el-form-item label="需求来源" prop="prdSource">
          <el-radio-group v-model="createForm.prdSource">
            <el-radio-button label="LINK">文档链接</el-radio-button>
            <el-radio-button label="UPLOAD">上传文档</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <!-- 文档链接：单个主文档链接 + 解析二级文档 -->
        <template v-if="createForm.prdSource === 'LINK'">
          <el-form-item label="文档链接" prop="prdName">
            <el-input
              v-model="linkUrl"
              placeholder="粘贴一个飞书文档链接（新版文档 docx / 知识库 wiki / 电子表格 sheets）"
              clearable
            />
            <div class="upload-tip">仅填写一个主文档链接，将以你的飞书授权身份读取内容。</div>
          </el-form-item>
          <el-form-item label="解析二级文档">
            <el-switch v-model="createForm.parseSubDoc" />
            <div class="upload-tip">开启后会提取主文档中直接引用的飞书文档（仅一层），一并抓取并作为关联文档整合。</div>
          </el-form-item>
        </template>

        <!-- 上传文档：一个主文档 + 多个关联文档 -->
        <template v-else>
          <el-form-item label="主文档" prop="prdName">
            <el-upload
              ref="mainUploadRef"
              :action="uploadUrl"
              :headers="uploadHeaders"
              :limit="1"
              :on-success="onMainSuccess"
              :on-error="onUploadError"
              :on-remove="onMainRemove"
              :on-exceed="onMainExceed"
              :before-upload="beforeUpload"
              :accept="acceptExts"
              :show-file-list="true"
            >
              <el-button type="primary" plain size="default">
                <el-icon style="margin-right: 4px;"><UploadFilled /></el-icon>
                选择主文档
              </el-button>
              <template #tip>
                <div class="upload-tip">仅一个主文档。支持 PDF、DOCX、TXT、Markdown、表格(XLSX/XLS/CSV) 或图片，单个文件最大 100MB。</div>
              </template>
            </el-upload>
          </el-form-item>
          <el-form-item label="关联文档">
            <el-upload
              ref="relatedUploadRef"
              :action="uploadUrl"
              :headers="uploadHeaders"
              multiple
              :on-success="onRelatedSuccess"
              :on-error="onUploadError"
              :on-remove="onRelatedRemove"
              :before-upload="beforeUpload"
              :accept="acceptExts"
              :show-file-list="true"
            >
              <el-button plain size="default">
                <el-icon style="margin-right: 4px;"><UploadFilled /></el-icon>
                选择关联文档
              </el-button>
              <template #tip>
                <div class="upload-tip">可多个，含图片、表格等关联材料，将与主文档整合为一份需求。</div>
              </template>
            </el-upload>
          </el-form-item>
        </template>

        <el-form-item label="文档内嵌图片解析" prop="parseImage">
          <el-switch v-model="createForm.parseImage" :disabled="isImageUpload" />
          <div class="upload-tip">开启后会解析文档中内嵌的图片并回填至原文，耗时更长；关闭则仅解析文本内容。{{ isImageUpload ? '当前上传均为图片，图片将被直接解析，无需此项。' : '' }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleCreate" :loading="creating" :disabled="!createForm.prdName">
          创建并生成
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ref, reactive, onMounted, onActivated, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { testgenApi } from '@/api/testgen'
import { useUserStore } from '@/stores/user'
import config from '@/config/index.js'

export default {
  name: 'TestGenList',
  components: { UploadFilled },
  setup() {
    const router = useRouter()
    const userStore = useUserStore()
    const taskList = ref([])
    const total = ref(0)
    const query = reactive({ pageNo: 1, pageSize: 10 })
    const loading = ref(false)
    const creating = ref(false)
    const createDialogVisible = ref(false)
    const formRef = ref(null)
    const mainUploadRef = ref(null)
    const relatedUploadRef = ref(null)
    const createForm = ref({ prdName: '', prdSource: 'LINK', parseImage: false, parseSubDoc: true, relatedNames: [] })
    const isImageUpload = ref(false)
    const linkUrl = ref('')
    // 上传文件名：主文档（单个）+ 关联文档（多个）
    const mainFileName = ref('')
    const relatedFileNames = ref([])
    const acceptExts = '.pdf,.docx,.txt,.md,.markdown,.png,.jpg,.jpeg,.webp,.xlsx,.xls,.csv'
    const uploadUrl = `${config.baseURL}${config.apiPrefix}/common/file/upload`
    // el-upload 走原生 XHR，不经过 axios 拦截器，需手动带上登录 token
    const uploadHeaders = ref({ Authorization: 'Bearer ' + (localStorage.getItem('token') || '') })

    const isImageName = (name) => /\.(png|jpg|jpeg|webp)$/i.test(name || '')

    // prdName 只存主文档（链接模式为单个链接，上传模式为主文件名）；关联文档单独放 relatedNames
    const syncPrdName = () => {
      if (createForm.value.prdSource === 'LINK') {
        createForm.value.prdName = (linkUrl.value || '').trim()
        createForm.value.relatedNames = []
      } else {
        createForm.value.prdName = mainFileName.value || ''
        createForm.value.relatedNames = [...relatedFileNames.value]
      }
    }

    // 内嵌图片解析开关的可用性：主/关联全部为图片时禁用并强制关闭
    const syncImageFlag = () => {
      const names = []
      if (mainFileName.value) names.push(mainFileName.value)
      names.push(...relatedFileNames.value)
      isImageUpload.value = names.length > 0 && names.every(isImageName)
      if (isImageUpload.value) {
        createForm.value.parseImage = false
      }
    }

    // 链接模式下实时同步 prdName（供创建按钮禁用判断）
    watch(linkUrl, () => {
      if (createForm.value.prdSource === 'LINK') syncPrdName()
    })

    // 切换来源时清空另一种输入，避免串台
    watch(() => createForm.value.prdSource, () => {
      createForm.value.prdName = ''
      createForm.value.parseImage = false
      createForm.value.parseSubDoc = true
      createForm.value.relatedNames = []
      linkUrl.value = ''
      mainFileName.value = ''
      relatedFileNames.value = []
      isImageUpload.value = false
      if (mainUploadRef.value && mainUploadRef.value.clearFiles) mainUploadRef.value.clearFiles()
      if (relatedUploadRef.value && relatedUploadRef.value.clearFiles) relatedUploadRef.value.clearFiles()
    })

    const formRules = {
      prdName: [{ required: true, message: '请提供需求文档', trigger: 'change' }]
    }

    const statusTextMap = {
      NEW: '新建',
      PLANNING: '规划中',
      PLAN_REVIEW: '待确认',
      GENERATING: '生成中',
      EDITING: '编辑中',
      FINISHED: '已完成',
      FAILED: '失败'
    }
    const statusTypeMap = {
      NEW: 'info',
      PLANNING: 'warning',
      PLAN_REVIEW: 'warning',
      GENERATING: 'warning',
      EDITING: 'info',
      FINISHED: 'success',
      FAILED: 'danger'
    }
    const prdSourceMap = { UPLOAD: '文档', LINK: '链接' }

    const formatDateTime = (val) => {
      if (!val) return ''
      return String(val).replace('T', ' ').replace(/\.\d+$/, '').substring(0, 19)
    }

    const createTimeFormatter = (row) => formatDateTime(row && row.createTime)

    // 需求文档展示：只显示主文档名称（两种来源一致）。优先用解析出的真实名，否则退回链接/主文件名
    const displayDocLines = (row) => {
      if (!row) return []
      if (row.prdDisplayName) return [row.prdDisplayName]
      if (row.prdSource === 'LINK') return [row.prdName || '']
      const names = row.prdName ? row.prdName.split('\n') : []
      return names.length ? [names[0]] : []
    }

    const fetchList = async () => {
      loading.value = true
      try {
        const res = await testgenApi.listTasks(query)
        if (res.code === 0 && res.data) {
          taskList.value = res.data.list || []
          total.value = res.data.total || 0
        } else {
          taskList.value = []
          total.value = 0
        }
      } catch (e) {
        console.error('获取任务列表失败', e)
        taskList.value = []
        total.value = 0
      } finally {
        loading.value = false
      }
    }

    const onPageChange = (page) => {
      query.pageNo = page
      fetchList()
    }

    const onSizeChange = (size) => {
      query.pageSize = size
      query.pageNo = 1
      fetchList()
    }

    const beforeUpload = (file) => {
      const validTypes = [
        'application/pdf',
        'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'application/vnd.ms-excel',
        'text/csv',
        'text/plain',
        'text/markdown',
        'image/png',
        'image/jpeg',
        'image/webp'
      ]
      if (!validTypes.includes(file.type) && !file.name.match(/\.(pdf|docx|txt|md|markdown|png|jpg|jpeg|webp|xlsx|xls|csv)$/i)) {
        ElMessage.error('仅支持 PDF、DOCX、TXT、Markdown、表格(XLSX/XLS/CSV) 或 PNG、JPG、JPEG、WEBP 图片')
        return false
      }
      if (file.size > 100 * 1024 * 1024) {
        ElMessage.error('文件大小不能超过 100MB')
        return false
      }
      return true
    }

    // 主文档：单个
    const onMainSuccess = (response) => {
      if (response.code === 0 && response.data && response.data.fileName) {
        mainFileName.value = response.data.fileName
        syncPrdName()
        syncImageFlag()
        ElMessage.success('主文档上传成功')
      } else {
        ElMessage.error('上传失败')
      }
    }
    const onMainRemove = () => {
      mainFileName.value = ''
      syncPrdName()
      syncImageFlag()
    }
    const onMainExceed = () => {
      ElMessage.warning('只能上传一个主文档，请先移除当前主文档再重新选择')
    }

    // 关联文档：多个，从当前文件列表重建
    const rebuildRelated = (uploadFiles) => {
      relatedFileNames.value = (uploadFiles || [])
        .filter(f => f.response && f.response.code === 0 && f.response.data && f.response.data.fileName)
        .map(f => f.response.data.fileName)
      syncPrdName()
      syncImageFlag()
    }
    const onRelatedSuccess = (response, uploadFile, uploadFiles) => {
      if (response.code === 0 && response.data) {
        rebuildRelated(uploadFiles)
        ElMessage.success('关联文档上传成功')
      } else {
        ElMessage.error('上传失败')
      }
    }
    const onRelatedRemove = (uploadFile, uploadFiles) => {
      rebuildRelated(uploadFiles)
    }

    const onUploadError = () => {
      ElMessage.error('文件上传失败，请重试')
    }

    const openCreateDialog = () => {
      createForm.value = { prdName: '', prdSource: 'LINK', parseImage: false, parseSubDoc: true, relatedNames: [] }
      isImageUpload.value = false
      linkUrl.value = ''
      mainFileName.value = ''
      relatedFileNames.value = []
      uploadHeaders.value = { Authorization: 'Bearer ' + (localStorage.getItem('token') || '') }
      if (mainUploadRef.value && mainUploadRef.value.clearFiles) mainUploadRef.value.clearFiles()
      if (relatedUploadRef.value && relatedUploadRef.value.clearFiles) relatedUploadRef.value.clearFiles()
      createDialogVisible.value = true
    }

    const handleCreate = async () => {
      if (!createForm.value.prdName) {
        ElMessage.warning(createForm.value.prdSource === 'LINK' ? '请填写文档链接' : '请上传主文档')
        return
      }
      creating.value = true
      try {
        createForm.value.creator = userStore.userInfo?.username || ''
        const res = await testgenApi.createTask(createForm.value)
        if (res.code !== 0) {
          ElMessage.error(res.data || res.msg || '创建任务失败')
          return
        }
        const taskId = res.data.taskId
        createDialogVisible.value = false
        // 先跳转工作区，等 WebSocket 建立后再触发生成
        router.push(`/toolbox/testgen/${taskId}?generate=true`)
      } finally {
        creating.value = false
      }
    }

    const continueGen = (row) => {
      if (row.status === 'NEW') {
        router.push(`/toolbox/testgen/${row.id}?generate=true`)
      } else {
        router.push(`/toolbox/testgen/${row.id}`)
      }
    }

    const regenerate = async (row) => {
      try {
        await ElMessageBox.confirm('确定要重新生成吗？当前数据将被清空。', '提示', { type: 'warning' })
        await fetchList()
        router.push(`/toolbox/testgen/${row.id}?regenerate=true`)
      } catch (e) {
        if (e !== 'cancel') {
          console.error(e)
        }
      }
    }

    const downloadXmind = async (row) => {
      try {
        const res = await testgenApi.getDownloadUrl(row.id)
        if (res.code === 0 && res.data) {
          window.open(res.data, '_blank')
        } else {
          ElMessage.warning('暂无可下载的文件')
        }
      } catch (e) {
        ElMessage.error('获取下载链接失败')
      }
    }

    const deleteTask = async (row) => {
      const docName = displayDocLines(row)[0] || row.prdName
      try {
        await ElMessageBox.confirm(
          `确定要删除任务"${docName}"吗？删除后不可恢复。`,
          '删除确认',
          {
            confirmButtonText: '确定删除',
            cancelButtonText: '取消',
            type: 'warning'
          }
        )
      } catch (e) {
        return
      }
      try {
        const res = await testgenApi.deleteTask(row.id)
        if (res.code === 0) {
          ElMessage.success('删除成功')
          fetchList()
        } else {
          ElMessage.error(res.msg || '删除失败')
        }
      } catch (e) {
        ElMessage.error('删除失败')
      }
    }

    onMounted(fetchList)
    onActivated(fetchList)

    return {
      taskList, total, query, loading, creating, createDialogVisible, createForm, isImageUpload,
      linkUrl, acceptExts, formRef, mainUploadRef, relatedUploadRef, uploadUrl, uploadHeaders, formRules,
      statusTextMap, statusTypeMap, prdSourceMap,
      formatDateTime, createTimeFormatter, displayDocLines,
      fetchList, onPageChange, onSizeChange, beforeUpload,
      onMainSuccess, onMainRemove, onMainExceed, onRelatedSuccess, onRelatedRemove, onUploadError,
      openCreateDialog, handleCreate, continueGen, regenerate, downloadXmind, deleteTask
    }
  }
}
</script>

<style scoped>
.test-gen-list { padding: 20px; }
.action-bar { margin-bottom: 16px; display: flex; justify-content: flex-end; gap: 8px; }
.pager { margin-top: 16px; display: flex; justify-content: flex-end; }
:deep(.center-header) {
  text-align: center;
}
:deep(.create-dialog .el-dialog__body) {
  padding: 24px 32px 8px;
}
:deep(.create-dialog .el-dialog__footer) {
  padding: 12px 32px 24px;
}
:deep(.create-dialog .el-upload-list__item-name) {
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.upload-tip {
  margin-top: 8px;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}
.prd-name-line {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.6;
}
</style>
