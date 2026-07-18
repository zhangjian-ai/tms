<template>
  <el-card class="info-card app-manager-card">
    <template #header>
      <div class="card-header">
        <h3>应用管理</h3>
        <div class="app-actions">
          <el-button size="small" :loading="installing" @click="triggerInstall">
            <el-icon><UploadFilled /></el-icon>
            {{ installing ? `安装中 ${installProgress}%` : '安装' }}
          </el-button>
          <el-button size="small" :loading="loading" @click="loadApps" title="刷新">
            <el-icon><Refresh /></el-icon>
          </el-button>
        </div>
      </div>
    </template>

    <input ref="fileInputRef" type="file" :accept="accept" style="display:none" @change="onFileChosen" />

    <el-input
      v-model="keyword"
      size="small"
      placeholder="搜索应用名称 / 包名"
      clearable
      class="app-search"
    />

    <div v-loading="loading" class="app-list">
      <el-empty v-if="!loading && filteredApps.length === 0" description="暂无应用" :image-size="60" />
      <div v-for="app in filteredApps" :key="app.id" class="app-item">
        <div class="app-info">
          <div class="app-name">
            {{ app.name }}
            <el-tag v-if="app.system" size="small" type="info">系统</el-tag>
          </div>
          <div class="app-meta">{{ app.id }}<span v-if="app.version"> · v{{ app.version }}</span></div>
        </div>
        <el-button
          v-if="!app.system"
          size="small"
          type="danger"
          plain
          @click="confirmUninstall(app)"
        >
          卸载
        </el-button>
      </div>
    </div>
  </el-card>
</template>

<script setup>
/* global defineProps */
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, UploadFilled } from '@element-plus/icons-vue'
import { listApps, uninstallApp, installApp } from '@/api/deviceApps'

const props = defineProps({
  proxyHost: { type: String, default: '' },
  proxyPort: { type: [String, Number], default: '' },
  serial: { type: String, default: '' },
  platform: { type: String, default: 'android' } // 'android' | 'ios'
})

const apps = ref([])
const loading = ref(false)
const installing = ref(false)
const installProgress = ref(0)
const keyword = ref('')
const fileInputRef = ref(null)

const accept = computed(() => (props.platform === 'ios' ? '.ipa' : '.apk'))

const filteredApps = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return apps.value
  return apps.value.filter(a =>
    (a.name || '').toLowerCase().includes(kw) || (a.id || '').toLowerCase().includes(kw)
  )
})

const ready = () => props.proxyHost && props.proxyPort && props.serial

const loadApps = async () => {
  if (!ready()) return
  loading.value = true
  try {
    apps.value = await listApps(props.proxyHost, props.proxyPort, props.serial)
  } catch (e) {
    ElMessage.error(e.message || '获取应用列表失败')
  } finally {
    loading.value = false
  }
}

const confirmUninstall = async (app) => {
  try {
    await ElMessageBox.confirm(`确定卸载「${app.name}」？`, '卸载确认', { type: 'warning' })
  } catch (_) {
    return
  }
  try {
    await uninstallApp(props.proxyHost, props.proxyPort, props.serial, app.id)
    ElMessage.success('卸载成功')
    loadApps()
  } catch (e) {
    ElMessage.error(e.message || '卸载失败')
  }
}

const triggerInstall = () => {
  if (installing.value) return
  fileInputRef.value && fileInputRef.value.click()
}

const onFileChosen = async (event) => {
  const file = event.target.files && event.target.files[0]
  event.target.value = '' // 允许重复选择同一文件
  if (!file) return
  if (!ready()) {
    ElMessage.error('设备连接信息不完整')
    return
  }
  installing.value = true
  installProgress.value = 0
  try {
    await installApp(props.proxyHost, props.proxyPort, props.serial, file, (p) => { installProgress.value = p })
    ElMessage.success('安装成功')
    loadApps()
  } catch (e) {
    ElMessage.error(e.message || '安装失败')
  } finally {
    installing.value = false
  }
}

onMounted(loadApps)
</script>

<style scoped>
.app-manager-card {
  display: flex;
  flex-direction: column;
}
/* 卡片主体撑满高度，列表区在内部滚动，使面板底边可与投屏底边对齐 */
.app-manager-card :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.app-manager-card .card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.app-actions {
  display: flex;
  gap: 8px;
}
.app-search {
  margin-bottom: 12px;
}
.app-list {
  flex: 1;
  min-height: 120px;
  overflow-y: auto;
}
.app-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 4px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  gap: 8px;
}
.app-info {
  min-width: 0;
  flex: 1;
}
.app-name {
  font-size: 13px;
  color: var(--el-text-color-primary);
  display: flex;
  align-items: center;
  gap: 6px;
}
.app-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
</style>
