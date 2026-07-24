import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useTestGenStore = defineStore('testgen', () => {
  const task = ref(null)
  const treeData = ref(null)
  const wsConnected = ref(false)

  function setTask(t) { task.value = t }
  function setTreeData(d) { treeData.value = d }
  function reset() {
    task.value = null
    treeData.value = null
    wsConnected.value = false
  }

  return { task, treeData, wsConnected, setTask, setTreeData, reset }
})
