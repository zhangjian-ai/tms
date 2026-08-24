// 用例文件互转（纯前端）：Excel(.xlsx) <-> XMind(.xmind)。
// 逻辑移植自 transfer/excel_to_xmind.py 与 transfer/xmind_to_excel.py。
// - .xlsx / .xmind 均为 ZIP 容器，借助 zipLite 读写。
// - 仅支持新版 .xlsx（不支持老的二进制 .xls）。
// - XMind 用例通过 priority-N 标记识别；目录层级用「|」连接。
import { zipRead, zipWrite } from './zipLite'

// ---------------- 通用小工具 ----------------

const LEVEL_TO_PRIORITY = { P0: 1, P1: 2, P2: 3, P3: 4 }
const PRIORITY_TO_LEVEL = { 1: 'P0', 2: 'P1', 3: 'P2', 4: 'P3' }

// content.json 里需要一张极小的缩略图占位（1x1 PNG）
const THUMBNAIL_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8' +
  '/x8AAwMCAO5x9n0AAAAASUVORK5CYII='

function uuid() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

function norm(s) {
  if (s === null || s === undefined) return ''
  return String(s).replace(/\r\n/g, '\n').replace(/\r/g, '\n').trim()
}

// 逐行 strip、去空行后用单个换行重连
function cleanText(text) {
  const lines = norm(text).split('\n').map((l) => l.trim())
  return lines.filter((l) => l).join('\n')
}

// 合并连续空行为单个换行
function collapseBlankLines(text) {
  return norm(text).replace(/\n{2,}/g, '\n').trim()
}

const utf8 = (s) => new TextEncoder().encode(s)

function b64ToBytes(b64) {
  const bin = atob(b64)
  const arr = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) arr[i] = bin.charCodeAt(i)
  return arr
}

function stripExt(name) {
  const i = name.lastIndexOf('.')
  return i > 0 ? name.slice(0, i) : name
}

// ================================================================
//  Excel -> XMind
// ================================================================

// 逻辑字段 -> 可接受的表头名（新版在前，旧版兼容在后）
const COL_ALIASES = {
  directory: ['所属目录', '用例目录'],
  name: ['用例名称'],
  id: ['ID', '测试用例ID'],
  precondition: ['前置条件'],
  level: ['用例分级', '用例等级'],
  steps: ['测试步骤', '用例步骤'],
  expected: ['预期结果'],
  type: ['用例类型'],
}

function resolveCol(mapping, field) {
  for (const alias of COL_ALIASES[field] || []) {
    if (Object.prototype.hasOwnProperty.call(mapping, alias)) return mapping[alias]
  }
  return null
}

// 列字母 -> 0 基索引（A->0, B->1, ..., AA->26）
function colToIndex(ref) {
  const letters = ref.match(/^[A-Z]+/i)
  if (!letters) return 0
  let n = 0
  for (const ch of letters[0].toUpperCase()) n = n * 26 + (ch.charCodeAt(0) - 64)
  return n - 1
}

