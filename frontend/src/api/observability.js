// Observability 模块 API 封装：包装既有 api.js（不改变公共请求函数）
import { api } from '../api'

export const obs = {
  runs: (params = {}) => {
    const q = new URLSearchParams()
    if (params.cursor) q.set('cursor', params.cursor)
    if (params.limit) q.set('limit', String(params.limit))
    if (params.ownerId != null) q.set('ownerId', String(params.ownerId))
    const qs = q.toString()
    return api.get('/observability/runs' + (qs ? '?' + qs : ''))
  },
  run: (runId) => api.get(`/observability/runs/${runId}`),
  spans: (runId, offset = 0, limit = 200) =>
    api.get(`/observability/runs/${runId}/spans?offset=${offset}&limit=${limit}`),
  events: (runId, cursor = 0, limit = 100) =>
    api.get(`/observability/runs/${runId}/events?cursor=${cursor}&limit=${limit}`),
  payload: (payloadId) => api.get(`/observability/payloads/${payloadId}`),
  tasks: () => api.get('/observability/tasks'),
  createTask: (body) => api.post('/observability/tasks', body),
  addRevision: (taskId, body) => api.post(`/observability/tasks/${taskId}/revisions`, body),
  linkRun: (taskId, body) => api.post(`/observability/tasks/${taskId}/links`, body),
  annotate: (runId, content) => api.post(`/observability/runs/${runId}/annotations`, { content }),
  importExperiment: (body) => api.post('/observability/experiments/imports', body),
  comparison: (experimentId, requiredPairs = 30) =>
    api.get(`/observability/experiments/${experimentId}/comparison?requiredPairs=${requiredPairs}`),
  exportPreview: (limit = 100) => api.post(`/observability/exports/preview?limit=${limit}`),
  export: (format = 'json', limit = 100) =>
    api.post(`/observability/exports?format=${format}&limit=${limit}`)
}

/** 状态展示字典（文字+图标，不只颜色） */
export const STATUS_CN = {
  RUNNING: '▶ 运行中',
  COMPLETED: '✓ 完成',
  ABORTED: '✕ 中止',
  UNKNOWN: '? 未知',
  PARTIAL: '⚠ 部分数据',
  COMPLETE: '✓ 完整',
  CANCELLED: '✕ 已取消',
  FAILED: '✕ 失败',
  COMMITTED: '✓ 已提交',
  NEEDS_CONFIRMATION: '… 待确认',
  STARTED: '▶ 开始',
  SUCCESS: '✓ 成功'
}

export const STATUS_CLASS = {
  RUNNING: 'badge RUNNING',
  COMPLETED: 'badge COMPLETED',
  ABORTED: 'badge ABORTED',
  UNKNOWN: 'badge UNKNOWN',
  PARTIAL: 'badge PARTIAL',
  COMPLETE: 'badge COMPLETE',
  FAILED: 'badge FAILED',
  COMMITTED: 'badge COMMITTED',
  CANCELLED: 'badge CANCELLED',
  NEEDS_CONFIRMATION: 'badge NEEDS_CONFIRMATION'
}

export function fmtTime(ms) {
  if (ms == null) return '—'
  const d = new Date(ms)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

export function fmtMs(ms) {
  if (ms == null) return '—'
  if (ms < 1000) return ms + ' ms'
  if (ms < 60000) return (ms / 1000).toFixed(1) + ' s'
  return (ms / 60000).toFixed(1) + ' min'
}

export function fmtCost(v) {
  if (v == null) return '未知'
  return '¥' + v
}

export function fmtToken(v, usageStatus) {
  if (usageStatus === 'NOT_APPLICABLE') return 'N/A'
  if (v == null) return '未知'
  return String(v)
}
