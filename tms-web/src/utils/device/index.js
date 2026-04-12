/**
 * 设备控制工具模块
 *
 * 按平台组织，统一从此入口导出：
 *   - Android: 基于 scrcpy control socket 的实时控制
 *   - iOS / HarmonyOS: 后续扩展，各自实现对应的控制器类
 *
 * 使用示例:
 *   import { ScrcpyController, Action, KeyCode } from '@/utils/device'
 */

export {
  ScrcpyController,
  Action,
  KeyCode,
  ControlType,
  buildTouchPacket,
  buildKeycodePacket,
  buildTextPacket,
  buildScrollPacket
} from './scrcpy.js'
