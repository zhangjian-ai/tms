import { onMounted, onBeforeUnmount } from 'vue'
import { ElMessageBox } from 'element-plus'

/**
 * 空闲自动释放 + 回页提示：页面隐藏超过 thresholdMs 则释放设备，回页时若设备已断开则弹窗引导回列表。
 *
 * @param {Object} opts
 * @param {() => boolean} opts.isActive        当前是否仍持有/连接设备
 * @param {() => (Promise<any>|any)} opts.release  静默释放
 * @param {() => void} [opts.onReleased]        确认弹窗后的收尾
 * @param {number} [opts.thresholdMs]           离开多久后主动释放，默认 3 分钟
 */
export function useIdleRelease({ isActive, release, onReleased, thresholdMs = 3 * 60 * 1000 }) {
  let timer = null
  let wasActive = false   // 进入隐藏时设备是否连接
  let notified = false    // 是否已弹过提示

  const clearTimer = () => {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  const onHidden = () => {
    if (!isActive()) return
    wasActive = true
    clearTimer()
    timer = setTimeout(async () => {
      timer = null
      if (isActive()) {
        try {
          await release()
        } catch (e) {
          console.error('空闲释放设备失败:', e)
        }
      }
    }, thresholdMs)
  }

  const onVisible = async () => {
    clearTimer()
    if (!wasActive || notified) return
    // 仍连接：无需处理
    if (isActive()) {
      wasActive = false
      return
    }
    // 离开期间设备已被释放 → 提示并引导回列表
    wasActive = false
    notified = true
    try {
      await release() // 兜底清理 web 侧状态
    } catch (e) {
      console.error('回页清理失败:', e)
    }
    try {
      await ElMessageBox.alert('长时间未操作，设备已释放，请返回设备列表重新占用', '提示', {
        confirmButtonText: '返回设备列表',
        type: 'warning'
      })
    } catch (e) {
      // 忽略
    }
    if (onReleased) onReleased()
  }

  const handleVisibility = () => {
    if (document.hidden) onHidden()
    else onVisible()
  }

  onMounted(() => document.addEventListener('visibilitychange', handleVisibility))
  onBeforeUnmount(() => {
    clearTimer()
    document.removeEventListener('visibilitychange', handleVisibility)
  })
}