// 解析 .xlsx 工作表为二维数组（行 x 列，缺失单元格为 ''）
function readXlsxMatrix(bytes) {
  const files = zipRead(bytes)
  const dec = new TextDecoder('utf-8')
  const parser = new DOMParser()

  // 共享字符串（若单元格用 t="s"）
  let shared = []
  if (files['xl/sharedStrings.xml']) {
    const doc = parser.parseFromString(dec.decode(files['xl/sharedStrings.xml']), 'application/xml')
    const sis = doc.getElementsByTagName('si')
    shared = new Array(sis.length)
    for (let i = 0; i < sis.length; i++) shared[i] = sis[i].textContent || ''
  }

  // 选取第一个工作表：优先 sheet1.xml，否则取编号最小的
  let sheetKey = null
  const sheetKeys = Object.keys(files).filter((k) => /^xl\/worksheets\/sheet\d+\.xml$/.test(k))
  if (sheetKeys.length) {
    sheetKeys.sort((a, b) => {
      const na = parseInt(a.match(/(\d+)\.xml$/)[1], 10)
      const nb = parseInt(b.match(/(\d+)\.xml$/)[1], 10)
      return na - nb
    })
    sheetKey = sheetKeys[0]
  }
  if (!sheetKey) throw new Error('未在 .xlsx 中找到工作表')

  const doc = parser.parseFromString(dec.decode(files[sheetKey]), 'application/xml')
  const rowEls = doc.getElementsByTagName('row')
  const matrix = []
  let maxCol = 0
  for (let i = 0; i < rowEls.length; i++) {
    const rowEl = rowEls[i]
    const rowNum = parseInt(rowEl.getAttribute('r') || String(matrix.length + 1), 10)
    const cells = rowEl.getElementsByTagName('c')
    const rowArr = []
    for (let j = 0; j < cells.length; j++) {
      const c = cells[j]
      const ref = c.getAttribute('r') || ''
      const colIdx = ref ? colToIndex(ref) : j
      const t = c.getAttribute('t')
      let text = ''
      if (t === 'inlineStr') {
        const is = c.getElementsByTagName('is')[0]
        text = is ? is.textContent || '' : ''
      } else if (t === 's') {
        const v = c.getElementsByTagName('v')[0]
        const idx = v ? parseInt(v.textContent, 10) : -1
        text = idx >= 0 && idx < shared.length ? shared[idx] : ''
      } else {
        const v = c.getElementsByTagName('v')[0]
        text = v ? v.textContent || '' : ''
      }
      rowArr[colIdx] = text
      if (colIdx + 1 > maxCol) maxCol = colIdx + 1
    }
    // 用行号定位，缺行补空数组
    matrix[rowNum - 1] = rowArr
  }
  // 补齐每行长度与缺失行
  for (let i = 0; i < matrix.length; i++) {
    if (!matrix[i]) matrix[i] = []
    for (let c = 0; c < maxCol; c++) if (matrix[i][c] === undefined) matrix[i][c] = ''
  }
  return matrix
}

function findHeaderRow(matrix) {
  const scan = Math.min(30, matrix.length)
  for (let r = 0; r < scan; r++) {
    const mapping = {}
    const row = matrix[r] || []
    for (let c = 0; c < row.length; c++) {
      const name = String(row[c] == null ? '' : row[c]).trim()
      if (name) mapping[name] = c
    }
    if (resolveCol(mapping, 'name') !== null && resolveCol(mapping, 'directory') !== null) {
      return { headerRow: r, mapping }
    }
  }
  throw new Error('未找到包含必需列的表头：用例名称 与 所属目录/用例目录')
}

function isInstructionRow(v) {
  const s = norm(v)
  return s.startsWith('[字段填写说明]') || s.startsWith('“用例')
}

// 把原始表格行折叠成用例：用例名称非空开启新用例，随后名称为空但有步骤/预期的行是续行
function groupRowsIntoCases(rows) {
  const cases = []
  let pending = []
  let current = null

  const flush = () => {
    if (!current) return
    cases.push({
      caseId: current.caseId,
      directory: current.directory,
      name: current.name,
      precondition: current.precondition,
      steps: pending.slice(),
      level: current.level,
    })
  }

  for (const row of rows) {
    if (isInstructionRow(row.name) || isInstructionRow(row.directory)) continue
    if (row.name) {
      flush()
      current = row
      pending = []
      if (row.steps || row.expected) pending.push([row.steps, row.expected])
    } else {
      if (!current) continue
      if (row.steps || row.expected) pending.push([row.steps, row.expected])
    }
  }
  flush()
  return cases
}

function readCasesFromXlsx(bytes) {
  const matrix = readXlsxMatrix(bytes)
  const { headerRow, mapping } = findHeaderRow(matrix)
  const cell = (row, field) => {
    const c = resolveCol(mapping, field)
    return c !== null ? norm(row[c]) : ''
  }
  const rawId = (row) => {
    const c = resolveCol(mapping, 'id')
    return c !== null ? norm(row[c]) || null : null
  }
  const rows = []
  for (let r = headerRow + 1; r < matrix.length; r++) {
    const row = matrix[r] || []
    rows.push({
      name: cell(row, 'name'),
      directory: cell(row, 'directory'),
      precondition: cell(row, 'precondition'),
      level: cell(row, 'level'),
      steps: cell(row, 'steps'),
      expected: cell(row, 'expected'),
      caseId: rawId(row),
    })
  }
  return groupRowsIntoCases(rows)
}

function ensureAttached(topic) {
  if (!topic.children) topic.children = {}
  if (!Array.isArray(topic.children.attached)) topic.children.attached = []
  return topic.children.attached
}

