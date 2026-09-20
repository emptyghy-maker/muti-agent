<script setup>
// 管理员消费日志审计台：按会话聚合展示一次规划的完整链路 + 告警/漏斗/趋势/慢调用分析
import { ref, computed, onMounted } from 'vue'
import { api } from '../api'

const STAGE_CN = {
  PREFERENCE: '偏好问询', ATTRACTIONS: '景点推荐', FOODS: '美食推荐',
  HOTELS: '酒店推荐', ITINERARY: '行程规划', ADJUST: '行程调整',
  ROUTE: '路径推荐', FEEDBACK: '评价反馈', DONE: '行程完成'
}
const CHANNEL_CN = {
  KB: '知识库', CACHE: '缓存翻页', AGENT: 'AI 分析',
  RULE_FALLBACK: '规则兜底', OP: '操作'
}
const RESULT_CN = {
  DONE: '成功', TIMEOUT: '失败·超时', FAILED: '失败',
  RESTARTED: '未完成·重新开始', EXPIRED: '未完成·会话过期', ONGOING: '未完成·进行中'
}
const stageCn = (s) => STAGE_CN[s] || s || '-'
const channelCn = (c) => CHANNEL_CN[c] || c || '-'
const resultBadge = (s) => (s === 'DONE' ? 'ok' : (s === 'TIMEOUT' || s === 'FAILED') ? 'bad' : 'warn')
const resultText = (s) => {
  if (s.resultStatus === 'FAILED' && s.failAction) return '失败·' + s.failAction
  if (s.resultStatus === 'DONE' && s.adjustCount > 0) return '成功 · 调整' + s.adjustCount + '次'
  return RESULT_CN[s.resultStatus] || '-'
}

const fmtNum = (n) => (n == null ? 0 : n).toLocaleString()
const fmtCost = (c) => (c == null || c === '' ? '-' : '¥' + Number(c).toFixed(4))
const fmtMs = (ms) => {
  const v = ms == null ? 0 : ms
  return v >= 1000 ? (v / 1000).toFixed(1) + ' 秒' : v + ' 毫秒'
}
// DB 落库为 UTC 时间：转成北京时间展示
const fmtTime = (t) => {
  if (!t) return '-'
  const d = new Date(String(t).replace(' ', 'T') + 'Z')
  if (isNaN(d.getTime())) return String(t).slice(0, 19)
  return d.toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false })
}

const users = ref([])
const summary = ref(null)
const error = ref('')

// 会话维度审计
const sessions = ref([])
const sessionsTotal = ref(0)
const sessionsLoading = ref(false)
const sessionOffset = ref(0)
const SESSION_PAGE = 50
const fSessionId = ref('')
const fUsername = ref('')
const fLastStage = ref('')
const fResult = ref('')
const fDateFrom = ref('')
const fDateTo = ref('')

const sessionDetailId = ref(null)
const sessionDetail = ref([])
const sessionDetailLoading = ref(false)
const sessionTrace = ref(null)

// 模型超时口径（与后端 LlmConfig 一致，用于解读「为什么耗时长」）
const MODEL_TIMEOUT = {
  'qwen3.8-max': { timeoutSec: 300, retries: 0 },
  'qwen3.7-flash': { timeoutSec: 180, retries: 2 }
}
function timeHint(model, ms) {
  const cfg = MODEL_TIMEOUT[model]
  if (!cfg || !ms) return ''
  const attempts = Math.ceil(ms / (cfg.timeoutSec * 1000))
  if (attempts > 1) return '（≈' + attempts + ' 次尝试，含内置重试）'
  if (ms > cfg.timeoutSec * 1000) return '（超过单次超时上限）'
  return ''
}

// 分析：告警 / 漏斗 / 趋势 / 慢调用
const alerts = ref([])
const funnel = ref([])
const trend = ref([])
const slowCalls = ref([])
const slowSessionSec = ref(0)
const slowCallSec = ref(60)
const showResolvedSlow = ref(false)

async function resolveSlow(r) {
  const raw = window.prompt('解决说明（可留空）：', '')
  if (raw === null) return
  try {
    await api.post('/usage/admin/slow/' + r.id + '/resolve?note=' + encodeURIComponent(raw), {})
    await loadSlow()
  } catch (e) {
    error.value = e.message
  }
}

async function reopenSlow(r) {
  try {
    await api.post('/usage/admin/slow/' + r.id + '/reopen', {})
    await loadSlow()
  } catch (e) {
    error.value = e.message
  }
}

