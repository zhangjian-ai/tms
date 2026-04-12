<template>
  <div :class="['pagination-container', `align-${align}`]">
    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="pageSizes"
      :total="total"
      :layout="layout"
      :background="background"
      @size-change="handleSizeChange"
      @current-change="handleCurrentChange"
    />
  </div>
</template>

<script>
import { computed } from 'vue'

export default {
  name: 'DefaultPagination',
  props: {
    // 当前页码
    page: {
      type: Number,
      default: 1
    },
    // 每页显示数量
    size: {
      type: Number,
      default: 10
    },
    // 总数
    total: {
      type: Number,
      required: true
    },
    // 每页显示个数选择器的选项设置
    pageSizes: {
      type: Array,
      default: () => [10, 20, 50, 100]
    },
    // 组件布局，子组件名用逗号分隔
    layout: {
      type: String,
      default: 'total, sizes, prev, pager, next, jumper'
    },
    // 是否为分页按钮添加背景色
    background: {
      type: Boolean,
      default: true
    },
    // 分页对齐方式
    align: {
      type: String,
      default: 'right',
      validator: (value) => ['left', 'center', 'right'].includes(value)
    }
  },
  emits: ['update:page', 'update:size', 'change'],
  setup(props, { emit }) {
    // 双向绑定当前页码
    const currentPage = computed({
      get: () => props.page,
      set: (value) => emit('update:page', value)
    })

    // 双向绑定每页数量
    const pageSize = computed({
      get: () => props.size,
      set: (value) => emit('update:size', value)
    })

    // 每页数量改变事件
    const handleSizeChange = (size) => {
      emit('update:size', size)
      emit('change', { type: 'size', value: size })
    }

    // 当前页改变事件
    const handleCurrentChange = (page) => {
      emit('update:page', page)
      emit('change', { type: 'page', value: page })
    }

    return {
      currentPage,
      pageSize,
      handleSizeChange,
      handleCurrentChange
    }
  }
}
</script>

<style scoped>
.pagination-container {
  margin-top: 20px;
  display: flex;
}

.pagination-container.align-left {
  justify-content: flex-start;
}

.pagination-container.align-center {
  justify-content: center;
}

.pagination-container.align-right {
  justify-content: flex-end;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .pagination-container {
    justify-content: center;
  }
  
  .pagination-container :deep(.el-pagination) {
    flex-wrap: wrap;
    justify-content: center;
  }
}
</style> 