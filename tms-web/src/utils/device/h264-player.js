/**
 * WebCodecs H.264 低延迟播放器
 *
 * 复刻原生 scrcpy 的回显模型：解一帧就立刻画一帧、只显示最新帧、
 * 跟不上就丢帧、无播放时钟、无缓冲，延迟不随时间累积。
 *
 * 输入：agent 逐 NAL 转发的裸 Annex-B H.264（每条 WS 消息 = 起始码 + 单个 NAL）。
 * 管线：VideoDecoder.decode(EncodedVideoChunk) → VideoFrame → canvas 2D 立即绘制。
 *
 * 仅支持具备 WebCodecs 的浏览器（Chrome / Edge）。
 */

/* global VideoDecoder, EncodedVideoChunk */

// H.264 NAL 单元类型（nal_unit_type = 头字节 & 0x1F）
const NAL_NON_IDR = 1 // 非 IDR slice（delta 帧）
const NAL_IDR = 5     // IDR slice（关键帧）
const NAL_SPS = 7     // 序列参数集
const NAL_PPS = 8     // 图像参数集

export class WebCodecsPlayer {
  /** 当前浏览器是否支持 WebCodecs 视频解码 */
  static isSupported () {
    return typeof window !== 'undefined' && 'VideoDecoder' in window
  }

  /**
   * @param {HTMLCanvasElement} canvas 渲染目标
   * @param {object} [opts]
   * @param {number} [opts.fps=25]            合成时间戳用的帧率（scrcpy send_frame_meta=false 无真实 PTS）
   * @param {number} [opts.queueThreshold=2]  解码队列积压阈值，超过则丢帧追新
   * @param {(w:number,h:number)=>void} [opts.onResize] 首帧/分辨率变化回调
   * @param {(err:Error)=>void} [opts.onError] 解码错误回调
   */
  constructor (canvas, opts = {}) {
    this.canvas = canvas
    this.ctx = canvas.getContext('2d', { alpha: false, desynchronized: true })

    this.decoder = null
    this.sps = null // 含起始码的最新 SPS 字节（Uint8Array）
    this.pps = null // 含起始码的最新 PPS 字节（Uint8Array）
    this.configured = false
    this.skipUntilKey = true // 首帧前 / 积压时：丢弃 delta 直到下一个关键帧

    this.tsUs = 0
    this.frameDurUs = Math.round(1e6 / (opts.fps || 25))
    this.queueThreshold = opts.queueThreshold || 2
    this.onResize = opts.onResize || null
    this.onError = opts.onError || null
    this.closed = false
  }

  /**
   * 喂入一条 WS 二进制消息（一个完整的 H.264 访问单元，Annex-B）。
   * agent 开启 send_frame_meta 后，一条消息可能含多个 NAL（如配置包 SPS+PPS），
   * 故按起始码拆分后逐个处理。
   * @param {ArrayBuffer|Uint8Array} data
   */
  feed (data) {
    if (this.closed) return
    const bytes = data instanceof Uint8Array ? data : new Uint8Array(data)
    for (const nal of splitNalUnits(bytes)) {
      this._handleNal(nal)
    }
  }

  /** 处理单个 NAL 单元 */
  _handleNal (bytes) {
    const startLen = startCodeLength(bytes)
    if (!startLen || bytes.length <= startLen) return

    const nalType = bytes[startLen] & 0x1F

    switch (nalType) {
      case NAL_SPS:
        this.sps = bytes
        this._maybeConfigure()
        break
      case NAL_PPS:
        this.pps = bytes
        this._maybeConfigure()
        break
      case NAL_IDR:
        this._decodeVcl(bytes, true)
        break
      case NAL_NON_IDR:
        this._decodeVcl(bytes, false)
        break
      default:
        // SEI(6) / AUD(9) 等：不参与分帧，忽略
        break
    }
  }

  /** 释放解码器；close 后 feed 变为 no-op */
  close () {
    this.closed = true
    this._teardownDecoder()
    this.sps = null
    this.pps = null
  }

  // ======================== 内部方法 ========================

