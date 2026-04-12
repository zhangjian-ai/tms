<template>
  <div class="device-list">
    <!-- 合并的搜索和表格区域 -->
    <el-card class="main-card">
      <!-- 搜索区域 -->
      <div class="search-section">
        <el-form :model="searchForm" class="search-form" :inline="true" label-width="80px">
          <div class="search-row">
            <el-form-item label="设备名称" class="search-item">
              <el-input
                v-model="searchForm.name"
                placeholder="请输入设备名称"
                clearable
              />
            </el-form-item>
            <el-form-item label="序列号" class="search-item">
              <el-input
                v-model="searchForm.serial"
                placeholder="请输入设备序列号"
                clearable
              />
            </el-form-item>
            <el-form-item label="品牌" class="search-item">
              <el-input
                v-model="searchForm.brand"
                placeholder="请输入品牌"
                clearable
              />
            </el-form-item>
            <el-form-item label="操作系统" class="search-item">
              <el-select
                v-model="searchForm.deviceSys"
                placeholder="请选择操作系统"
                clearable
              >
                <el-option label="Android" value="android" />
                <el-option label="iOS" value="ios" />
                <el-option label="Harmony" value="harmony" />
              </el-select>
            </el-form-item>
          </div>
          <div class="search-row">
            <el-form-item label="系统版本" class="search-item">
              <el-input
                v-model="searchForm.osVersion"
                placeholder="请输入系统版本"
                clearable
              />
            </el-form-item>
          </div>
          
          <div class="button-group">
            <el-button type="primary" @click="handleSearch">搜索</el-button>
            <el-button @click="handleReset">重置</el-button>
          </div>
        </el-form>
      </div>

      <!-- 分隔线 -->
      <el-divider />

      <!-- 表格区域 -->
      <div class="table-section">
        <el-table
          v-loading="loading"
          :data="deviceList"
          :cell-style="{ 'text-align': 'center' }" 
          border
          stripe
          style="width: 100%"
          :header-cell-style="headerCellStyle"
        >
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="name" label="设备名称" min-width="150" />
          <el-table-column prop="serial" label="设备序列号" min-width="200" />
          <el-table-column prop="brand" label="品牌" width="120" />
          <el-table-column prop="model" label="型号" width="120" />
          <el-table-column prop="deviceSys" label="操作系统" width="100">
            <template #default="scope">
              <el-tag
                :type="getDeviceSysTagType(scope.row.deviceSys)"
                size="small"
              >
                {{ getDeviceSysLabel(scope.row.deviceSys) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="osVersion" label="系统版本" width="120" />
          <el-table-column label="屏幕尺寸" width="120">
            <template #default="scope">
                {{ scope.row.width }} x {{ scope.row.height }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="scope">
              <el-tag
                :type="scope.row.status === 1 ? 'success' : 'danger'"
                size="small"
              >
                {{ scope.row.status === 1 ? '可用' : '不可用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="holder" label="持有者" width="120">
            <template #default="scope">
                {{ scope.row.holder === "null" ? '' : scope.row.holder }}
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" width="180" />
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="scope">
              <el-button
                type="primary"
                size="small"
                :disabled="(scope.row.status !== 1 || scope.row.holder !== null)"
                @click="handleHoldDevice(scope.row)"
              >
                占用
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- 分页 -->
        <DefaultPagination
          v-model:page="pagination.pageNo"
          v-model:size="pagination.pageSize"
          :total="pagination.total"
          @change="handlePaginationChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { deviceApi } from '@/api/device.js'
import DefaultPagination from '@/components/Pagination.vue'

export default {
  name: 'DeviceList',
  components: {
    DefaultPagination
  },
  setup() {
    const router = useRouter()
    
    // 响应式数据
    const loading = ref(false)
    const deviceList = ref([])

    // 搜索表单
    const searchForm = reactive({
      name: '',
      serial: '',
      brand: '',
      deviceSys: '',
      osVersion: ''
    })

    // 分页信息
    const pagination = reactive({
      pageNo: 1,
      pageSize: 10,
      total: 0
    })

    // 获取设备列表
    const getDeviceList = async () => {
      loading.value = true
      try {
        const params = {
          ...searchForm,
          pageNo: pagination.pageNo,
          pageSize: pagination.pageSize
        }
        const data = (await deviceApi.getDeviceList(params)).data
        deviceList.value = data.list
        pagination.total = data.total

      } catch (error) {
        ElMessage.error('获取设备列表失败')
      } finally {
        loading.value = false
      }
    }

    // 搜索
    const handleSearch = () => {
      pagination.pageNo = 1
      getDeviceList()
    }

    // 重置搜索
    const handleReset = () => {
      Object.keys(searchForm).forEach(key => {
        searchForm[key] = ''
      })
      pagination.pageNo = 1
      getDeviceList()
    }

    // 分页改变事件统一处理
    const handlePaginationChange = (event) => {
      if (event.type === 'size') {
        // 改变每页数量时重置到第一页
        pagination.pageNo = 1
      }
      // 刷新数据（页码已通过双向绑定自动更新）
      getDeviceList()
    }

    // 根据平台类型获取对应路由名称
    const getDeviceRouteName = (deviceSys) => {
      const routeMap = {
        'android': 'AndroidDevice',
        'ios': 'IOSDevice',
        // 'harmony': 'HarmonyDevice',
      }
      return routeMap[deviceSys] || null
    }

    // 占用设备并跳转到对应平台的详情页
    const handleHoldDevice = async (row) => {
      const routeName = getDeviceRouteName(row.deviceSys)
      if (!routeName) {
        ElMessage.warning(`暂不支持 ${getDeviceSysLabel(row.deviceSys)} 设备的在线操作`)
        return
      }

      const res = await deviceApi.deviceHold({
        id: row.id,
        holder: "seeker"
      })
      if (res.code !== 0) {
        ElMessage.error('设备占用失败')
        getDeviceList()
      } else {
        try {
          router.push({
            name: routeName,
            params: { id: row.id },
            query: {
              serial: row.serial,
              name: row.name,
              deviceSys: row.deviceSys
            }
          })
        } catch (error) {
          ElMessage.error('占用失败' + error)
        }
      }
    }

    // 获取操作系统标签类型
    const getDeviceSysTagType = (deviceSys) => {
      const typeMap = {
        'android': 'success',
        'ios': 'primary',
        'harmony': 'warning'
      }
      return typeMap[deviceSys] || 'info'
    }

    // 获取操作系统标签文本
    const getDeviceSysLabel = (deviceSys) => {
      const labelMap = {
        'android': 'Android',
        'ios': 'iOS',
        'harmony': 'Harmony'
      }
      return labelMap[deviceSys] || deviceSys
    }

    // 表头样式
    const headerCellStyle = () => {
      return {
        textAlign: 'center',
        color: '#000000',
        fontWeight: '600',
        backgroundColor: '#f5f7fa'
      }
    }

    // 组件挂载时获取数据
    onMounted(() => {
      getDeviceList()
    })

    return {
      loading,
      deviceList,
      searchForm,
      pagination,
      headerCellStyle,
      handleSearch,
      handleReset,
      handlePaginationChange,
      handleHoldDevice,
      getDeviceSysTagType,
      getDeviceSysLabel
    }
  }
}
</script>

<style scoped>
.device-list {
  padding: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
}

.main-card {
  flex: 1;
  display: flex;
  flex-direction: column;
  margin: 0;
  border-radius: 0;
  border: none;
  box-shadow: none;
}

.search-section {
  padding: 20px 20px 0 20px;
}

.search-form {
  padding: 0;
}

.search-row {
  display: flex;
  flex-wrap: wrap;
  margin-bottom: 16px;
  align-items: center;
  width: 100%;
}

.search-item {
  margin-bottom: 0;
  margin-right: 0;
  flex: 0 0 25%;
  padding-right: 16px;
  box-sizing: border-box;
}

.search-item .el-input,
.search-item .el-select {
  width: 100%;
}

.search-form .el-form-item__label {
  font-weight: 500;
  color: #606266;
  text-align: center;
  padding-right: 10px;
  width: 68px !important;
  min-width: 68px;
}

.button-group {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 8px;
}

.button-group .el-button {
  margin: 0;
  padding: 8px 16px;
}

.table-section {
  flex: 1;
  padding: 0 20px 20px 20px;
  display: flex;
  flex-direction: column;
}

.table-section .el-table {
  flex: 1;
}

/* 分隔线样式 */
.el-divider {
  margin: 20px 0;
}

/* 响应式设计 */
@media (max-width: 1200px) {
  .search-item {
    flex: 0 0 33.333%;
  }
}

@media (max-width: 900px) {
  .search-item {
    flex: 0 0 50%;
  }
}

@media (max-width: 768px) {
  .search-section {
    padding: 16px 16px 0 16px;
  }
  
  .table-section {
    padding: 0 16px 16px 16px;
  }
  
  .search-row {
    flex-direction: column;
  }
  
  .search-item {
    flex: none;
    width: 100%;
    padding-right: 0;
    margin-bottom: 10px;
  }
  
  .search-form .el-form-item__label {
    text-align: center;
    padding-right: 5px;
    width: auto !important;
    min-width: auto;
  }
  
  .button-group {
    justify-content: center;
    flex-wrap: wrap;
  }
}
</style> 