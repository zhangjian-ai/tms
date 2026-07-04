import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 设备会话令牌 store
 *
 * 列表页占用成功后，将本次占用生成的 sessionId 按设备 id 暂存，供详情页在同标签内导航时读取，
 * 从而沿用同一次占用。复制标签页会重建为空的 store，详情页读不到即自行重新占用（占用失败则拒绝投屏）。
 * sessionId 仅存在于内存，绝不写入 URL，避免复制标签时被连带复制。
 */
export const useDeviceSessionStore = defineStore('deviceSession', () => {
  // deviceId(string) -> sessionId
  const sessions = ref({})

  const setSession = (deviceId, sessionId) => {
    sessions.value[String(deviceId)] = sessionId
  }

  const getSession = (deviceId) => {
    return sessions.value[String(deviceId)] || null
  }

  const clearSession = (deviceId) => {
    delete sessions.value[String(deviceId)]
  }

  return { sessions, setSession, getSession, clearSession }
})
