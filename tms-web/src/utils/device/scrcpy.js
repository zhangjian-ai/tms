/**
 * scrcpy 二进制控制协议 (v1.24)
 *
 * 通过 scrcpy control socket 向 Android 设备注入输入事件，
 * 绕过 uiautomator2 HTTP RPC，延迟从 200-500ms 降至 10-30ms。
 *
 * 协议文档: https://github.com/Genymobile/scrcpy/blob/master/doc/control.md
 */

// ======================== 控制消息类型 ========================

export const ControlType = Object.freeze({
  INJECT_KEYCODE: 0,
  INJECT_TEXT: 1,
  INJECT_TOUCH_EVENT: 2,
  INJECT_SCROLL_EVENT: 3,
  BACK_OR_SCREEN_ON: 4,
  EXPAND_NOTIFICATION_PANEL: 5,
  EXPAND_SETTINGS_PANEL: 6,
  COLLAPSE_PANELS: 7,
  GET_CLIPBOARD: 8,
  SET_CLIPBOARD: 9,
  SET_SCREEN_POWER_MODE: 10,
  ROTATE_DEVICE: 11
})

// ======================== 触摸动作 ========================

export const Action = Object.freeze({
  DOWN: 0,
  UP: 1,
  MOVE: 2
})

// ======================== Android KeyCode ========================

export const KeyCode = Object.freeze({
  HOME: 3,
  BACK: 4,
  VOLUME_UP: 24,
  VOLUME_DOWN: 25,
  POWER: 26,
  MENU: 82,
  APP_SWITCH: 187,
  DEL: 67,
  ENTER: 66
})

// 默认虚拟触摸指针 ID
const VIRTUAL_TOUCH_ID = BigInt('0x1234567887654321') // eslint-disable-line no-undef

// ======================== 包构造函数 ========================

/**
 * 构造触摸事件包 (28 bytes)
 *
 * 布局: type(1) + action(1) + pointerId(8) + x(4) + y(4)
 *       + screenW(2) + screenH(2) + pressure(2) + buttons(4)
 */
export function buildTouchPacket (action, x, y, screenWidth, screenHeight) {
  const buf = new ArrayBuffer(28)
  const v = new DataView(buf)
  let o = 0
  v.setUint8(o, ControlType.INJECT_TOUCH_EVENT); o += 1
  v.setUint8(o, action); o += 1
  v.setBigInt64(o, VIRTUAL_TOUCH_ID); o += 8
  v.setInt32(o, clamp(x, 0, screenWidth)); o += 4
  v.setInt32(o, clamp(y, 0, screenHeight)); o += 4
  v.setUint16(o, screenWidth); o += 2
  v.setUint16(o, screenHeight); o += 2
  v.setUint16(o, action === Action.UP ? 0 : 0xFFFF); o += 2
  v.setInt32(o, action === Action.UP ? 0 : 1); o += 4  // buttons
  return buf
}

/**
 * 构造按键事件包 (14 bytes)
 *
 * 布局: type(1) + action(1) + keycode(4) + repeat(4) + metaState(4)
 */
export function buildKeycodePacket (action, keycode, repeat = 0, metaState = 0) {
  const buf = new ArrayBuffer(14)
  const v = new DataView(buf)
  let o = 0
  v.setUint8(o, ControlType.INJECT_KEYCODE); o += 1
  v.setUint8(o, action); o += 1
  v.setInt32(o, keycode); o += 4
  v.setInt32(o, repeat); o += 4
  v.setInt32(o, metaState); o += 4
  return buf
}

/**
 * 构造文本注入包 (5 + textLength bytes)
 *
 * 布局: type(1) + length(4) + utf8Bytes(n)
 */
export function buildTextPacket (text) {
  const encoder = new TextEncoder()
  const textBytes = encoder.encode(text)
  const buf = new ArrayBuffer(5 + textBytes.length)
  const v = new DataView(buf)
  v.setUint8(0, ControlType.INJECT_TEXT)
  v.setInt32(1, textBytes.length)
  new Uint8Array(buf, 5).set(textBytes)
  return buf
}

/**
 * 构造滚动事件包 (25 bytes)
 *
 * 布局: type(1) + x(4) + y(4) + screenW(2) + screenH(2) + hScroll(4) + vScroll(4) + buttons(4)
 */