function findOrCreateDirNode(root, parts) {
  let cur = root
  for (const p of parts) {
    if (!p) continue
    const attached = ensureAttached(cur)
    let nxt = attached.find(
      (ch) => ch && typeof ch === 'object' && norm(ch.title) === p && ch.class === 'topic'
    )
    if (!nxt) {
      nxt = { id: uuid(), class: 'topic', title: p, children: { attached: [] } }
      attached.push(nxt)
    }
    cur = nxt
  }
  return cur
}

function caseTopicFromRow(row) {
  const level = (row.level || '').toUpperCase().trim()
  const priority = LEVEL_TO_PRIORITY[level] || 3

  const topic = {
    id: uuid(),
    class: 'topic',
    title: row.name,
    markers: [{ markerId: `priority-${priority}` }],
    children: { attached: [] },
  }
  if (row.caseId) topic.labels = [row.caseId]

  const attached = ensureAttached(topic)

  // 前置条件节点：保留「前置条件：」标签便于在 XMind 中识别
  const preContent = cleanText(norm(row.precondition))
  const preTitle = preContent ? '前置条件：\n' + preContent : '前置条件：'
  attached.push({ id: uuid(), class: 'topic', title: preTitle })

  // 每个 (步骤, 预期) 生成一个步骤节点，预期作为其子节点
  for (const [stepText, expectedText] of row.steps) {
    const steps = cleanText(norm(stepText))
    const expected = cleanText(norm(expectedText))
    const stepNode = { id: uuid(), class: 'topic', title: steps }
    if (expected) {
      stepNode.children = { attached: [{ id: uuid(), class: 'topic', title: expected }] }
    }
    attached.push(stepNode)
  }
  return topic
}

function buildContentJson(fileStem, cases) {
  const dirParts = (row) => norm(row.directory).split('|').map((p) => p.trim()).filter((p) => p)

  const topDirs = new Set()
  for (const row of cases) {
    const parts = dirParts(row)
    topDirs.add(parts.length ? parts[0] : '')
  }

  // 唯一共享的顶层目录作为根标题（其层级不在下方重复）；否则以文件名作根、各用例保留完整目录路径
  let rootTitle
  let stripFirst
  if (topDirs.size === 1) {
    rootTitle = [...topDirs][0] || fileStem
    stripFirst = true
  } else {
    rootTitle = fileStem
    stripFirst = false
  }

  const rootTopic = {
    id: uuid(),
    class: 'topic',
    title: rootTitle,
    structureClass: 'org.xmind.ui.logic.right',
    children: { attached: [] },
  }

  for (const row of cases) {
    const parts = dirParts(row)
    const subParts = stripFirst ? parts.slice(1) : parts
    const parent = findOrCreateDirNode(rootTopic, subParts)
    ensureAttached(parent).push(caseTopicFromRow(row))
  }

  const sheet = {
    id: uuid(),
    revisionId: uuid(),
    class: 'sheet',
    title: rootTitle,
    rootTopic,
  }
  return [sheet]
}

/**
 * Excel(.xlsx) -> XMind(.xmind)
 * @param {ArrayBuffer|Uint8Array} buffer 原始文件字节
 * @param {string} fileName 原始文件名（用于生成输出名/根标题）
 * @returns {{ blob: Blob, fileName: string }}
 */
export function excelToXmind(buffer, fileName) {
  const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer)
  const stem = stripExt(fileName || 'cases')
  const cases = readCasesFromXlsx(bytes)
  if (!cases.length) throw new Error('未从 Excel 中解析到任何用例（请检查表头与内容）')

  const content = buildContentJson(stem, cases)
  const metadata = {
    dataStructureVersion: '3',
    creator: { name: 'transfer', version: '1.0' },
    layoutEngineVersion: '5',
  }
  const manifest = {
    'file-entries': {
      'content.json': {},
      'metadata.json': {},
      'manifest.json': {},
      'content.xml': {},
      'Thumbnails/thumbnail.png': {},
    },
  }
  const files = [
    { name: 'content.json', data: utf8(JSON.stringify(content)) },
    { name: 'metadata.json', data: utf8(JSON.stringify(metadata)) },
    { name: 'manifest.json', data: utf8(JSON.stringify(manifest)) },
    { name: 'content.xml', data: utf8('<?xml version="1.0" encoding="UTF-8"?><xmap-content></xmap-content>') },
    { name: 'Thumbnails/thumbnail.png', data: b64ToBytes(THUMBNAIL_PNG_BASE64) },
  ]
  const zipped = zipWrite(files)
  return {
    blob: new Blob([zipped], { type: 'application/octet-stream' }),
    fileName: stem + '.xmind',
  }
}

