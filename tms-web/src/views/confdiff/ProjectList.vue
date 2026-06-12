<template>
  <div class="conf-project">
    <div class="action-bar">
      <el-select v-model="query.machineId" placeholder="按机器筛选" clearable filterable style="width: 200px" @change="fetchList">
        <el-option v-for="m in machines" :key="m.id" :label="m.name" :value="m.id" />
      </el-select>
      <el-input v-model="query.name" placeholder="项目名称" clearable style="width: 160px" @keyup.enter="fetchList" />
      <el-button @click="fetchList">查询</el-button>
      <el-button type="primary" @click="openDialog()">新增项目</el-button>
    </div>

    <el-table :data="list" v-loading="loading" stripe header-cell-class-name="center-header">
      <el-table-column prop="id" label="ID" min-width="70" align="center" />
      <el-table-column prop="name" label="项目名称" min-width="140" align="center" />
      <el-table-column prop="machineName" label="所属机器" min-width="130" align="center" />
      <el-table-column prop="repoUrl" label="仓库地址" min-width="240" align="left" show-overflow-tooltip />
      <el-table-column label="配置路径" min-width="200" align="left">
        <template #default="{ row }">
          <el-tag v-for="p in row.configPaths" :key="p" size="small" style="margin: 2px">{{ p }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="defaultBranch" label="默认分支" min-width="110" align="center" />
      <el-table-column prop="remark" label="备注" min-width="120" align="left" show-overflow-tooltip />
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑项目' : '新增项目'" width="560px" :close-on-click-modal="false">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="100px">
        <el-form-item label="所属机器" prop="machineId">
          <el-select v-model="form.machineId" placeholder="选择机器" filterable style="width: 100%">
            <el-option v-for="m in machines" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目名称" prop="name">
          <el-input v-model="form.name" placeholder="同时作为远程 clone 目录名" />
        </el-form-item>
        <el-form-item label="仓库地址" prop="repoUrl">
          <el-input v-model="form.repoUrl" placeholder="git 仓库地址" />
        </el-form-item>
        <el-form-item label="配置路径" prop="configPaths">
          <el-select
            v-model="form.configPaths"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="输入相对仓库根的路径,回车添加多个"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="默认分支" prop="defaultBranch">
          <el-input v-model="form.defaultBranch" placeholder="master" />
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
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confProjectApi, confMachineApi } from '@/api/confdiff'

export default {
  name: 'ConfProjectList',
  setup() {
    const list = ref([])
    const machines = ref([])
    const total = ref(0)
    const loading = ref(false)
    const saving = ref(false)
    const dialogVisible = ref(false)
    const formRef = ref(null)
    const query = reactive({ machineId: null, name: '', pageNo: 1, pageSize: 10 })

    const emptyForm = () => ({
      id: null, machineId: null, name: '', repoUrl: '',
      configPaths: [], defaultBranch: 'master', remark: ''
    })
    const form = reactive(emptyForm())

    const rules = {
      machineId: [{ required: true, message: '请选择机器', trigger: 'change' }],
      name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
      repoUrl: [{ required: true, message: '请输入仓库地址', trigger: 'blur' }],
      configPaths: [{ type: 'array', required: true, message: '请至少添加一个配置路径', trigger: 'change' }]
    }

    const loadMachines = async () => {
      const res = await confMachineApi.list({ pageNo: 1, pageSize: 200 })
      if (res.code === 0 && res.data) machines.value = res.data.list || []
    }

    const fetchList = async () => {
      loading.value = true
      try {
        const res = await confProjectApi.list(query)
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

    const openDialog = (row) => {
      Object.assign(form, emptyForm())
      if (row) {
        Object.assign(form, {
          id: row.id, machineId: row.machineId, name: row.name, repoUrl: row.repoUrl,
          configPaths: [...(row.configPaths || [])], defaultBranch: row.defaultBranch, remark: row.remark
        })
      }
      dialogVisible.value = true
      formRef.value?.clearValidate?.()
    }

    const submit = async () => {
      await formRef.value.validate()
      saving.value = true
      try {
        const res = await confProjectApi.saveOrUpdate({ ...form })
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
        await ElMessageBox.confirm(`确定删除项目「${row.name}」？`, '提示', { type: 'warning' })
        const res = await confProjectApi.delete(row.id)
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

    onMounted(async () => {
      await loadMachines()
      fetchList()
    })

    return {
      list, machines, total, loading, saving, dialogVisible, formRef, query, form, rules,
      fetchList, onPageChange, openDialog, submit, remove
    }
  }
}
</script>

<style scoped>
.conf-project {
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
