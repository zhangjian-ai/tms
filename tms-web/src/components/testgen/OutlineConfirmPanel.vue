<template>
  <div class="outline-confirm-panel">
    <div class="panel-header">
      <h3>请确认或调整需求章节大纲</h3>
      <p class="hint">
        大纲是对需求文档的章节摘要，每条章节会单独喂给 AI 提取测试点。可在此增删章节、修改摘要。
        <el-button type="primary" link size="small" @click="guideOpen = !guideOpen">
          {{ guideOpen ? '收起说明' : '查看说明' }}
        </el-button>
      </p>

      <el-collapse-transition>
        <div v-show="guideOpen" class="guide-box">
          <div class="guide-title">编辑规范</div>
          <ul class="guide-list">
            <li>
              <strong>需求摘要：</strong>
              一句话概括本次需求的总体目标，影响后续 AI 提点的全局视角。
            </li>
            <li>
              <strong>章节名：</strong>
              直接对应需求文档中的章节标题或主题名（如"体力恢复机制"、"订单匹配流程"），不要自创业务模块概念。
            </li>
            <li>
              <strong>章节摘要：</strong>
              对该章节内容的客观摘要——讲了什么、有哪些规则/流程/配置项；2-4 句话即可，不要扩写或预设模块设计。
            </li>
            <li>
              <strong>增删的影响：</strong>
              新增章节 = AI 会专门为它提取一组测试点；删除章节 = 该章节下的内容不会被覆盖。章节越细，提点越聚焦但耗时更长。
            </li>
            <li>
              <strong>规模建议：</strong>
              章节数量按文档实际章节决定，通常 3-10 个之间；不要硬凑数。
            </li>
          </ul>
        </div>
      </el-collapse-transition>
    </div>

    <div class="panel-body">
      <el-form label-position="top" size="small">
        <el-form-item label="需求摘要">
          <el-input
            v-model="local.summary"
            type="textarea"
            :rows="2"
            placeholder="一句话概括本次需求要实现的目标"
          />
        </el-form-item>

        <div class="modules-section">
          <div class="modules-header">
            <span>章节列表</span>
            <el-button type="primary" link @click="addModule">+ 添加章节</el-button>
          </div>

          <div
            v-for="(m, idx) in local.modules"
            :key="idx"
            class="module-card"
          >
            <div class="module-row">
              <el-input
                v-model="m.name"
                placeholder="章节名（如：体力恢复机制）"
                style="width: 220px"
              />
              <el-input
                v-model="m.scope"
                type="textarea"
                :autosize="{ minRows: 2, maxRows: 6 }"
                placeholder="章节内容摘要（2-4 句话）"
                style="flex: 1; margin-left: 8px"
              />
              <el-button text @click="removeModule(idx)">
                <el-icon><Close /></el-icon>
              </el-button>
            </div>
          </div>
        </div>
      </el-form>
    </div>

    <div class="panel-footer">
      <el-button @click="$emit('cancel')">取消</el-button>
      <el-button type="primary" :loading="loading" @click="onConfirm">
        确认并生成测试点
      </el-button>
    </div>
  </div>
</template>

<script>
import { ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Close } from '@element-plus/icons-vue'

const GUIDE_STORAGE_KEY = 'testgen.outline.guideOpen'

export default {
  name: 'OutlineConfirmPanel',
  components: { Close },
  props: {
    outline: { type: Object, default: () => ({ summary: '', modules: [] }) },
    loading: { type: Boolean, default: false }
  },
  emits: ['confirm', 'cancel'],
  setup(props, { emit }) {
    const local = ref(cloneOutline(props.outline))
    // 默认展开；用户折叠后下次进入保持折叠状态
    const guideOpen = ref(localStorage.getItem(GUIDE_STORAGE_KEY) !== '0')
    watch(guideOpen, (v) => {
      localStorage.setItem(GUIDE_STORAGE_KEY, v ? '1' : '0')
    })

    watch(() => props.outline, (val) => {
      local.value = cloneOutline(val)
    }, { deep: true })

    function cloneOutline(v) {
      const src = v || {}
      return {
        summary: src.summary || '',
        modules: (src.modules || []).map(m => ({
          name: m.name || '',
          scope: m.scope || ''
        }))
      }
    }

    function addModule() {
      local.value.modules.push({ name: '', scope: '' })
    }

    async function removeModule(idx) {
      const m = local.value.modules[idx]
      const name = (m && m.name && m.name.trim()) || '该章节'
      try {
        await ElMessageBox.confirm(
          `确定删除章节「${name}」吗？\n删除后该章节内容不会被 AI 覆盖。`,
          '删除章节',
          { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
        )
        local.value.modules.splice(idx, 1)
      } catch (e) {
        // 用户取消
      }
    }

    function onConfirm() {
      const cleaned = {
        summary: (local.value.summary || '').trim(),
        modules: local.value.modules
          .map(m => ({
            name: (m.name || '').trim(),
            scope: (m.scope || '').trim()
          }))
          .filter(m => m.name)
      }
      emit('confirm', cleaned)
    }

    return {
      local, guideOpen,
      addModule, removeModule,
      onConfirm
    }
  }
}
</script>

<style scoped>
.outline-confirm-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #fff;
}
.panel-header {
  padding: 16px 20px 8px;
  border-bottom: 1px solid #ebeef5;
}
.panel-header h3 { margin: 0 0 4px; font-size: 16px; }
.panel-header .hint { margin: 0; color: #909399; font-size: 12px; display: flex; align-items: center; gap: 4px; }
.guide-box {
  margin-top: 10px;
  padding: 10px 14px;
  background: #f5f7fa;
  border-left: 3px solid #409eff;
  border-radius: 4px;
  font-size: 12px;
  color: #606266;
  line-height: 1.7;
}
.guide-title {
  font-weight: 600;
  color: #303133;
  margin-bottom: 4px;
}
.guide-list {
  margin: 0;
  padding-left: 18px;
}
.guide-list li { margin: 2px 0; }
.guide-list strong { color: #303133; }
.guide-list u { text-decoration: underline; color: #e6a23c; }
.panel-body { flex: 1; overflow: auto; padding: 16px 20px; }
.modules-section { margin-top: 8px; }
.modules-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-weight: 600;
}
.module-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #fafbfc;
}
.module-row { display: flex; align-items: flex-start; gap: 4px; }
.module-row .el-input,
.module-row .el-textarea { vertical-align: top; }
.panel-footer {
  border-top: 1px solid #ebeef5;
  padding: 12px 20px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