// ================================================================
//  XMind -> Excel
// ================================================================

function iterAttached(topic) {
  const children = topic.children || {}
  const attached = children.attached || []
  return attached.filter((c) => c && typeof c === 'object')
}

function priorityFromTopic(topic) {
  const markers = topic.markers || []
  for (const m of markers) {
    if (!m || typeof m !== 'object') continue
    const id = String(m.markerId || '')
    if (id.startsWith('priority-')) {
      const n = parseInt(id.split('-')[1], 10)
      return isNaN(n) ? null : n
    }
  }
  return null
}

function labelIdFromTopic(topic) {
  const labels = topic.labels
  if (!Array.isArray(labels)) return null
  for (const lab of labels) {
    const s = norm(String(lab))
    if (s) return s
  }
  return null
}

// 去掉「xxx：」前缀（前缀不含冒号且长度 1-30）
function stripPrefixBeforeColon(title) {
  return (title || '').replace(/^[^：:]{1,30}[：:]\s*/, '')
}

const COMBINED_RE = /\n\s*预期结果[：:]\s*\n?/

// 前序遍历，产出 [祖先标题数组, 节点]（祖先不含自身）
function walkTopics(root) {
  const out = []
  const rec = (node, ancestors) => {
    out.push([ancestors, node])
    const title = norm(String(node.title || ''))
    const next = title ? ancestors.concat([title]) : ancestors
    for (const child of iterAttached(node)) rec(child, next)
  }
  rec(root, [])
  return out
}

function parseXmindCases(bytes) {
  const files = zipRead(bytes)
  if (!files['content.json']) throw new Error('不是有效的 XMind 文件（缺少 content.json）')
  const content = JSON.parse(new TextDecoder('utf-8').decode(files['content.json']))
  if (!Array.isArray(content) || !content.length) throw new Error('XMind content.json 为空或格式不正确')
  const root = content[0] && content[0].rootTopic
  if (!root || typeof root !== 'object') throw new Error('XMind content.json 缺少 rootTopic')

  const cases = []
  for (const [ancestors, topic] of walkTopics(root)) {
    const prio = priorityFromTopic(topic)
    if (prio === null) continue
    if (!PRIORITY_TO_LEVEL[prio]) {
      throw new Error(`不支持的优先级 ${prio}（节点「${topic.title}」），应为 1-4`)
    }
    const level = PRIORITY_TO_LEVEL[prio]
    const name = norm(stripPrefixBeforeColon(String(topic.title || '')))
    const caseId = labelIdFromTopic(topic)
    const directory = ancestors.filter((a) => norm(a)).join('|')

    const children = iterAttached(topic)
    let precondition = ''
    const steps = []
    if (children.length) {
      precondition = collapseBlankLines(stripPrefixBeforeColon(String(children[0].title || '')))
      const stepTopics = children.slice(1)
      // 两种布局：1) 经典——多个步骤节点，各带预期子节点；2) 合并——单节点标题内含步骤+预期
      if (
        stepTopics.length === 1 &&
        iterAttached(stepTopics[0]).length === 0 &&
        norm(String(stepTopics[0].title || '')).includes('预期结果')
      ) {
        const combined = norm(stripPrefixBeforeColon(String(stepTopics[0].title || '')))
        const m = combined.match(COMBINED_RE)
        if (m) {
          const idx = combined.indexOf(m[0])
          steps.push([combined.slice(0, idx).trim(), combined.slice(idx + m[0].length).trim()])
        } else {
          steps.push([combined, ''])
        }
      } else {
        for (const st of stepTopics) {
          const stepText = norm(String(st.title || ''))
          const expTexts = iterAttached(st).map((ec) => norm(String(ec.title || ''))).filter((t) => t)
          steps.push([stepText, expTexts.join('\n')])
        }
      }
    }
    cases.push({ caseId, directory, name, precondition, steps, level })
  }
  return cases
}

// ---- 极简 .xlsx 写出（inlineStr，含表头样式与自动换行）----