// 大段内容弹窗
const modal = ref(null)
function openModal(title, text) {
  let pretty = null
  if (text) {
    try {
      const parsed = JSON.parse(text)
      if (parsed != null && typeof parsed === 'object') pretty = JSON.stringify(parsed, null, 2)
    } catch (e) { /* 非 JSON 原样展示 */ }
  }
  modal.value = { title, text: pretty != null ? pretty : (text || ''), pretty: pretty != null }
}
function closeModal() { modal.value = null }
const cellText = (r) => r.question || r.answer || r.remark || '-'
function cellRender(r) {
  const t = cellText(r)
  return t.length > 100 ? t.slice(0, 100) + '…' : t
}

async function loadSummary() {
  const [u, s] = await Promise.all([
    api.get('/usage/admin/users'),
    api.get('/usage/admin/summary')
  ])
  users.value = u
  summary.value = s
}

async function loadSessions() {
  sessionsLoading.value = true
  try {
    const q = new URLSearchParams()
    if (fSessionId.value) q.set('sessionId', fSessionId.value)
    if (fUsername.value) q.set('username', fUsername.value)
    if (fLastStage.value) q.set('lastStage', fLastStage.value)
    if (fResult.value) q.set('resultStatus', fResult.value)
    if (fDateFrom.value) q.set('dateFrom', fDateFrom.value)
    if (fDateTo.value) q.set('dateTo', fDateTo.value)
    q.set('limit', SESSION_PAGE)
    q.set('offset', sessionOffset.value)
    const d = await api.get('/usage/admin/sessions?' + q.toString())
    sessions.value = d.list
    sessionsTotal.value = d.total
  } catch (e) {
    error.value = e.message
  } finally {
    sessionsLoading.value = false
  }
}

async function loadAnalytics() {
  try {
    const [a, f, t] = await Promise.all([
      api.get('/usage/admin/alerts?hours=24'),
      api.get('/usage/admin/funnel'),
      api.get('/usage/admin/trend?days=14')
    ])
    alerts.value = a
    funnel.value = f
    trend.value = t
  } catch (e) {
    error.value = e.message
  }
}

async function loadSlow() {
  try {
    slowCalls.value = await api.get('/usage/admin/slow?sessionTotalSec=' + (slowSessionSec.value || 0)
      + '&callSec=' + (slowCallSec.value == null || slowCallSec.value === '' ? 0 : slowCallSec.value)
      + '&limit=20&showResolved=' + (showResolvedSlow.value || false))
  } catch (e) {
    error.value = e.message
  }
}

async function openSession(s) {
  if (sessionDetailId.value === s.sessionId) {
    sessionDetailId.value = null
    sessionDetail.value = []
    return
  }
  sessionDetailLoading.value = true
  sessionDetailId.value = s.sessionId
  sessionDetail.value = []
  sessionTrace.value = null
  try {
    sessionDetail.value = await api.get('/usage/admin/sessions/' + encodeURIComponent(s.sessionId) + '/records')
    // 实时追踪链（内存 trace，重启后消失；非本人会话或已丢失时静默隐藏）
    try {
      sessionTrace.value = await api.get('/trace/detail?sessionId=' + encodeURIComponent(s.sessionId))
    } catch (e) { /* 忽略：无实时 trace 不影响明细 */ }
  } catch (e) {
    error.value = e.message
  } finally {
    sessionDetailLoading.value = false
  }
}

function prevSessionPage() {
  if (sessionOffset.value >= SESSION_PAGE) {
    sessionOffset.value -= SESSION_PAGE
    loadSessions()
  }
}

function nextSessionPage() {
  if (sessionOffset.value + SESSION_PAGE < sessionsTotal.value) {
    sessionOffset.value += SESSION_PAGE
    loadSessions()
  }
}

function applySessionFilters() {
  sessionOffset.value = 0
  sessionDetailId.value = null
  sessionDetail.value = []
  loadSessions()
}

function resetSessionFilters() {
  fSessionId.value = ''
  fUsername.value = ''
  fLastStage.value = ''
  fResult.value = ''
  fDateFrom.value = ''
  fDateTo.value = ''
  applySessionFilters()
}

function pickUser(u) {
  fUsername.value = u.username
  applySessionFilters()
}

function jumpToSession(sessionId) {
  fSessionId.value = sessionId
  applySessionFilters()
}