  _decodeVcl (bytes, isKey) {
    if (!this.configured) return // 尚未拿到 SPS/PPS，丢弃
    if (this.skipUntilKey) {
      if (!isKey) return // 追新中：只等关键帧
      this.skipUntilKey = false
    }

    // 关键帧前置 SPS+PPS，保证解码器可独立解出该帧
    let payload
    let type
    if (isKey) {
      payload = concat(this.sps, this.pps, bytes)
      type = 'key'
    } else {
      payload = bytes
      type = 'delta'
    }

    const timestamp = this.tsUs
    this.tsUs += this.frameDurUs

    try {
      this.decoder.decode(new EncodedVideoChunk({ type, timestamp, data: payload }))
    } catch (e) {
      this._handleError(e)
      return
    }

    // 画最新、丢落后：队列积压则丢弃后续 delta，直到下一个关键帧重同步
    if (this.decoder && this.decoder.decodeQueueSize > this.queueThreshold) {
      this.skipUntilKey = true
    }
  }

  _maybeConfigure () {
    if (this.configured || !this.sps || !this.pps) return
    const codec = codecStringFromSps(this.sps)
    if (!codec) return

    try {
      this.decoder = new VideoDecoder({
        output: (frame) => this._onFrame(frame),
        error: (e) => this._handleError(e)
      })
      this.decoder.configure({
        codec,
        optimizeForLatency: true,
        hardwareAcceleration: 'prefer-hardware'
      })
      this.configured = true
      this.skipUntilKey = true // 配置后仍需等首个关键帧
    } catch (e) {
      this._handleError(e)
    }
  }

  _onFrame (frame) {
    try {
      if (this.closed || !this.ctx) return
      const w = frame.displayWidth
      const h = frame.displayHeight
      if (w && h && (this.canvas.width !== w || this.canvas.height !== h)) {
        this.canvas.width = w
        this.canvas.height = h
        if (this.onResize) this.onResize(w, h)
      }
      this.ctx.drawImage(frame, 0, 0, this.canvas.width, this.canvas.height)
    } finally {
      frame.close()
    }
  }

  _handleError (e) {
    // 软复位：关闭解码器，等下一组 SPS/PPS/IDR 重建
    this._teardownDecoder()
    if (this.onError) {
      try { this.onError(e) } catch (_) { /* ignore */ }
    } else {
      console.error('[WebCodecsPlayer] 解码错误:', e)
    }
  }

  _teardownDecoder () {
    if (this.decoder) {
      try {
        if (this.decoder.state !== 'closed') this.decoder.close()
      } catch (_) { /* ignore */ }
    }
    this.decoder = null
    this.configured = false
    this.skipUntilKey = true
  }
}

// ======================== 工具函数 ========================

/**
 * 将一段含 1+ 个 NAL 的 Annex-B 字节流按起始码拆分，逐个产出「起始码 + 负载」切片。
 * 兼容 3 字节(00 00 01)与 4 字节(00 00 00 01)起始码：统一以 00 00 01 定位，
 * 切片从该处开始，前导多余的 00 归入上一 NAL 尾部（解码器可容忍）。
 */
function* splitNalUnits (bytes) {
  const n = bytes.length
  let start = -1
  let i = 0
  while (i + 3 <= n) {
    if (bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 1) {
      if (start >= 0) {
        yield bytes.subarray(start, i)
      }
      start = i
      i += 3
    } else {
      i++
    }
  }
  if (start >= 0) {
    yield bytes.subarray(start, n)
  }
}

/** 返回 Annex-B 起始码长度（4 或 3），无起始码返回 0 */
function startCodeLength (b) {
  if (b.length >= 4 && b[0] === 0 && b[1] === 0 && b[2] === 0 && b[3] === 1) return 4
  if (b.length >= 3 && b[0] === 0 && b[1] === 0 && b[2] === 1) return 3
  return 0
}

/**
 * 从 SPS NAL 派生 WebCodecs codec 串。
 * Annex-B 内联参数集模式下不传 description，仅需 profile/constraint/level。
 * SPS 头字节之后依次为 profile_idc、constraint_set flags、level_idc。
 */
function codecStringFromSps (sps) {
  const startLen = startCodeLength(sps)
  // 头字节(1) 之后 3 字节：profile / constraint / level
  if (sps.length < startLen + 4) return null
  const profile = sps[startLen + 1]
  const constraint = sps[startLen + 2]
  const level = sps[startLen + 3]
  return 'avc1.' + hex2(profile) + hex2(constraint) + hex2(level)
}

function hex2 (n) {
  return n.toString(16).padStart(2, '0').toUpperCase()
}

/** 拼接多个 Uint8Array（忽略 null/undefined） */
function concat (...parts) {
  let total = 0
  for (const p of parts) if (p) total += p.length
  const out = new Uint8Array(total)
  let offset = 0
  for (const p of parts) {
    if (!p) continue
    out.set(p, offset)
    offset += p.length
  }
  return out
}
