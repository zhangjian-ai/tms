<template>
  <div class="product-management">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-left">
        <h2>产品管理</h2>
        <p class="page-description">管理测试产品，按产品组织测试用例</p>
      </div>
      <div class="header-right">
        <el-button @click="fetchModules">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button type="primary" @click="handleAddProduct">
          <el-icon><Plus /></el-icon>
          新增产品
        </el-button>
      </div>
    </div>

    <!-- 产品列表 -->
    <el-card class="content-card">
      <el-table
        v-loading="loading"
        :data="productList"
        stripe
        border
        style="width: 100%"
      >
        <el-table-column prop="id" label="ID" width="80" align="center" />
        <el-table-column prop="name" label="产品名称" min-width="200" />
        <el-table-column prop="createTime" label="创建时间" width="180" align="center">
          <template #default="{ row }">
            {{ formatDateTime(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column prop="updateTime" label="更新时间" width="180" align="center">
          <template #default="{ row }">
            {{ formatDateTime(row.updateTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="handleEditProduct(row)">
              编辑
            </el-button>
            <el-button type="success" link @click="handleManageModules(row)">
              模块管理
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增/编辑产品对话框 -->
    <el-dialog
      v-model="productDialogVisible"
      :title="isEditProduct ? '编辑产品' : '新增产品'"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="productFormRef"
        :model="productForm"
        :rules="productRules"
        label-width="100px"
      >
        <el-form-item label="产品名称" prop="name">
          <el-input v-model="productForm.name" placeholder="请输入产品名称" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="productDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitProductForm" :loading="submitLoading">
          确定
        </el-button>
      </template>
    </el-dialog>

    <!-- 模块管理对话框 -->
    <el-dialog
      v-model="moduleDialogVisible"
      :title="`模块管理 - ${currentProduct?.name || ''}`"
      width="700px"
      :close-on-click-modal="false"
    >
      <div class="module-dialog-content">
        <!-- 模块树 -->
        <el-tree
          ref="moduleTreeRef"
          :data="moduleTreeData"
          :props="treeProps"
          node-key="id"
          default-expand-all
          :expand-on-click-node="false"
          class="module-tree"
        >
          <template #default="{ data }">
            <div class="tree-node">
              <span class="node-label">
                <el-tag v-if="data.isProduct === 1" type="primary" size="small">产品</el-tag>
                <el-tag v-else type="info" size="small">模块</el-tag>
                {{ data.name }}
              </span>
              <span class="node-actions">
                <el-button type="primary" link size="small" @click.stop="handleAddModule(data)">
                  新增子模块
                </el-button>
                <el-button type="warning" link size="small" @click.stop="handleEditModule(data)">
                  编辑
                </el-button>
                <el-button 
                  v-if="data.isProduct !== 1" 
                  type="danger" 
                  link 
                  size="small" 
                  @click.stop="handleDeleteModule(data)"
                >
                  删除
                </el-button>
              </span>
            </div>
          </template>
        </el-tree>
        
        <el-empty v-if="moduleTreeData.length === 0 && !loading" description="暂无模块数据" />
      </div>
    </el-dialog>

    <!-- 新增/编辑模块对话框 -->
    <el-dialog
      v-model="moduleFormDialogVisible"
      :title="isEditModule ? '编辑模块' : '新增子模块'"
      width="500px"
      :close-on-click-modal="false"
      append-to-body
    >
      <el-form
        ref="moduleFormRef"
        :model="moduleForm"
        :rules="moduleRules"
        label-width="100px"
      >
        <el-form-item label="父模块" v-if="!isEditModule">
          <el-input :value="parentModuleName" disabled />
        </el-form-item>
        <el-form-item label="模块名称" prop="name">
          <el-input v-model="moduleForm.name" placeholder="请输入模块名称" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="moduleFormDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitModuleForm" :loading="submitLoading">
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { moduleApi } from '@/api/module.js'

export default {
  name: 'ProductManagement',
  components: {
    Plus,
    Refresh
  },
  setup() {
    // 数据状态
    const loading = ref(false)
    const submitLoading = ref(false)
    const allModules = ref([])
    const productList = ref([])
    
    // 产品对话框
    const productDialogVisible = ref(false)
    const isEditProduct = ref(false)
    const productFormRef = ref(null)
    const productForm = ref({
      id: null,
      name: ''
    })
    const productRules = {
      name: [{ required: true, message: '请输入产品名称', trigger: 'blur' }]
    }
    
    // 模块管理对话框
    const moduleDialogVisible = ref(false)
    const currentProduct = ref(null)
    const moduleTreeRef = ref(null)
    const moduleTreeData = ref([])
    const treeProps = {
      children: 'children',
      label: 'name'
    }
    
    // 模块表单对话框
    const moduleFormDialogVisible = ref(false)
    const isEditModule = ref(false)
    const moduleFormRef = ref(null)
    const parentModule = ref(null)
    const moduleForm = ref({
      id: null,
      name: '',
      parentId: null,
      isProduct: 0
    })
    const moduleRules = {
      name: [{ required: true, message: '请输入模块名称', trigger: 'blur' }]
    }
    
    // 父模块名称
    const parentModuleName = computed(() => {
      if (parentModule.value) {
        return parentModule.value.name
      }
      return currentProduct.value?.name || ''
    })
    
    // 格式化日期时间
    const formatDateTime = (dateTime) => {
      if (!dateTime) return '-'
      const date = new Date(dateTime)
      const year = date.getFullYear()
      const month = String(date.getMonth() + 1).padStart(2, '0')
      const day = String(date.getDate()).padStart(2, '0')
      const hours = String(date.getHours()).padStart(2, '0')
      const minutes = String(date.getMinutes()).padStart(2, '0')
      const seconds = String(date.getSeconds()).padStart(2, '0')
      return `${year}-${month}-${day} ${hours}:${minutes}:${seconds}`
    }
    
    // 获取所有模块数据
    const fetchModules = async () => {
      loading.value = true
      try {
        const res = await moduleApi.getModuleList()
        if (res.code === 0) {
          allModules.value = res.data || []
          // 筛选出产品列表（isProduct === 1）
          productList.value = allModules.value.filter(item => item.isProduct === 1)
        } else {
          ElMessage.error(res.msg || '获取模块列表失败')
        }
      } catch (error) {
        console.error('获取模块列表失败:', error)
        ElMessage.error('获取模块列表失败')
      } finally {
        loading.value = false
      }
    }
    
    // 构建树形结构数据
    const buildModuleTree = (productId) => {
      const modules = allModules.value
      
      // 找到当前产品
      const product = modules.find(m => m.id === productId)
      if (!product) return []
      
      // 递归构建子模块
      const buildChildren = (parentId) => {
        const children = modules.filter(m => m.parentId === parentId)
        return children.map(child => ({
          ...child,
          children: buildChildren(child.id)
        }))
      }
      
      // 返回以产品为根节点的树
      return [{
        ...product,
        children: buildChildren(product.id)
      }]
    }
    
    // 新增产品
    const handleAddProduct = () => {
      isEditProduct.value = false
      productForm.value = {
        id: null,
        name: ''
      }
      productDialogVisible.value = true
    }
    
    // 编辑产品
    const handleEditProduct = (row) => {
      isEditProduct.value = true
      productForm.value = {
        id: row.id,
        name: row.name
      }
      productDialogVisible.value = true
    }
    
    // 提交产品表单
    const submitProductForm = async () => {
      if (!productFormRef.value) return
      
      await productFormRef.value.validate(async (valid) => {
        if (valid) {
          submitLoading.value = true
          try {
            let res
            if (isEditProduct.value) {
              // 更新产品
              res = await moduleApi.updateModule({
                id: productForm.value.id,
                name: productForm.value.name,
                isProduct: 1
              })
            } else {
              // 新增产品
              res = await moduleApi.addModule({
                name: productForm.value.name,
                parentId: null,
                isProduct: 1
              })
            }
            
            if (res.code === 0) {
              ElMessage.success(isEditProduct.value ? '更新成功' : '新增成功')
              productDialogVisible.value = false
              fetchModules()
            } else {
              ElMessage.error(res.msg || '操作失败')
            }
          } catch (error) {
            console.error('提交产品表单失败:', error)
            ElMessage.error('操作失败')
          } finally {
            submitLoading.value = false
          }
        }
      })
    }
    
    // 打开模块管理对话框
    const handleManageModules = (product) => {
      currentProduct.value = product
      moduleTreeData.value = buildModuleTree(product.id)
      moduleDialogVisible.value = true
    }
    
    // 新增子模块
    const handleAddModule = (parentData) => {
      isEditModule.value = false
      parentModule.value = parentData
      moduleForm.value = {
        id: null,
        name: '',
        parentId: parentData ? parentData.id : currentProduct.value.id,
        isProduct: 0
      }
      moduleFormDialogVisible.value = true
    }
    
    // 编辑模块
    const handleEditModule = (data) => {
      isEditModule.value = true
      parentModule.value = null
      moduleForm.value = {
        id: data.id,
        name: data.name,
        parentId: data.parentId,
        isProduct: data.isProduct
      }
      moduleFormDialogVisible.value = true
    }
    
    // 删除模块
    const handleDeleteModule = async (data) => {
      // 检查是否有子模块
      const hasChildren = allModules.value.some(m => m.parentId === data.id)
      
      const confirmMessage = hasChildren 
        ? `确定要删除模块「${data.name}」吗？该模块下的所有子模块也将被删除！`
        : `确定要删除模块「${data.name}」吗？`
      
      try {
        await ElMessageBox.confirm(confirmMessage, '删除确认', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        })
        
        const res = await moduleApi.deleteModule({ id: data.id })
        if (res.code === 0) {
          ElMessage.success('删除成功')
          // 刷新数据
          await fetchModules()
          // 重新构建树
          if (currentProduct.value) {
            moduleTreeData.value = buildModuleTree(currentProduct.value.id)
          }
        } else {
          ElMessage.error(res.msg || '删除失败')
        }
      } catch (error) {
        if (error !== 'cancel') {
          console.error('删除模块失败:', error)
          ElMessage.error('删除失败')
        }
      }
    }
    
    // 提交模块表单
    const submitModuleForm = async () => {
      if (!moduleFormRef.value) return
      
      await moduleFormRef.value.validate(async (valid) => {
        if (valid) {
          submitLoading.value = true
          try {
            let res
            if (isEditModule.value) {
              // 更新模块
              res = await moduleApi.updateModule({
                id: moduleForm.value.id,
                name: moduleForm.value.name
              })
            } else {
              // 新增子模块
              res = await moduleApi.addModule({
                name: moduleForm.value.name,
                parentId: moduleForm.value.parentId,
                isProduct: 0
              })
            }
            
            if (res.code === 0) {
              ElMessage.success(isEditModule.value ? '更新成功' : '新增成功')
              moduleFormDialogVisible.value = false
              // 刷新数据
              await fetchModules()
              // 重新构建树
              if (currentProduct.value) {
                moduleTreeData.value = buildModuleTree(currentProduct.value.id)
              }
            } else {
              ElMessage.error(res.msg || '操作失败')
            }
          } catch (error) {
            console.error('提交模块表单失败:', error)
            ElMessage.error('操作失败')
          } finally {
            submitLoading.value = false
          }
        }
      })
    }
    
    // 初始化
    onMounted(() => {
      fetchModules()
    })
    
    return {
      loading,
      submitLoading,
      productList,
      productDialogVisible,
      isEditProduct,
      productFormRef,
      productForm,
      productRules,
      moduleDialogVisible,
      currentProduct,
      moduleTreeRef,
      moduleTreeData,
      treeProps,
      moduleFormDialogVisible,
      isEditModule,
      moduleFormRef,
      parentModule,
      moduleForm,
      moduleRules,
      parentModuleName,
      formatDateTime,
      handleAddProduct,
      handleEditProduct,
      submitProductForm,
      handleManageModules,
      handleAddModule,
      handleEditModule,
      handleDeleteModule,
      submitModuleForm
    }
  }
}
</script>

<style scoped>
.product-management {
  padding: 16px;
  min-height: calc(100vh - 120px);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 16px;
  padding: 20px 24px;
  background-color: white;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.header-left h2 {
  margin: 0 0 8px 0;
  color: #303133;
  font-size: 20px;
  font-weight: 600;
}

.page-description {
  margin: 0;
  color: #909399;
  font-size: 14px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.content-card {
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
}

.module-dialog-content {
  min-height: 300px;
}

.module-tree {
  background: transparent;
}

.tree-node {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-right: 8px;
  font-size: 14px;
}

.node-label {
  display: flex;
  align-items: center;
  gap: 8px;
}

.node-actions {
  display: none;
}

.tree-node:hover .node-actions {
  display: flex;
  gap: 4px;
}

:deep(.el-tree-node__content) {
  height: 36px;
}

:deep(.el-tree-node__content:hover) {
  background-color: #f5f7fa;
}
</style>
