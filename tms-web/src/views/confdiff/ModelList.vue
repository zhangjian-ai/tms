<template>
  <div class="ai-model">
    <div class="action-bar">
      <el-input v-model="query.name" placeholder="模型名称" clearable style="width: 180px" @keyup.enter="fetchList" />
      <el-button @click="fetchList">查询</el-button>
      <el-button type="primary" @click="openDialog()">新增模型</el-button>
    </div>

    <el-table :data="list" v-loading="loading" stripe header-cell-class-name="center-header">
      <el-table-column prop="id" label="ID" min-width="60" align="center" />
      <el-table-column prop="name" label="名称" min-width="120" align="center" />
      <el-table-column prop="modelName" label="模型标识" min-width="140" align="center" show-overflow-tooltip />
      <el-table-column prop="baseUrl" label="base-url" min-width="220" align="left" show-overflow-tooltip />
      <el-table-column label="thinking" min-width="100" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="!!row.useAsThinking"
            :loading="markingId === row.id"
            @change="onMark(row, 'thinking', $event)"
          />
        </template>
      </el-table-column>
      <el-table-column label="vision" min-width="100" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="!!row.useAsVision"
            :loading="markingId === row.id"
            @change="onMark(row, 'vision', $event)"
          />
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" align="left" show-overflow-tooltip />
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑模型' : '新增模型'" width="560px" :close-on-click-modal="false">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="100px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="如: 思考模型" />
        </el-form-item>
        <el-form-item label="base-url" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="OpenAI 兼容接口地址,如 https://xxx/v1" />
        </el-form-item>
        <el-form-item label="模型标识" prop="modelName">
          <el-input v-model="form.modelName" placeholder="实际模型名,如 gpt-5.5" />
        </el-form-item>
        <el-form-item label="密钥" prop="apiKey">
          <el-input v-model="form.apiKey" type="password" show-password
                    :placeholder="form.id ? '编辑时留空表示不修改' : '请输入接口密钥'" />
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
import { modelApi } from '@/api/testgen'

export default {
  name: 'AiModelList',
  setup() {
    const list = ref([])
    const total = ref(0)
    const loading = ref(false)
    const saving = ref(false)
    const markingId = ref(null)
    const dialogVisible = ref(false)
    const formRef = ref(null)
    const query = reactive({ name: '', pageNo: 1, pageSize: 10, sortBy: 'id', asc: true })

    const emptyForm = () => ({
      id: null, name: '', baseUrl: '', apiKey: '', modelName: '', remark: ''
    })
    const form = reactive(emptyForm())

    const rules = computed(() => ({
      name: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
      baseUrl: [{ required: true, message: '请输入 base-url', trigger: 'blur' }],
      modelName: [{ required: true, message: '请输入模型标识', trigger: 'blur' }],
      // 新增时密钥必填,编辑时可留空(表示不修改)
      apiKey: form.id
        ? []
        : [{ required: true, message: '请输入接口密钥', trigger: 'blur' }]
    }))

    const fetchList = async () => {
      loading.value = true
      try {
        const res = await modelApi.list(query)
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

    const onMark = async (row, role, marked) => {
      markingId.value = row.id
      try {
        const res = await modelApi.mark(row.id, role, marked)
        if (res.code === 0) {
          ElMessage.success(marked ? `已标记为 ${role}` : `已取消 ${role} 标记`)
        } else {
          ElMessage.error(res.msg || '标记失败')
        }
      } catch (e) {
        ElMessage.error('标记失败')
      } finally {
        markingId.value = null
        // 重新拉取:标记生效会自动取消其他行的同一角色
        fetchList()
      }
    }

    const openDialog = (row) => {
      Object.assign(form, emptyForm())
      if (row) {
        // 密钥脱敏不回显，留空表示不修改；用途标记在列表开关维护，不入表单
        Object.assign(form, {
          id: row.id, name: row.name, baseUrl: row.baseUrl,
          modelName: row.modelName, remark: row.remark
        })
      }
      dialogVisible.value = true
      formRef.value?.clearValidate?.()
    }

    const submit = async () => {
      await formRef.value.validate()
      saving.value = true
      try {
        const payload = { ...form }
        // 编辑时密钥留空不提交,避免覆盖
        if (payload.id && !payload.apiKey) delete payload.apiKey
        const res = await modelApi.saveOrUpdate(payload)
        if (res.code === 0) {
          ElMessage.success('保存成功')
          dialogVisible.value = false
          fetchList()
        } else {
          ElMessage.error(res.msg || '保存失败')
        }
      } catch (e) {
        ElMessage.error('保存失败')
      } finally {
        saving.value = false
      }
    }

    const remove = async (row) => {
      try {
        await ElMessageBox.confirm(`确定删除模型「${row.name}」？`, '提示', { type: 'warning' })
        const res = await modelApi.delete(row.id)
        if (res.code === 0) {
          ElMessage.success('删除成功')
          fetchList()
        } else {
          ElMessage.error(res.msg || '删除失败')
        }
      } catch (e) {
        // 取消
      }
    }

    onMounted(fetchList)

    return {
      list, total, loading, saving, markingId, dialogVisible, formRef, query, form, rules,
      fetchList, onPageChange, onMark, openDialog, submit, remove
    }
  }
}
</script>

<style scoped>
.ai-model {
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
</style>