function escXml(s) {
  return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function colLetter(i) {
  let s = ''
  i += 1
  while (i > 0) {
    const m = (i - 1) % 26
    s = String.fromCharCode(65 + m) + s
    i = Math.floor((i - 1) / 26)
  }
  return s
}

function cellXml(colIdx, rowNum, text, style) {
  if (text === null || text === undefined || text === '') return ''
  const ref = colLetter(colIdx) + rowNum
  return `<c r="${ref}" s="${style}" t="inlineStr"><is><t xml:space="preserve">${escXml(text)}</t></is></c>`
}

function buildXlsx(headers, widths, dataRows) {
  const cols = widths
    .map((w, i) => `<col min="${i + 1}" max="${i + 1}" width="${w}" customWidth="1"/>`)
    .join('')

  let body = ''
  body += `<row r="1">` + headers.map((h, i) => cellXml(i, 1, h, 1)).join('') + `</row>`
  let rn = 2
  for (const row of dataRows) {
    body += `<row r="${rn}">` + row.map((v, i) => cellXml(i, rn, v, 2)).join('') + `</row>`
    rn++
  }

  const sheetXml =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">` +
    `<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>` +
    `<sheetFormatPr defaultRowHeight="15"/>` +
    `<cols>${cols}</cols><sheetData>${body}</sheetData></worksheet>`

  const contentTypes =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">` +
    `<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>` +
    `<Default Extension="xml" ContentType="application/xml"/>` +
    `<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>` +
    `<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>` +
    `<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>` +
    `</Types>`

  const rels =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` +
    `<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>` +
    `</Relationships>`

  const workbook =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">` +
    `<sheets><sheet name="测试用例" sheetId="1" r:id="rId1"/></sheets></workbook>`

  const wbRels =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` +
    `<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>` +
    `<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>` +
    `</Relationships>`

  const styles =
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">` +
    `<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>` +
    `<fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>` +
    `<fill><patternFill patternType="solid"><fgColor rgb="FFC4BD97"/></patternFill></fill></fills>` +
    `<borders count="1"><border/></borders>` +
    `<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>` +
    `<cellXfs count="3">` +
    `<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>` +
    `<xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>` +
    `<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>` +
    `</cellXfs>` +
    `<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>` +
    `</styleSheet>`

  return zipWrite([
    { name: '[Content_Types].xml', data: utf8(contentTypes) },
    { name: '_rels/.rels', data: utf8(rels) },
    { name: 'xl/workbook.xml', data: utf8(workbook) },
    { name: 'xl/_rels/workbook.xml.rels', data: utf8(wbRels) },
    { name: 'xl/styles.xml', data: utf8(styles) },
    { name: 'xl/worksheets/sheet1.xml', data: utf8(sheetXml) },
  ])
}

// 一条用例展开为若干行（一步一行）；首行携带全部字段，续行仅步骤/预期
function caseRows(tc) {
  const pairs = tc.steps.length ? tc.steps : [['', '']]
  const [firstStep, firstExp] = pairs[0]
  const rows = [[tc.name, tc.level, '功能', tc.precondition, firstStep, firstExp, tc.directory]]
  for (let i = 1; i < pairs.length; i++) {
    const [step, exp] = pairs[i]
    rows.push(['', '', '', '', step, exp, ''])
  }
  return rows
}

/**
 * XMind(.xmind) -> Excel(.xlsx)
 * @param {ArrayBuffer|Uint8Array} buffer 原始文件字节
 * @param {string} fileName 原始文件名（用于生成输出名）
 * @returns {{ blob: Blob, fileName: string }}
 */
export function xmindToExcel(buffer, fileName) {
  const bytes = buffer instanceof Uint8Array ? buffer : new Uint8Array(buffer)
  const stem = stripExt(fileName || 'cases')
  const cases = parseXmindCases(bytes)
  if (!cases.length) throw new Error('未从 XMind 中解析到任何用例（需要带优先级标记 priority-N 的节点）')

  const headers = ['用例名称', '用例分级', '用例类型', '前置条件', '测试步骤', '预期结果', '所属目录']
  const widths = [34, 10, 12, 30, 46, 46, 40]
  const dataRows = []
  for (const tc of cases) for (const row of caseRows(tc)) dataRows.push(row)

  const zipped = buildXlsx(headers, widths, dataRows)
  return {
    blob: new Blob([zipped], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    }),
    fileName: stem + '.xlsx',
  }
}