export function buildScrollPacket (x, y, screenWidth, screenHeight, hScroll, vScroll) {
  const buf = new ArrayBuffer(25)
  const v = new DataView(buf)
  let o = 0
  v.setUint8(o, ControlType.INJECT_SCROLL_EVENT); o += 1
  v.setInt32(o, clamp(x, 0, screenWidth)); o += 4
  v.setInt32(o, clamp(y, 0, screenHeight)); o += 4
  v.setUint16(o, screenWidth); o += 2
  v.setUint16(o, screenHeight); o += 2
  v.setInt32(o, hScroll); o += 4
  v.setInt32(o, vScroll); o += 4
  v.setInt32(o, 0); o += 4  // buttons
  return buf
}

// ======================== 控制器类 ========================

/**
 * ScrcpyController —— 面向组件的高层控制接口
 *
 * 封装了 WebSocket 发送、分辨率管理和坐标转换，
 * 组件只需调用 touch / key / text 等语义化方法。
 */
export class ScrcpyController {
  constructor () {
    this._ws = null
    this._width = 0
    this._height = 0
  }

  /** 绑定用于发送控制指令的 WebSocket（即 scrcpyWs） */
  bind (ws) {
    this._ws = ws
  }

  /** 解绑 */
  unbind () {
    this._ws = null
  }

  /** 设置 scrcpy 返回的设备分辨率（经 max_size 缩放后的值） */
  setResolution (width, height) {
    this._width = width
    this._height = height
  }

  get width () { return this._width }
  get height () { return this._height }
  get ready () { return !!(this._ws && this._width && this._height) }

  // ---- 触摸 ----

  touchDown (x, y) { this._sendTouch(Action.DOWN, x, y) }
  touchMove (x, y) { this._sendTouch(Action.MOVE, x, y) }
  touchUp (x, y) { this._sendTouch(Action.UP, x, y) }

  // ---- 按键 ----

  /** 发送一次完整按键（DOWN + UP） */
  pressKey (keycode) {
    this._send(buildKeycodePacket(Action.DOWN, keycode))
    this._send(buildKeycodePacket(Action.UP, keycode))
  }

  pressHome () { this.pressKey(KeyCode.HOME) }
  pressBack () { this.pressKey(KeyCode.BACK) }
  pressPower () { this.pressKey(KeyCode.POWER) }
  pressMenu () { this.pressKey(KeyCode.MENU) }
  pressAppSwitch () { this.pressKey(KeyCode.APP_SWITCH) }
  pressVolumeUp () { this.pressKey(KeyCode.VOLUME_UP) }
  pressVolumeDown () { this.pressKey(KeyCode.VOLUME_DOWN) }

  // ---- 文本 ----

  inputText (text) {
    this._send(buildTextPacket(text))
  }

  // ---- 滚动 ----

  scroll (x, y, hScroll, vScroll) {
    if (!this._width || !this._height) return
    this._send(buildScrollPacket(x, y, this._width, this._height, hScroll, vScroll))
  }

  // ---- 坐标转换（显示坐标 → 设备坐标） ----

  /**
   * 将页面上 video 元素内的相对坐标转换为 scrcpy 设备坐标
   * @param {number} relativeX  相对于 video 左上角的 px
   * @param {number} relativeY  相对于 video 左上角的 px
   * @param {number} displayWidth  video 元素的渲染宽度 px
   * @param {number} displayHeight video 元素的渲染高度 px
   * @returns {{ x: number, y: number } | null}
   */
  toDeviceCoords (relativeX, relativeY, displayWidth, displayHeight) {
    if (!this._width || !this._height) return null
    return {
      x: Math.round(relativeX * this._width / displayWidth),
      y: Math.round(relativeY * this._height / displayHeight)
    }
  }

  // ---- 内部方法 ----

  _sendTouch (action, x, y) {
    if (!this._width || !this._height) return
    this._send(buildTouchPacket(action, x, y, this._width, this._height))
  }

  _send (packet) {
    if (packet && this._ws && this._ws.readyState === WebSocket.OPEN) {
      this._ws.send(packet)
    }
  }
}

// ======================== 工具函数 ========================

function clamp (value, min, max) {
  return Math.max(min, Math.min(value, max))
}
