/**
 * 计算直连 agent 代理端点的基址。
 * HTTPS 下走同源 /agent/<platform>（由 nginx 反代到 agent），否则直连 proxyHost:proxyPort。
 */

function isSecure () {
  return typeof window !== 'undefined' && window.location.protocol === 'https:'
}

/** WebSocket 基址，如 wss://host/agent/android 或 ws://1.2.3.4:8000 */
export function agentWsBase (proxyHost, proxyPort, platform) {
  if (isSecure()) {
    return `wss://${window.location.host}/agent/${platform}`
  }
  return `ws://${proxyHost}:${proxyPort}`
}

/** HTTP 基址，如 https://host/agent/android 或 http://1.2.3.4:8000 */
export function agentHttpBase (proxyHost, proxyPort, platform) {
  if (isSecure()) {
    return `${window.location.protocol}//${window.location.host}/agent/${platform}`
  }
  return `http://${proxyHost}:${proxyPort}`
}
