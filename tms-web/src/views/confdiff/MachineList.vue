<template>
  <div class="conf-machine">
    <div class="action-bar">
      <el-input v-model="query.name" placeholder="机器名称" clearable style="width: 160px" @keyup.enter="fetchList" />
      <el-input v-model="query.host" placeholder="主机地址" clearable style="width: 160px" @keyup.enter="fetchList" />
      <el-button @click="fetchList">查询</el-button>
      <el-button type="primary" @click="openDialog()">新增机器</el-button>
    </div>

    <el-table :data="list" v-loading="loading" stripe header-cell-class-name="center-header">
      <el-table-column prop="id" label="ID" min-width="70" align="center" />
      <el-table-column prop="name" label="机器名称" min-width="140" align="center" />
      <el-table-column label="地址" min-width="180" align="center">
        <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
      </el-table-column>
      <el-table-column prop="username" label="用户名" min-width="110" align="center" />
      <el-table-column label="鉴权" min-width="100" align="center">
        <template #default="{ row }">
          <el-tag size="small">{{ row.authType === 'private_key' ? '私钥' : '密码' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="workDir" label="工作目录" min-width="200" align="left" show-overflow-tooltip />
      <el-table-column prop="remark" label="备注" min-width="140" align="left" show-overflow-tooltip />
      <el-table-column label="操作" min-width="220" align="center" fixed="right">
        <template #default="{ row }">
          <el-button size="small" @click="testConnection(row)" :loading="testingId === row.id">测试连接</el-button>
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑机器' : '新增机器'" width="560px" :close-on-click-modal="false">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="100px">
        <el-form-item label="机器名称" prop="name">
          <el-input v-model="form.name" placeholder="如: 配置编译机" />
        </el-form-item>
        <el-form-item label="主机地址" prop="host">
          <el-input v-model="form.host" placeholder="IP 或域名" />
        </el-form-item>
        <el-form-item label="端口" prop="port">
          <el-input-number v-model="form.port" :min="1" :max="65535" />
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" />
        </el-form-item>
        <el-form-item label="鉴权方式" prop="authType">
          <el-radio-group v-model="form.authType">
            <el-radio-button label="password">密码</el-radio-button>
            <el-radio-button label="private_key">私钥</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.authType === 'password'" label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="编辑时留空表示不修改" />
        </el-form-item>
        <template v-else>
          <el-form-item label="私钥" prop="privateKey">
            <el-input v-model="form.privateKey" type="textarea" :rows="4" placeholder="PEM 私钥内容,编辑时留空表示不修改" />
          </el-form-item>
          <el-form-item label="私钥口令">
            <el-input v-model="form.passphrase" type="password" show-password placeholder="无口令可留空" />
          </el-form-item>
        </template>
        <el-form-item label="工作目录" prop="workDir">
          <el-input v-model="form.workDir" placeholder="项目统一 clone 的远程目录,如 /data/confdiff/repos" />
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
import { confMachineApi } from '@/api/confdiff'

export default {
  name: 'ConfMachineList',
  setup() {
    const list = ref([])
    const total = ref(0)
    const loading = ref(false)
    const saving = ref(false)
    const testingId = ref(null)
    const dialogVisible = ref(false)
    const formRef = ref(null)
    const query = reactive({ name: '', host: '', pageNo: 1, pageSize: 10 })

    const emptyForm = () => ({
      id: null, name: '', host: '', port: 22, username: '',
      authType: 'password', password: '', privateKey: '', passphrase: '',
      workDir: '', remark: ''
    })
    const form = reactive(emptyForm())

    const rules = {
      name: [{ required: true, message: '请输入机器名称', trigger: 'blur' }],
      host: [{ required: true, message: '请输入主机地址', trigger: 'blur' }],
      username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
      workDir: [{ required: true, message: '请输入工作目录', trigger: 'blur' }]
    }

    const fetchList = async () => {
      loading.value = true
      try {
        const res = await confMachineApi.list(query)
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
        // 敏感字段不回显(后端脱敏),留空表示不修改
        Object.assign(form, {
          id: row.id, name: row.name, host: row.host, port: row.port,
          username: row.username, authType: row.authType, workDir: row.workDir, remark: row.remark
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
        // 编辑时留空的敏感字段不提交,避免覆盖
        if (payload.id) {
          if (!payload.password) delete payload.password
          if (!payload.privateKey) delete payload.privateKey
        }
        const res = await confMachineApi.saveOrUpdate(payload)
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
        await ElMessageBox.confirm(`确定删除机器「${row.name}」？其下项目将一并删除。`, '提示', { type: 'warning' })
        const res = await confMachineApi.delete(row.id)
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

    const testConnection = async (row) => {
      testingId.value = row.id
      try {
        const res = await confMachineApi.testConnection(row.id)
        if (res.code === 0) {
          ElMessage.success('连接成功')
        } else {
          ElMessage.error(res.msg || '连接失败')
        }
      } catch (e) {
        ElMessage.error('连接失败')
      } finally {
        testingId.value = null
      }
    }

    onMounted(fetchList)

    return {
      list, total, loading, saving, testingId, dialogVisible, formRef, query, form, rules,
      fetchList, onPageChange, openDialog, submit, remove, testConnection
    }
  }
}
</script>

<style scoped>
.conf-machine {
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
