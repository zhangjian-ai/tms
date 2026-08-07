/**
 * XMind 用例面板的「视觉主题」。
 *
 * 这里只负责外观：节点配色、优先级徽章配色、连线路径形状。
 * 不含任何业务 / 交互逻辑（数据转换、事件、右键菜单等仍在 XMindTreePanel.vue 里）。
 * 想换配色或连线风格，改这一个文件即可，不影响组件本身逻辑。
 */

// ---- 节点类型配色 ----
// key 为节点 type；value 同时用作节点背景色和连线颜色（branchColor）。
// 冷色系青蓝渐变：root→目录→用例→步骤 由深到浅、相邻层为邻近色，无互补撞色。
export const NODE_COLORS = {
  root: '#2c3e50',    // 深蓝灰
  module: '#2980b9',  // 蓝
  case: '#16a085',    // 青绿 teal
  step: '#48c9b0',    // 浅青（仅用于连线；步骤节点本身无填充，见下）
  free: '#606266'     // 深灰
}

// ---- 无填充节点类型 ----
// 这些类型的节点不上底色（透明背景 + 深色字），只保留连线着色。
// 步骤是叶子、数量最多，去底色减轻视觉负担、让带色的目录/用例节点作为结构骨架更突出。
export const PLAIN_NODE_TYPES = ['step']
// 透明底节点的文字色。引用 CSS 变量 --tms-plain-text（在 XMindTreePanel 里按
// prefers-color-scheme 定义：浅色主题深字、深色主题浅字），兜底用 mind-elixir 自身的
// 主题文字色 --color。避免 OS 深色模式下（mind-elixir 会自动切深色画布）出现黑底黑字。
export const PLAIN_TEXT_COLOR = 'var(--tms-plain-text, var(--color))'

// ---- 优先级徽章配色 ----
export const PRIORITY_CONFIG = {
  'priority-1': { label: 'P0', color: '#e70f0f', number: '0' },
  'priority-2': { label: 'P1', color: '#f44949', number: '1' },
  'priority-3': { label: 'P2', color: '#efa338', number: '2' },
  'priority-4': { label: 'P3', color: '#67c23a', number: '3' }
}

/**
 * 连线路径：直角折线（组织架构图风格），替换 mind-elixir 默认曲线。
 *
 * 入参 p 由 mind-elixir 提供：pT/pL/pW/pH 父节点，cT/cL/cW/cH 子节点，
 * direction 含 'lhs' 表示在左侧。this 为 mind-elixir 实例。
 */

// 主枝（root→一级节点）：坐标取 me-root 与子节点 me-tpc，均无 padding，直接连节点边。
export function generateMainBranch(p) {
  var isLeft = /lhs/.test(String(p.direction))
  var y0 = p.pT + p.pH / 2                          // 起点 y：root 垂直中心
  var y1 = p.cT + p.cH / 2                          // 终点 y：子节点垂直中心
  var x0 = isLeft ? p.pL : (p.pL + p.pW)            // 起点 x：root 近侧边
  var x1 = isLeft ? (p.cL + p.cW) : p.cL            // 终点 x：子节点近侧边
  var xm = (x0 + x1) / 2                             // 竖干 x：中点（同侧各主枝共享）

  return 'M ' + x0 + ' ' + y0 +   // root 出线
    ' H ' + xm +                  // 水平到竖干
    ' V ' + y1 +                  // 竖直（直角拐弯）
    ' H ' + x1                    // 水平进子节点（直角拐弯）
}

// 读取 --node-gap-x（子枝容器 me-parent 的左右 padding），用于跳过透明内边距。
function subGap(inst) {
  var d = 30
  try {
    var s = inst && inst.container && inst.container.style.getPropertyValue('--node-gap-x')
    var v = parseInt(s, 10)
    if (!v && inst) v = parseInt(getComputedStyle(inst.container).getPropertyValue('--node-gap-x'), 10)
    if (v) d = v
  } catch (e) { /* 用默认 gap */ }
  return d
}

// 子枝：坐标取带 padding 的容器 me-parent，两端各跳过一个 --node-gap-x 连到可见节点边。
export function generateSubBranch(p) {
  var d = subGap(this)
  var isLeft = /lhs/.test(String(p.direction))
  var y0 = p.pT + p.pH / 2                          // 起点 y：父节点垂直中心
  var y1 = p.cT + p.cH / 2                          // 终点 y：子节点垂直中心
  var x0 = isLeft ? (p.pL + d) : (p.pL + p.pW - d)  // 起点 x：父节点可见边（跳过 padding）
  var x1 = isLeft ? (p.cL + p.cW - d) : (p.cL + d)  // 终点 x：子节点可见边（跳过 padding）
  var xm = (x0 + x1) / 2                             // 竖干 x：中点（同一父节点各子枝共享）

  return 'M ' + x0 + ' ' + y0 +   // 父节点出线
    ' H ' + xm +                  // 水平到竖干
    ' V ' + y1 +                  // 竖直（直角拐弯）
    ' H ' + x1                    // 水平进子节点（直角拐弯）
}
