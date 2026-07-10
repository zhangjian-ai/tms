<template>
  <div class="prompt-mgr">
    <div class="action-bar">
      <el-input v-model="query.name" placeholder="提示词名称" clearable style="width: 180px" @keyup.enter="fetchList" />
      <el-select v-model="query.stageKey" placeholder="按阶段筛选" clearable style="width: 200px" @change="fetchList">
        <el-option v-for="s in stages" :key="s.stageKey" :label="s.stageName" :value="s.stageKey" />
      </el-select>
      <el-button @click="fetchList">查询</el-button>
      <el-button type="primary" @click="openDialog()">新增提示词</el-button>
    </div>

    <el-table :data="list" v-loading="loading" stripe header-cell-class-name="center-header">
      <el-table-column prop="id" label="ID" min-width="60" align="center" />
      <el-table-column prop="name" label="名称" min-width="180" align="left" show-overflow-tooltip />
      <el-table-column label="生效阶段" min-width="180" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.stageKey" type="success" size="small">{{ row.stageName || row.stageKey }}</el-tag>
          <el-tag v-else type="info" size="small">草稿(未生效)</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160" align="left" show-overflow-tooltip />
      <el-table-column prop="updateTime" label="更新时间" min-width="180" align="center" :formatter="timeFormatter" />
      <el-table-column label="操作" min-width="160" align="center" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager">
      <el-pagination
        layout="total, prev, pager, next"
        :total="total"
        :page-size="query.pageSize"
        :current-page="query.pageNo"
        @current-change="onPageChange"
      />
    </div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑提示词' : '新增提示词'" width="760px" :close-on-click-modal="false">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="如: 用例生成-精简版" />
        </el-form-item>
        <el-form-item label="生效阶段" prop="stageKey">
          <el-select v-model="form.stageKey" placeholder="选择阶段(不选则为草稿,不生效)" clearable style="width: 100%">
            <el-option
              v-for="s in stages"
              :key="s.stageKey"
              :label="stageOptionLabel(s)"
              :value="s.stageKey"
            />
          </el-select>
          <div class="field-tip">同一阶段仅能有一个生效提示词;选择已被占用的阶段将替换原提示词(原提示词转为草稿)。</div>
        </el-form-item>
        <el-form-item label="系统提示词" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :autosize="{ minRows: 12, maxRows: 24 }"
            placeholder="请输入系统提示词内容"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit" :loading="saving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { promptApi } from '@/api/testgen'

export default {
  name: 'PromptList',
  setup() {
    const list = ref([])
    const stages = ref([])
    const total = ref(0)
    const loading = ref(false)
    const saving = ref(false)
    const dialogVisible = ref(false)
    const formRef = ref(null)
    const query = reactive({ name: '', stageKey: '', pageNo: 1, pageSize: 10, sortBy: 'id', asc: true })

    const emptyForm = () => ({ id: null, name: '', stageKey: '', content: '', remark: '' })
    const form = reactive(emptyForm())

    const rules = computed(() => ({
      name: [{ required: true, message: '请输入提示词名称', trigger: 'blur' }],
      content: [{ required: true, message: '请输入系统提示词内容', trigger: 'blur' }]
    }))

    const formatDateTime = (val) => {
      if (!val) return ''
      return String(val).replace('T', ' ').replace(/\.\d+$/, '').substring(0, 19)
    }
    const timeFormatter = (row) => formatDateTime(row && row.updateTime)

    // 下拉项文案:已被占用的阶段附带当前提示词名
    const stageOptionLabel = (s) => {
      if (s.boundPromptId && s.boundPromptId !== form.id) {
        return `${s.stageName}（已绑定: ${s.boundPromptName}）`
      }
      return s.stageName
    }

    const fetchStages = async () => {
      try {
        const res = await promptApi.stages()
        if (res.code === 0) stages.value = res.data || []
      } catch (e) {
        // 忽略
      }
    }

    const fetchList = async () => {
      loading.value = true
      try {
        const res = await promptApi.list(query)
        if (res.code === 0 && res.data) {
          list.value = res.data.list || []
          total.value = res.data.total || 0
        }
      } catch (e) {
        ElMessage.error('查询失败')
      } finally {
        loading.value = false
      }
    }

    const onPageChange = (page) => {
      query.pageNo = page
      fetchList()
    }

    const openDialog = async (row) => {
      Object.assign(form, emptyForm())
      await fetchStages()
      if (row) {
        try {
          const res = await promptApi.detail(row.id)
          if (res.code === 0 && res.data) {
            Object.assign(form, {
              id: res.data.id,
              name: res.data.name,
              stageKey: res.data.stageKey || '',
              content: res.data.content || '',
              remark: res.data.remark || ''
            })
          }
        } catch (e) {
          ElMessage.error('加载详情失败')
          return
        }
      }
      dialogVisible.value = true
      formRef.value?.clearValidate?.()
    }

    // 找到目标阶段当前绑定的、非本条的提示词(用于接管弹窗提示)
    const occupiedBy = (stageKey) => {
      if (!stageKey) return null
      const s = stages.value.find(x => x.stageKey === stageKey)
      if (s && s.boundPromptId && s.boundPromptId !== form.id) return s
      return null
    }

    const submit = async () => {
      await formRef.value.validate()
      const conflict = occupiedBy(form.stageKey)
      if (conflict) {
        try {
          await ElMessageBox.confirm(
            `阶段「${conflict.stageName}」已绑定提示词「${conflict.boundPromptName}」。保存后将由当前提示词接管该阶段，原提示词转为草稿（不生效）。确认继续？`,
            '阶段冲突提示',
            { type: 'warning', confirmButtonText: '确认接管', cancelButtonText: '取消' }
          )
        } catch (e) {
          return
        }
      }
      saving.value = true
      try {
        const payload = { ...form, stageKey: form.stageKey || null }
        const res = await promptApi.saveOrUpdate(payload)
        if (res.code === 0) {
          ElMessage.success('保存成功')
          dialogVisible.value = false
          await fetchStages()
          fetchList()
        } else {
          ElMessage.error(res.data || res.msg || '保存失败')
        }
      } catch (e) {
        ElMessage.error('保存失败')
      } finally {
        saving.value = false
      }
    }

    const remove = async (row) => {
      try {
        await ElMessageBox.confirm(`确定删除提示词「${row.name}」？`, '提示', { type: 'warning' })
        const res = await promptApi.delete(row.id)
        if (res.code === 0) {
          ElMessage.success('删除成功')
          await fetchStages()
          fetchList()
        } else {
          ElMessage.error(res.msg || '删除失败')
        }
      } catch (e) {
        // 取消
      }
    }

    onMounted(() => {
      fetchStages()
      fetchList()
    })

    return {
      list, stages, total, loading, saving, dialogVisible, formRef, query, form, rules,
      timeFormatter, stageOptionLabel,
      fetchList, onPageChange, openDialog, submit, remove
    }
  }
}
</script>

<style scoped>
.prompt-mgr {
  padding: 16px;
}
.action-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
.field-tip {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
}
</style>
