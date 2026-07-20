// 设备应用管理 API —— 直连 agent 代理端点。
// 与后端 baseURL 不同源、无鉴权，故用原始 fetch/XHR，不走 src/api/index.js 的 axios 实例。
// 该接口同时供自动化脚本直接调用（安装:POST 原始文件体 ?filename=；卸载:POST {id}）。
// 基址经 agentHttpBase 计算：HTTPS 下走同源 /agent/<platform> 反代，避免混合内容拦截。

import { agentHttpBase } from '@/utils/device'

function base (proxyHost, proxyPort, platform) {
  return agentHttpBase(proxyHost, proxyPort, platform)
}

export async function listApps (proxyHost, proxyPort, serial, platform = 'android') {
  const res = await fetch(`${base(proxyHost, proxyPort, platform)}/devices/${serial}/apps`)
  const data = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error(data.error || `获取应用列表失败(${res.status})`)
  return data.apps || []
}

export async function uninstallApp (proxyHost, proxyPort, serial, id, platform = 'android') {
  const res = await fetch(`${base(proxyHost, proxyPort, platform)}/devices/${serial}/apps/uninstall`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id })
  })
  const data = await res.json().catch(() => ({}))
  if (!res.ok || !data.ok) throw new Error(data.error || `卸载失败(${res.status})`)
}

// 用 XHR 上传以获得进度；原始文件体 + ?filename=；装完同步返回
export function installApp (proxyHost, proxyPort, serial, file, onProgress, platform = 'android') {
  return new Promise((resolve, reject) => {
    const url = `${base(proxyHost, proxyPort, platform)}/devices/${serial}/apps/install?filename=${encodeURIComponent(file.name)}`
    const xhr = new XMLHttpRequest()
    xhr.open('POST', url)
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable && onProgress) onProgress(Math.round((e.loaded / e.total) * 100))
    }
    xhr.onload = () => {
      let data = {}
      try { data = JSON.parse(xhr.responseText) } catch (_) { /* ignore */ }
      if (xhr.status >= 200 && xhr.status < 300 && data.ok) resolve()
      else reject(new Error(data.error || `安装失败(${xhr.status})`))
    }
    xhr.onerror = () => reject(new Error('网络错误，安装失败'))
    xhr.send(file)
  })
}
