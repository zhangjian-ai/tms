<template>
  <div class="file-convert">
    <div class="convert-card">
      <h3 class="title">用例文件转换</h3>
      <p class="desc">Excel(.xlsx) 与 XMind(.xmind) 互转，全程在浏览器本地完成，文件不会上传服务器。</p>

      <el-upload
        drag
        action="#"
        :auto-upload="false"
        :show-file-list="false"
        :on-change="onFileChange"
        accept=".xlsx,.xmind"
      >
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <div class="upload-text">将文件拖到此处，或<em>点击选择</em></div>
        <template #tip>
          <div class="upload-tip">支持 .xlsx / .xmind，单个文件；不支持老的二进制 .xls</div>
        </template>
      </el-upload>

      <div v-if="file" class="file-info">
        <div class="file-row">
          <el-icon><Document /></el-icon>
          <span class="file-name">{{ file.name }}</span>
          <el-tag size="small" :type="direction ? 'success' : 'danger'">
            {{ direction ? directionLabel : '不支持的类型' }}
          </el-tag>
        </div>
        <div class="actions">
          <el-button
            type="primary"
            :loading="converting"
            :disabled="!direction"
            @click="convert"
          >开始转换</el-button>
          <el-button text @click="reset">重新选择</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled, Document } from '@element-plus/icons-vue'
import { excelToXmind, xmindToExcel } from '@/utils/caseConvert'

export default {
  name: 'FileConvert',
  components: { UploadFilled, Document },
  setup() {
    const file = ref(null)
    const converting = ref(false)

    // 根据扩展名判定转换方向
    const direction = computed(() => {
      if (!file.value) return null
      const n = file.value.name.toLowerCase()
      if (n.endsWith('.xlsx')) return 'excel2xmind'
      if (n.endsWith('.xmind')) return 'xmind2excel'
      return null
    })
    const directionLabel = computed(() =>
      direction.value === 'excel2xmind' ? 'Excel → XMind' : 'XMind → Excel'
    )

    function onFileChange(uploadFile) {
      file.value = uploadFile.raw || null
    }

    function reset() {
      file.value = null
    }

    function download(blob, name) {
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = name
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      // 稍后释放，确保下载已开始
      setTimeout(() => URL.revokeObjectURL(url), 2000)
    }

    async function convert() {
      if (!file.value || !direction.value) return
      converting.value = true
      try {
        const buffer = await file.value.arrayBuffer()
        const result =
          direction.value === 'excel2xmind'
            ? excelToXmind(buffer, file.value.name)
            : xmindToExcel(buffer, file.value.name)
        download(result.blob, result.fileName)
        ElMessage.success('转换完成，已开始下载：' + result.fileName)
      } catch (e) {
        console.error(e)
        ElMessage.error('转换失败：' + (e && e.message ? e.message : '未知错误'))
      } finally {
        converting.value = false
      }
    }

    return { file, converting, direction, directionLabel, onFileChange, reset, convert }
  },
}
</script>

<style scoped>
.file-convert {
  padding: 24px;
  display: flex;
  justify-content: center;
}
.convert-card {
  width: 100%;
  max-width: 620px;
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 28px;
}
.title { margin: 0 0 6px; font-size: 18px; }
.desc { margin: 0 0 20px; color: #909399; font-size: 13px; }
.upload-icon { font-size: 48px; color: #c0c4cc; }
.upload-text { color: #606266; font-size: 14px; }
.upload-text em { color: var(--el-color-primary); font-style: normal; }
.upload-tip { color: #909399; font-size: 12px; margin-top: 8px; }
.file-info {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px dashed #ebeef5;
}
.file-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}
.file-name {
  font-size: 14px;
  color: #303133;
  word-break: break-all;
  flex: 1;
}
.actions { display: flex; align-items: center; gap: 8px; }
</style>