const stat = computed(() => {
  if (!summary.value) {
    return { calls: 0, tokens: 0, cost: null, users: 0 }
  }
  const calls = summary.value.byStage.reduce((a, x) => a + x.count, 0)
  const tokens = summary.value.byStage.reduce((a, x) => a + x.totalTokens, 0)
  const cost = summary.value.byStage.reduce((a, x) => (x.cost == null ? a : a + Number(x.cost)), 0)
  return {
    calls: fmtNum(calls),
    tokens: fmtNum(tokens),
    cost: cost === 0 ? '-' : '¥' + cost.toFixed(4),
    users: summary.value.byUser.length
  }
})

const maxStageTokens = computed(() =>
  Math.max(1, ...(summary.value?.byStage || []).map((x) => x.totalTokens)))
const maxChannelCount = computed(() =>
  Math.max(1, ...(summary.value?.byChannel || []).map((x) => x.count)))
const maxModelTokens = computed(() =>
  Math.max(1, ...(summary.value?.byModel || []).map((x) => x.totalTokens)))
const maxTrendTokens = computed(() =>
  Math.max(1, ...(trend.value || []).map((x) => x.totalTokens)))
const funnelMax = computed(() => Math.max(1, ...(funnel.value || []).map((x) => x.sessions)))

onMounted(async () => {
  try {
    await loadSummary()
    await loadSessions()
    await loadAnalytics()
    await loadSlow()
  } catch (e) {
    error.value = e.message
  }
})
</script>

