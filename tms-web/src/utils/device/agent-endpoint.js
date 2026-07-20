/**
 * 直连 agent 代理端点的基址计算。
 *
 * 云真机的投屏/控制/inspector（WS）与应用管理/截图（HTTP）默认直连 agent 机器
 * 的 proxyHost:proxyPort。但当页面以 HTTPS 访问时（内网经 nginx 自签证书 TLS 反代，
 * 从而让 WebCodecs 所需的安全上下文成立），直连 agent 的 `ws://`/`http://` 会被浏览器
 * 以「混合内容」为由拦截。此时改走同源的 nginx 反代前缀 `/agent/<platform>/`，
 * 由 nginx 转发到 agent（Android→:8000，iOS→:8001）。
 *
 * - HTTPS：返回同源 `wss://<host>/agent/<platform>` 与 `https://<host>/agent/<platform>`
 * - 其它（dev：http://localhost）：返回原直连基址 `ws(s)://proxyHost:proxyPort`，行为不变
 */

function isSecure () {
  return typeof window !== 'undefined' && window.location.protocol === 'https:'
}

/**
 * WebSocket 基址（不含 /devices/... 路径）。
 * @param {string} proxyHost
 * @param {string|number} proxyPort
 * @param {'android'|'ios'} platform
 * @returns {string} 如 `wss://host/agent/android` 或 `ws://1.2.3.4:8000`
 */
export function agentWsBase (proxyHost, proxyPort, platform) {
  if (isSecure()) {
    return `wss://${window.location.host}/agent/${platform}`
  }
  return `ws://${proxyHost}:${proxyPort}`
}

/**
 * HTTP 基址（不含 /devices/... 路径）。
 * @param {string} proxyHost
 * @param {string|number} proxyPort
 * @param {'android'|'ios'} platform
 * @returns {string} 如 `https://host/agent/android` 或 `http://1.2.3.4:8000`
 */
export function agentHttpBase (proxyHost, proxyPort, platform) {
  if (isSecure()) {
    return `${window.location.protocol}//${window.location.host}/agent/${platform}`
  }
  return `http://${proxyHost}:${proxyPort}`
}
