import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useTestGenStore = defineStore('testgen', () => {
  const task = ref(null)
  const treeData = ref(null)
  const chatMessages = ref([])
  const wsConnected = ref(false)
  const generating = ref(false)

  function setTask(t) { task.value = t }
  function setTreeData(d) { treeData.value = d }
  function addChatMessage(msg) { chatMessages.value.push(msg) }
  function setChatHistory(list) { chatMessages.value = list || [] }
  function reset() {
    task.value = null
    treeData.value = null
    chatMessages.value = []
    wsConnected.value = false
    generating.value = false
  }

  return { task, treeData, chatMessages, wsConnected, generating, setTask, setTreeData, addChatMessage, setChatHistory, reset }
})