<template>
  <div>
    <h2>消费日志审计台</h2>
    <p class="tip">按会话聚合展示一次规划的完整链路：点「查看规划」展开该会话的明细（阶段、动作、模型、token、耗时与返回内容），大段输出点「弹窗」查看。渠道说明：知识库=规则/数据库推荐，缓存翻页=换一批，AI 分析=大模型分析成功，规则兜底=AI 失败后回退知识库。</p>

    <div class="stat-grid">
      <div class="stat"><div class="label">总调用次数</div><div class="value">{{ stat.calls }}</div></div>
      <div class="stat"><div class="label">总 Token 消耗</div><div class="value">{{ stat.tokens }}</div></div>
      <div class="stat"><div class="label">预估总费用</div><div class="value">{{ stat.cost }}</div></div>
      <div class="stat"><div class="label">活跃用户数</div><div class="value">{{ stat.users }}</div></div>
    </div>

    <div class="error" v-if="error">{{ error }}</div>

    <div class="alert-box" v-if="alerts.length">
      <h4>⚠ 失败告警（近 24 小时有失败记录的会话，点击跳转会话）</h4>
      <ul>
        <li v-for="a in alerts" :key="a.sessionId" class="clickable" @click="jumpToSession(a.sessionId)">
          <b><code>{{ (a.sessionId || '').slice(0, 12) }}</code></b>（{{ a.username || '-' }}）{{ a.action || '-' }}
          <template v-if="a.model"> · {{ a.model }}</template>
          · 失败 {{ a.failCount }} 次
          <template v-if="a.remark"> · {{ a.remark }}</template>
          <template v-if="a.lastFailedAt"> · {{ fmtTime(a.lastFailedAt) }}</template>
        </li>
      </ul>
    </div>

    <!-- 会话漏斗 -->
    <div class="card" v-if="funnel.length">
      <h3>会话漏斗（到达各环节的会话数）</h3>
      <div class="bars">
        <div class="bar-row" v-for="(f, i) in funnel" :key="'fu' + i">
          <span class="name">{{ f.label }}</span>
          <div class="track">
            <div class="fill fill-green" :style="{ width: Math.max(1.5, (f.sessions / funnelMax) * 100) + '%' }"></div>
          </div>
          <span class="val">{{ f.sessions }} 会话{{
            i === 0 ? '' : '（' + Math.round((f.sessions / Math.max(1, funnel[0].sessions)) * 100) + '%）'
          }}</span>
        </div>
      </div>
    </div>

    <!-- 用量趋势 -->
    <div class="card" v-if="trend.length">
      <h3>近 {{ trend.length }} 天用量趋势（每日调用 / token / 费用）</h3>
      <div class="bars">
        <div class="bar-row" v-for="t in trend" :key="'td' + t.date">
          <span class="name">{{ t.date.slice(5) }}</span>
          <div class="track">
            <div class="fill" :style="{ width: Math.max(1.5, (t.totalTokens / maxTrendTokens) * 100) + '%' }"></div>
          </div>
          <span class="val">{{ t.calls }} 次 / {{ fmtNum(t.totalTokens) }} token / {{ fmtCost(t.cost) }}</span>
        </div>
      </div>
    </div>

    <!-- 慢调用排行 -->
    <div class="card">
      <h3>慢调用排行（AI 调用耗时分析）</h3>
      <div class="filters">
        <label class="tip">会话 AI 总耗时 ≥ <input v-model.number="slowSessionSec" type="number" min="0" style="width:80px" /> 秒</label>
        <label class="tip">单次调用 ≥ <input v-model.number="slowCallSec" type="number" min="0" style="width:80px" /> 秒</label>
        <button class="btn small" @click="loadSlow">查询</button>
        <label class="tip" style="margin-left:8px">
          <input type="checkbox" v-model="showResolvedSlow" @change="loadSlow" /> 显示已解决
        </label>
        <span class="tip">两个条件同时满足才展示；填 0 表示不过滤该维度。处理完的慢调用点「标记已解决」后默认不再出现。</span>
      </div>
      <table class="tb" v-if="slowCalls.length">
        <thead>
          <tr><th>时间</th><th>会话</th><th>阶段</th><th>动作</th><th>模型</th><th>耗时</th><th>状态</th><th>备注</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in slowCalls" :key="'sl' + r.id">
            <td>{{ fmtTime(r.createdAt) }}</td>
            <td><code>{{ (r.sessionId || '').slice(0, 12) }}</code></td>
            <td>{{ stageCn(r.stage) }}</td>
            <td>{{ r.action }}</td>
            <td>{{ r.model || '-' }}</td>
            <td>{{ fmtMs(r.durationMs) }}{{ timeHint(r.model, r.durationMs) }}</td>
            <td>
              <span class="badge" :class="r.status === 'SUCCESS' ? 'ok' : 'bad'">{{ r.status }}</span>
              <span v-if="r.resolved" class="badge warn">已解决</span>
            </td>
            <td class="qa-cell">
              {{ r.remark || '-' }}
              <template v-if="r.resolved"> · 解决说明：{{ r.resolvedNote || '（无）' }}（{{ r.resolvedBy || '管理员' }}）</template>
            </td>
            <td>
              <button v-if="!r.resolved" class="btn small" @click="resolveSlow(r)">标记已解决</button>
              <button v-else class="btn small ghost" @click="reopenSlow(r)">恢复未解决</button>
            </td>
          </tr>
        </tbody>
      </table>
      <p class="tip" v-if="!slowCalls.length">当前条件下暂无慢调用</p>
    </div>

    <div class="card" v-if="summary">
      <h3>各阶段 Token 分布</h3>
      <div class="bars">
        <div class="bar-row" v-for="x in summary.byStage" :key="'st' + x.key">
          <span class="name">{{ stageCn(x.key) }}</span>
          <div class="track">
            <div class="fill" :style="{ width: Math.max(1.5, (x.totalTokens / maxStageTokens) * 100) + '%' }"></div>
          </div>
          <span class="val">{{ fmtNum(x.totalTokens) }} token / {{ fmtCost(x.cost) }}</span>
        </div>
      </div>
    </div>

    <div class="card" v-if="summary">
      <h3>渠道调用分布（这一步走了哪条链路）</h3>
      <div class="bars">
        <div class="bar-row" v-for="x in summary.byChannel" :key="'ch' + x.key">
          <span class="name">{{ channelCn(x.key) }}</span>
          <div class="track">
            <div class="fill fill-green" :style="{ width: Math.max(1.5, (x.count / maxChannelCount) * 100) + '%' }"></div>
          </div>
          <span class="val">{{ x.count }} 次 / {{ fmtNum(x.totalTokens) }} token</span>
        </div>
      </div>
    </div>

    <div class="card" v-if="summary && summary.byModel.length">
      <h3>各模型消耗与费用</h3>
      <div class="bars">
        <div class="bar-row" v-for="x in summary.byModel" :key="'md' + x.key">
          <span class="name">{{ x.key }}</span>
          <div class="track">
            <div class="fill fill-orange" :style="{ width: Math.max(1.5, (x.totalTokens / maxModelTokens) * 100) + '%' }"></div>
          </div>
          <span class="val">{{ fmtNum(x.totalTokens) }} token / {{ fmtCost(x.cost) }}</span>
        </div>
      </div>
    </div>

    <div class="card" v-if="summary && summary.byUser.length">
      <h3>按用户汇总（点击「查看明细」筛选该用户）</h3>
      <table class="tb">
        <thead>
          <tr><th>用户</th><th>调用次数</th><th>输入 token</th><th>输出 token</th><th>总 token</th><th>预估费用</th><th>最近活跃</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="u in summary.byUser" :key="u.userId" :class="{ clickable: true }" @click="pickUser(u)">
            <td>{{ u.username }}</td>
            <td>{{ fmtNum(u.count) }}</td>
            <td>{{ fmtNum(u.inputTokens) }}</td>
            <td>{{ fmtNum(u.outputTokens) }}</td>
            <td>{{ fmtNum(u.totalTokens) }}</td>
            <td>{{ fmtCost(u.cost) }}</td>
            <td>{{ fmtTime(u.lastActiveAt) }}</td>
            <td><button class="btn small ghost">查看明细</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <h3>会话审计（按会话聚合，共 {{ fmtNum(sessionsTotal) }} 个会话）</h3>
      <p class="tip">一次规划会话一行（# 为本次查询内的序号，悬停会话列可见完整 ID）。「结果」口径：成功=行程已发布；失败=某一步输出错误（悬停看原因）；未完成=会话过期/重新开始/仍在进行。「调整子任务」为历史 adj- 会话（新调整已归并进父会话）。</p>
      <div class="filters">
        <input v-model="fSessionId" type="text" placeholder="会话ID（模糊）" @keyup.enter="applySessionFilters" />
        <input v-model="fUsername" type="text" placeholder="用户名（模糊）" @keyup.enter="applySessionFilters" />
        <select v-model="fLastStage">
          <option value="">全部阶段</option>
          <option v-for="(v, k) in STAGE_CN" :key="k" :value="k">{{ v }}</option>
        </select>
        <select v-model="fResult">
          <option value="">全部结果</option>
          <option value="DONE">成功</option>
          <option value="FAILED">失败</option>
          <option value="TIMEOUT">失败·超时</option>
          <option value="ONGOING">未完成·进行中</option>
          <option value="EXPIRED">未完成·会话过期</option>
          <option value="RESTARTED">未完成·重新开始</option>
        </select>
        <input v-model="fDateFrom" type="date" title="开始日期" />
        <span class="tip">至</span>
        <input v-model="fDateTo" type="date" title="结束日期" />
        <button class="btn small" @click="applySessionFilters">查询</button>
        <button class="btn small ghost" @click="resetSessionFilters">重置</button>
      </div>
      <div class="loading" v-if="sessionsLoading">加载中……</div>
      <table class="tb" v-if="sessions.length">
        <thead>
          <tr>
            <th>#</th><th>会话</th><th>用户</th><th>目的地</th><th>记录数</th><th>入token</th><th>出token</th>
            <th>AI总耗时</th><th>费用</th><th>开始时间</th><th>最后阶段</th><th>结果</th><th></th>
          </tr>
        </thead>
        <tbody>
          <template v-for="(s, i) in sessions" :key="s.sessionId">
            <tr class="clickable" @click="openSession(s)">
              <td>{{ sessionOffset + i + 1 }}</td>
              <td>
                <code :title="s.sessionId">{{ (s.sessionId || '').slice(0, 10) }}</code>
                <span class="badge OP" v-if="s.subTask">调整子任务</span>
              </td>
              <td>{{ s.username || '-' }}</td>
              <td>{{ s.destinationName || '-' }}</td>
              <td>{{ fmtNum(s.recordCount) }}</td>
              <td>{{ fmtNum(s.inputTokens) }}</td>
              <td>{{ fmtNum(s.outputTokens) }}</td>
              <td>{{ fmtMs(s.totalAgentMs) }}</td>
              <td>{{ fmtCost(s.cost) }}</td>
              <td>{{ fmtTime(s.firstAt) }}</td>
              <td>{{ stageCn(s.lastStage) }}</td>
              <td>
                <span class="badge" :class="resultBadge(s.resultStatus)" :title="s.failRemark || ''">
                  {{ resultText(s) }}
                </span>
              </td>
              <td><button class="btn small ghost">{{ sessionDetailId === s.sessionId ? '收起' : '查看规划' }}</button></td>
            </tr>
            <tr v-if="sessionDetailId === s.sessionId" class="expand-row">
              <td colspan="13">
                <div class="loading" v-if="sessionDetailLoading">加载中……</div>
                <template v-else>
                  <div class="tip">会话完整链路（按时间正序，北京时间）；内容列截断展示，点击「弹窗」查看完整输出。模型超时口径：行程规划 qwen3.8-max 300 秒/次（不重试）· 偏好/候选 qwen3.7-flash 180 秒/次（含 2 次内置重试）· 行程修复 120 秒/次（不重试）——耗时为单次超时整数倍时通常发生了内置重试。</div>
                  <div class="card inner" v-if="sessionTrace">
                    <div class="tip">实时追踪链（内存记录，服务重启后消失；只对当前用户可见）：
                      状态 <span class="badge" :class="sessionTrace.status === 'SUCCESS' ? 'ok' : 'bad'">{{ sessionTrace.status }}</span>
                      · 总耗时 {{ fmtMs(sessionTrace.durationMs) }}
                      <template v-if="sessionTrace.question"> · 问题：{{ sessionTrace.question }}</template>
                    </div>
                    <table class="tb inner">
                      <thead>
                        <tr><th>Agent</th><th>耗时</th><th>成功</th><th>Provider</th><th>解析</th><th>校验</th><th>Token</th><th>失败原因</th></tr>
                      </thead>
                      <tbody>
                        <tr v-for="(a, i) in sessionTrace.agents" :key="'tg' + i">
                          <td>{{ a.agent }}</td>
                          <td>{{ fmtMs(a.durationMs) }}</td>
                          <td><span class="badge" :class="a.success ? 'ok' : 'bad'">{{ a.success ? '成功' : '失败' }}</span></td>
                          <td>{{ a.providerStatus || '-' }}</td>
                          <td>{{ a.parseStatus || '-' }}</td>
                          <td>{{ a.validationStatus || '-' }}</td>
                          <td>{{ a.totalTokens ?? '-' }}</td>
                          <td class="qa-cell">{{ a.error || a.fallbackReason || '-' }}</td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                  <table class="tb inner">
                    <thead>
                      <tr>
                        <th>时间</th><th>阶段</th><th>动作</th><th>渠道</th><th>模型</th>
                        <th>入token</th><th>出token</th><th>耗时</th><th>费用</th><th>状态</th><th>内容</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="r in sessionDetail" :key="'d' + r.id">
                        <td>{{ fmtTime(r.createdAt) }}</td>
                        <td>{{ stageCn(r.stage) }}</td>
                        <td>{{ r.action }}</td>
                        <td><span class="badge" :class="r.channel || 'OP'">{{ channelCn(r.channel || 'OP') }}</span></td>
                        <td>{{ r.model || '-' }}</td>
                        <td>{{ fmtNum(r.inputTokens) }}</td>
                        <td>{{ fmtNum(r.outputTokens) }}</td>
                        <td>{{ fmtMs(r.durationMs) }}{{ timeHint(r.model, r.durationMs) }}</td>
                        <td>{{ fmtCost(r.cost) }}</td>
                        <td><span class="badge" :class="r.status === 'SUCCESS' ? 'ok' : 'bad'">{{ r.status }}</span></td>
                        <td class="qa-cell">
                          {{ cellRender(r) }}
                          <button v-if="cellText(r).length > 100" class="btn small ghost"
                                  @click.stop="openModal((r.question ? '用户问题 / ' : '') + (r.action || '') + ' · ' + fmtTime(r.createdAt), r.question || r.answer || r.remark)">
                            弹窗
                          </button>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </template>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
      <p class="tip" v-if="!sessions.length && !sessionsLoading">暂无会话记录</p>
      <div class="pager" v-if="sessionsTotal > 0">
        <button class="btn small ghost" :disabled="sessionOffset < SESSION_PAGE" @click="prevSessionPage">上一页</button>
        <span>第 {{ Math.floor(sessionOffset / SESSION_PAGE) + 1 }} 页 / 共 {{ Math.ceil(sessionsTotal / SESSION_PAGE) }} 页</span>
        <button class="btn small ghost" :disabled="sessionOffset + SESSION_PAGE >= sessionsTotal" @click="nextSessionPage">下一页</button>
      </div>
    </div>

    <!-- 大段输出弹窗 -->
    <div class="modal-mask" v-if="modal" @click.self="closeModal">
      <div class="modal-box">
        <div class="modal-head">
          <h4>{{ modal.title }}</h4>
          <button class="btn small ghost" @click="closeModal">关闭</button>
        </div>
        <div class="modal-body">
          <pre v-if="modal.pretty">{{ modal.text }}</pre>
          <pre v-else>{{ modal.text }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>
