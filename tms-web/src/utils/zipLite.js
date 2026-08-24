// 极简 ZIP 读写：仅依赖 pako 做 DEFLATE 解压。
// - 读取：解析中心目录，支持 STORED(0) 与 DEFLATE(8) 两种存储方式。
// - 写入：一律用 STORED(不压缩)，避免引入压缩依赖；.xlsx/.xmind 阅读器均接受不压缩包。
// 仅覆盖用例文件转换所需的最小子集，非通用 ZIP 库。
import pako from 'pako'

const CRC_TABLE = (() => {
  const t = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    t[n] = c >>> 0
  }
  return t
})()

function crc32(buf) {
  let c = 0xffffffff
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

function concatBytes(parts) {
  let total = 0
  for (const p of parts) total += p.length
  const out = new Uint8Array(total)
  let off = 0
  for (const p of parts) { out.set(p, off); off += p.length }
  return out
}

/**
 * 打包为 ZIP（STORED，不压缩）。
 * @param {Array<{name:string, data:Uint8Array}>} files
 * @returns {Uint8Array}
 */
export function zipWrite(files) {
  const enc = new TextEncoder()
  const parts = []
  const central = []
  let offset = 0
  for (const f of files) {
    const nameBytes = enc.encode(f.name)
    const data = f.data
    const crc = crc32(data)
    const lh = new Uint8Array(30 + nameBytes.length)
    const dv = new DataView(lh.buffer)
    dv.setUint32(0, 0x04034b50, true)
    dv.setUint16(4, 20, true)      // version needed
    dv.setUint16(6, 0x0800, true)  // flag: 文件名 UTF-8
    dv.setUint16(8, 0, true)       // method: stored
    dv.setUint16(10, 0, true)      // mod time
    dv.setUint16(12, 0, true)      // mod date
    dv.setUint32(14, crc, true)
    dv.setUint32(18, data.length, true) // compressed size
    dv.setUint32(22, data.length, true) // uncompressed size
    dv.setUint16(26, nameBytes.length, true)
    dv.setUint16(28, 0, true)      // extra len
    lh.set(nameBytes, 30)
    parts.push(lh, data)
    central.push({ nameBytes, crc, size: data.length, offset })
    offset += lh.length + data.length
  }

  const cdStart = offset
  for (const c of central) {
    const ch = new Uint8Array(46 + c.nameBytes.length)
    const dv = new DataView(ch.buffer)
    dv.setUint32(0, 0x02014b50, true)
    dv.setUint16(4, 20, true)      // version made by
    dv.setUint16(6, 20, true)      // version needed
    dv.setUint16(8, 0x0800, true)  // flag: UTF-8
    dv.setUint16(10, 0, true)      // method
    dv.setUint16(12, 0, true)
    dv.setUint16(14, 0, true)
    dv.setUint32(16, c.crc, true)
    dv.setUint32(20, c.size, true)
    dv.setUint32(24, c.size, true)
    dv.setUint16(28, c.nameBytes.length, true)
    dv.setUint16(30, 0, true)      // extra
    dv.setUint16(32, 0, true)      // comment
    dv.setUint16(34, 0, true)      // disk number
    dv.setUint16(36, 0, true)      // internal attrs
    dv.setUint32(38, 0, true)      // external attrs
    dv.setUint32(42, c.offset, true)
    ch.set(c.nameBytes, 46)
    parts.push(ch)
    offset += ch.length
  }
  const cdSize = offset - cdStart

  const eocd = new Uint8Array(22)
  const dv = new DataView(eocd.buffer)
  dv.setUint32(0, 0x06054b50, true)
  dv.setUint16(4, 0, true)
  dv.setUint16(6, 0, true)
  dv.setUint16(8, central.length, true)
  dv.setUint16(10, central.length, true)
  dv.setUint32(12, cdSize, true)
  dv.setUint32(16, cdStart, true)
  dv.setUint16(20, 0, true)
  parts.push(eocd)

  return concatBytes(parts)
}

/**
 * 解包 ZIP，返回 { 路径: Uint8Array }。支持 STORED 与 DEFLATE。
 * @param {Uint8Array} buf
 * @returns {Object<string, Uint8Array>}
 */
export function zipRead(buf) {
  if (!(buf instanceof Uint8Array)) buf = new Uint8Array(buf)
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength)

  // 从尾部回找 EOCD 记录
  let eocd = -1
  for (let i = buf.length - 22; i >= 0; i--) {
    if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break }
  }
  if (eocd < 0) throw new Error('无效的 ZIP 文件（找不到中心目录）')

  const count = dv.getUint16(eocd + 10, true)
  let ptr = dv.getUint32(eocd + 16, true)
  const dec = new TextDecoder('utf-8')
  const out = {}
  for (let n = 0; n < count; n++) {
    if (dv.getUint32(ptr, true) !== 0x02014b50) throw new Error('ZIP 中心目录已损坏')
    const method = dv.getUint16(ptr + 10, true)
    const compSize = dv.getUint32(ptr + 20, true)
    const nameLen = dv.getUint16(ptr + 28, true)
    const extraLen = dv.getUint16(ptr + 30, true)
    const commentLen = dv.getUint16(ptr + 32, true)
    const localOff = dv.getUint32(ptr + 42, true)
    const name = dec.decode(buf.subarray(ptr + 46, ptr + 46 + nameLen))

    // 本地头的名称/扩展长度可能与中心目录不同，按本地头计算数据起点
    const lNameLen = dv.getUint16(localOff + 26, true)
    const lExtraLen = dv.getUint16(localOff + 28, true)
    const dataStart = localOff + 30 + lNameLen + lExtraLen
    const comp = buf.subarray(dataStart, dataStart + compSize)

    let data
    if (method === 0) data = comp
    else if (method === 8) data = pako.inflateRaw(comp)
    else throw new Error('不支持的 ZIP 压缩方式: ' + method)
    out[name] = data

    ptr += 46 + nameLen + extraLen + commentLen
  }
  return out
}
