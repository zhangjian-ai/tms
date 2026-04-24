<template>
  <div class="test-gen-list">
    <div class="action-bar">
      <el-button @click="fetchList">查询</el-button>
      <el-button type="primary" @click="openCreateDialog">新建任务</el-button>
    </div>

    <el-table :data="taskList" v-loading="loading" stripe header-cell-class-name="center-header">
      <el-table-column prop="prdName" label="需求文档" min-width="200" align="center" />
      <el-table-column prop="prdType" label="需求类型" width="100" align="center">
        <template #default="{ row }">
          <el-tag size="small">{{ prdTypeMap[row.prdType] || row.prdType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTypeMap[row.status]" size="small">{{ statusTextMap[row.status] || row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="170" align="center" />
      <el-table-column label="操作" width="280" align="center" fixed="right">
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
            v-if="row.status === 'GENERATING'"
            size="small"
            type="primary"
            @click="continueGen(row)"
          >
            继续生成
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
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建任务对话框 -->
    <el-dialog v-model="createDialogVisible" title="新建用例生成任务" width="520px" :close-on-click-modal="false" class="create-dialog">
      <el-form :model="createForm" label-width="90px" :rules="formRules" ref="formRef">
        <el-form-item label="需求文档" prop="prdName">
          <el-upload
            ref="uploadRef"
            :action="uploadUrl"
            :limit="1"
            :on-success="onUploadSuccess"
            :on-error="onUploadError"
            :on-remove="onUploadRemove"
            :before-upload="beforeUpload"
            accept=".pdf,.docx,.txt"
            :show-file-list="true"
          >
            <el-button type="primary" plain size="default">
              <el-icon style="margin-right: 4px;"><UploadFilled /></el-icon>
              选择文件
            </el-button>
            <template #tip>
              <div class="upload-tip">支持 PDF、DOCX、TXT 格式，最大 100MB</div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item label="需求类型" prop="prdType">
          <el-radio-group v-model="createForm.prdType">
            <el-radio-button label="BIZ">业务需求</el-radio-button>
            <el-radio-button label="BURY">埋点需求</el-radio-button>
            <el-radio-button label="API">接口需求</el-radio-button>
          </el-radio-group>
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
import { ref, onMounted, onActivated } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { testgenApi } from '@/api/testgen'
import config from '@/config/index.js'

export default {
  name: 'TestGenList',
  components: { UploadFilled },
  setup() {
    const router = useRouter()
    const taskList = ref([])
    const loading = ref(false)
    const creating = ref(false)
    const createDialogVisible = ref(false)
    const formRef = ref(null)
    const uploadRef = ref(null)
    const createForm = ref({ prdName: '', prdType: 'BIZ' })
    const uploadUrl = `${config.baseURL}${config.apiPrefix}/common/file/upload`

    const formRules = {
      prdName: [{ required: true, message: '请上传需求文档', trigger: 'change' }]
    }

    const statusTextMap = { NEW: '新建', GENERATING: '生成中', EDITING: '编辑中', FINISHED: '已完成', FAILED: '失败' }
    const statusTypeMap = { NEW: 'info', GENERATING: 'warning', EDITING: 'info', FINISHED: 'success', FAILED: 'danger' }
    const prdTypeMap = { BIZ: '业务需求', BURY: '埋点需求', API: '接口需求' }

    const fetchList = async () => {
      loading.value = true
      try {
        const res = await testgenApi.listTasks()
        taskList.value = Array.isArray(res.data) ? res.data : []
      } catch (e) {
        console.error('获取任务列表失败', e)
        taskList.value = []
      } finally {
        loading.value = false
      }
    }

    const beforeUpload = (file) => {
      const validTypes = ['application/pdf', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'text/plain']
      if (!validTypes.includes(file.type) && !file.name.match(/\.(pdf|docx|txt)$/i)) {
        ElMessage.error('仅支持 PDF、DOCX、TXT 格式')
        return false
      }
      if (file.size > 100 * 1024 * 1024) {
        ElMessage.error('文件大小不能超过 100MB')
        return false
      }
      return true
    }

    const onUploadSuccess = (response) => {
      if (response.code === 0 && response.data) {
        createForm.value.prdName = response.data.fileName
        ElMessage.success('文档上传成功')
      } else {
        ElMessage.error('上传失败')
      }
    }

    const onUploadError = () => {
      ElMessage.error('文档上传失败，请重试')
    }

    const onUploadRemove = () => {
      createForm.value.prdName = ''
    }

    const openCreateDialog = () => {
      createForm.value = { prdName: '', prdType: 'BIZ' }
      createDialogVisible.value = true
    }

    const handleCreate = async () => {
      if (!createForm.value.prdName) {
        ElMessage.warning('请先上传需求文档')
        return
      }
      creating.value = true
      try {
        const res = await testgenApi.createTask(createForm.value)
        const taskId = res.data.taskId
        createDialogVisible.value = false
        // 先跳转到工作区页面，让 WebSocket 建立后再触发生成
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
        // 刷新列表以更新状态
        await fetchList()
        // 跳转到工作区页面，让 WebSocket 建立连接
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

    onMounted(fetchList)
    onActivated(fetchList)

    return {
      taskList, loading, creating, createDialogVisible, createForm, formRef, uploadRef, uploadUrl, formRules,
      statusTextMap, statusTypeMap, prdTypeMap,
      fetchList, beforeUpload, onUploadSuccess, onUploadError, onUploadRemove,
      openCreateDialog, handleCreate, continueGen, regenerate, downloadXmind
    }
  }
}
</script>

<style scoped>
.test-gen-list { padding: 20px; }
.action-bar { margin-bottom: 16px; display: flex; justify-content: flex-end; gap: 8px; }
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
</style>
