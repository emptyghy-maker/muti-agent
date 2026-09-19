// 统一 API 封装：带 token 的 fetch + 统一 code!=0 抛错（S06-B：202 返回受理结果，错误带 HTTP 状态）
const BASE = '/api/v1'

function headers() {
  const h = { 'Content-Type': 'application/json' }
  const t = localStorage.getItem('token')
  if (t) h.Authorization = 'Bearer ' + t
  return h
}

async function request(path, options = {}) {
  const resp = await fetch(BASE + path, { headers: headers(), ...options })
  // 401 = token 缺失/过期：清掉本地 token 并通知 App 回到登录页
  if (resp.status === 401 && !path.startsWith('/auth/')) {
    localStorage.removeItem('token')
    window.dispatchEvent(new Event('auth-expired'))
    const e = new Error('登录已过期，请重新登录')
    e.status = 401
    throw e
  }
  if (resp.status === 202) {
    // S06-B：已受理，返回 operationId 供轮询；不得重新生成同一动作
    const body = await resp.json().catch(() => null)
    return { accepted: true, operationId: body && body.data ? body.data.operationId : null }
  }
  if (!resp.ok) {
    // 后端 401/403/404/409/422/423/503 都会带 JSON 错误体：优先透出业务 message
    let msg = 'HTTP ' + resp.status
    try {
      const body = await resp.json()
      if (body && body.message) msg = body.message
    } catch (e) { /* 非 JSON 错误体（网关页等），保留状态码文案 */ }
    const err = new Error(msg)
    err.status = resp.status
    throw err
  }
  const json = await resp.json()
  if (json.code !== 0) {
    const err = new Error(json.message || '请求失败')
    err.status = resp.status
    err.code = json.code
    throw err
  }
  return json.data
}

export const api = {
  get: (p) => request(p),
  post: (p, body) => request(p, { method: 'POST', body: JSON.stringify(body) }),
  /** 操作进度 SSE：只订阅已有操作，不触发执行；重连安全（EventSource 无法带自定义头，走 query token） */
  operationStream(opId) {
    const t = localStorage.getItem('token') || ''
    return new EventSource(`${BASE}/travel/operations/${opId}/stream?token=${encodeURIComponent(t)}`)
  }
}
