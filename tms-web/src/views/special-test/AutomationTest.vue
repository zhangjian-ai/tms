<template>
  <div class="automation-test">
    <div class="page-header">
      <h2>自动化测试</h2>
      <p class="page-description">配置和执行自动化测试脚本</p>
    </div>
    
    <div class="content-placeholder">
      <el-empty description="页面内容开发中...">
        <el-button type="primary">创建自动化任务</el-button>
      </el-empty>
    </div>
  </div>
</template>

<script>
export default {
  name: 'AutomationTest',
  setup() {
    return {}
  }
}
</script>

<style scoped>
.automation-test {
  padding: 24px;
  background-color: white;
  margin: 16px;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
  min-height: calc(100vh - 120px);
}

.page-header {
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid #e6e6e6;
}

.page-header h2 {
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

.content-placeholder {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 400px;
}
</style>
