<script setup>
// 主规划流程：会话创建 → 偏好问询（选项按钮/自由输入）→ 景点/美食/酒店候选勾选（支持换一批）
// → 行程生成（时间线 + 地点超链接路径弹层）
import { ref, computed, onMounted, nextTick, watch } from 'vue'
import { api } from '../api'
import ItineraryTimeline from '../components/ItineraryTimeline.vue'
import RouteDialog from '../components/RouteDialog.vue'

const params = new URLSearchParams((location.hash.split('?')[1] || ''))
const destinationId = Number(params.get('destinationId'))
const destinationName = params.get('name') || '目的地'
// 断点恢复：URL 携带 sessionId 时走恢复分支（重载快照+历史+进行中操作），否则照旧新建会话
const resumeSessionId = params.get('sessionId') || ''
// 本标签页最近活跃会话：中途离开再回到规划页时自动恢复（新建会话时由 HomeView 置 plan.new 标记区分）
const ACTIVE_SESSION_KEY = 'plan.activeSession'

const STEPS = [
  { key: 'PREFERENCE', label: '偏好问询' },
  { key: 'ATTRACTIONS', label: '选择景点' },
  { key: 'FOODS', label: '选择美食' },
  { key: 'HOTELS', label: '选择酒店' },
  { key: 'DONE', label: '生成行程' }
]

const sessionId = ref('')
const stage = ref('INIT')
const question = ref(null)
const preference = ref(null)
const chatHistory = ref([])
const attractionCandidates = ref([])
const foodCandidates = ref([])
const hotelCandidates = ref([])
const webFoodCandidates = ref([])
const selectedIds = ref([])
const itineraryId = ref(null)
const itineraryText = ref('')
const plan = ref(null)
const freeInput = ref('')
const loading = ref(false)
const error = ref('')
// S06-B：会话权威快照版本（写操作回传 expectedRevision）+ 受理中的操作 id
const sessionRevision = ref(null)
const pendingOpId = ref(null)

const routeDialog = ref(null)
const chatBox = ref(null)
const agentWorking = ref('')
const aiAdvice = ref('')
// 断点恢复失败（会话已过期/不存在）时展示「重新开始 / 返回首页」
const resumeExpired = ref(false)
// B 方案：疲劳超载草稿待确认（快照 pendingFatigueConfirm 驱动确认卡片）
const pendingFatigue = ref(false)
const fatigueScore = ref(null)
// 预算超支知情放行（快照 pendingBudgetConfirm 驱动放行卡片）
const pendingBudget = ref(false)
const budgetOver = ref(null)

// 新消息到达后自动滚动到对话底部
watch(() => chatHistory.value.length, async () => {
  await nextTick()
  if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight
})

const agentLabel = computed(() => ({ ATTRACTION: 'AttractionAgent', FOOD: 'FoodAgent', HOTEL: 'HotelAgent' })[candidateType.value] || '')
const agentTopic = computed(() => ({ ATTRACTION: '景点', FOOD: '美食', HOTEL: '酒店' })[candidateType.value] || '')

// 标签徽章：逗号分隔标签串 → 数组（最多展示 5 个）
function splitTags(tags) {
  if (!tags) return []
  return String(tags).split(/[,，、;；]/).map(t => t.trim()).filter(Boolean).slice(0, 5)
}
// 评分明细 tooltip：基础分 + 标签加成/冲突说明
function scoreTitle(c) {
  if (!c || c.score == null) return ''
  const base = c.tagNote ? '综合评分（含标签加成：' + c.tagNote + '）' : '综合评分（路径优/成本/需求匹配）'
  return base
}

const showInput = computed(() => stage.value === 'PREFERENCE' || !!candidateType.value || isDone.value)

const inputPlaceholder = computed(() => {
  if (stage.value === 'PREFERENCE') return '可以直接输入想法，如：3天，预算3000元，2个人'
  if (candidateType.value === 'ATTRACTION') return '比如：想找能看夜景的景点，不要太累'
  if (candidateType.value === 'FOOD') return '比如：人均50以内，想吃面食；想要情侣氛围的餐厅'
  if (candidateType.value === 'HOTEL') return '比如：离地铁站近一点、安静一些'
  if (isDone.value) return '对行程不满意？直接说：第二天太赶，删一个景点'
  return '说说你的想法'
})

const stepIndex = computed(() => {
  const i = STEPS.findIndex(s => s.key === stage.value)
  return i < 0 ? 0 : i
})

const candidateType = computed(() => {
  if (stage.value === 'ATTRACTIONS') return 'ATTRACTION'
  if (stage.value === 'FOODS') return 'FOOD'
  if (stage.value === 'HOTELS') return 'HOTEL'
  return null
})

const candidates = computed(() => {
  if (stage.value === 'ATTRACTIONS') return attractionCandidates.value
  if (stage.value === 'FOODS') return foodCandidates.value
  if (stage.value === 'HOTELS') return hotelCandidates.value
  return []
})

const isDone = computed(() => stage.value === 'DONE')

// 美食候选分组：有餐次标注（餐次需求存在）按早餐/午餐/晚餐/小吃分组，否则按风味分组
const MEAL_ORDER = { 早餐: 0, 午餐: 1, 晚餐: 2, 小吃: 3 }
const foodGroups = computed(() => {
  const groups = new Map()
  for (const g of foodCandidates.value) {
    for (const r of g.restaurants) {
      // 只有后端按餐次结构标注（r.mealType）的才是餐次组；风味恰巧叫「小吃」时仍按风味展示
      const key = r.mealType || g.cuisine
      if (!groups.has(key)) groups.set(key, { isMeal: false, items: [] })
      const grp = groups.get(key)
      if (r.mealType) grp.isMeal = true
      grp.items.push(r)
    }
  }
  return [...groups.keys()]
    .sort((a, b) => {
      const oa = MEAL_ORDER[a] ?? 4
      const ob = MEAL_ORDER[b] ?? 4
      if (oa !== ob) return oa - ob
      return String(a).localeCompare(String(b), 'zh')
    })
    .map(k => ({ label: k, isMeal: groups.get(k).isMeal, items: groups.get(k).items }))
})

const mealPlanText = computed(() => {
  const mp = preference.value && preference.value.mealPlan
  if (!mp) return ''
  const parts = []
  if (mp.breakfastPerDay != null) parts.push('早餐 ' + mp.breakfastPerDay + '/天')
  if (mp.lunchPerDay != null) parts.push('午餐 ' + mp.lunchPerDay + '/天')
  if (mp.dinnerPerDay != null) parts.push('晚餐 ' + mp.dinnerPerDay + '/天')
  // 与后端排除口径一致：提出餐次/正餐需求即默认不含小吃，显式要小吃才保留
  if (mp.snacksAllowed === true) parts.push('含小吃')
  else if (mp.breakfastPerDay != null || mp.lunchPerDay != null || mp.dinnerPerDay != null || mp.snacksAllowed === false) parts.push('不含小吃')
  return parts.join(' · ')
})

function applyStep(s) {
  if (s.sessionId) {
    sessionId.value = s.sessionId
    sessionStorage.setItem(ACTIVE_SESSION_KEY, s.sessionId)
  }
  stage.value = s.stage || stage.value
  // 幂等同步：同一条助手消息不重复渲染（快照恢复/页面切回会反复调用 applyStep）
  if (s.message) {
    const last = chatHistory.value[chatHistory.value.length - 1]
    if (!last || last.role !== 'assistant' || last.text !== s.message) {
      chatHistory.value.push({ role: 'assistant', text: s.message })
    }
  }
  if (s.conflict) chatHistory.value.push({ role: 'assistant', text: '⚠ ' + s.conflict })
  question.value = s.question || null
  if (s.preference) preference.value = s.preference
  attractionCandidates.value = s.attractionCandidates || []
  foodCandidates.value = s.foodCandidates || []
  hotelCandidates.value = s.hotelCandidates || []
  webFoodCandidates.value = s.webFoodCandidates || []
  // 按当前阶段恢复服务端已勾选（同时覆盖 409 重载丢选中的场景）
  if (s.stage === 'ATTRACTIONS') selectedIds.value = [...(s.selectedAttractionIds || [])]
  else if (s.stage === 'FOODS') selectedIds.value = [...(s.selectedFoodIds || [])]
  else if (s.stage === 'HOTELS') selectedIds.value = [...(s.selectedHotelIds || [])]
  else selectedIds.value = []
  if (s.itineraryId) itineraryId.value = s.itineraryId
  if (s.sessionRevision != null) sessionRevision.value = s.sessionRevision
  if (s.itineraryText) itineraryText.value = s.itineraryText
  if (s.plan) plan.value = s.plan
  aiAdvice.value = s.candidateAdvice || ''
  // B 方案：待确认标记（后端快照与步进结果均携带该字段，null 即未进入待确认）
  if (s.pendingFatigueConfirm !== undefined) {
    pendingFatigue.value = !!s.pendingFatigueConfirm
    fatigueScore.value = s.pendingFatigueScore ?? null
  }
  if (s.pendingBudgetConfirm !== undefined) {
    pendingBudget.value = !!s.pendingBudgetConfirm
    budgetOver.value = s.pendingBudgetOver ?? null
  }
}

onMounted(async () => {
  loading.value = true
  try {
    if (resumeSessionId) {
      await resumeFlow()
    } else if (sessionStorage.getItem('plan.new') === '1') {
      // 首页目的地卡片显式发起：新建会话（覆盖之前记录）
      sessionStorage.removeItem('plan.new')
      agentWorking.value = '正在建立规划会话…'
      applyStep(await api.post('/travel/session', { destinationId }))
    } else {
      const active = sessionStorage.getItem(ACTIVE_SESSION_KEY)
      if (active) {
        // 中途离开后回到规划页：自动恢复最近会话（后端仍在分析/生成时显示等待并轮询）
        await autoResume(active)
      } else {
        agentWorking.value = '正在建立规划会话…'
        applyStep(await api.post('/travel/session', { destinationId }))
      }
    }
  } catch (e) {
    error.value = e.message
    if (resumeSessionId) resumeExpired.value = true
  } finally {
    loading.value = false
  }
})

/** 回页自动恢复：快照同步 + 后端仍在偏好分析/生成时轮询直至阶段推进（原请求在服务端继续执行） */
async function autoResume(id) {
  agentWorking.value = '正在恢复上次会话…'
  let r = null
  try {
    r = await api.get('/travel/session/resumable?sessionId=' + encodeURIComponent(id))
  } catch (e) { /* 网络异常按不可恢复处理 */ }
  if (!r || r.sessionId !== id) {
    sessionStorage.removeItem(ACTIVE_SESSION_KEY)
    if (destinationId) {
      agentWorking.value = '正在建立规划会话…'
      applyStep(await api.post('/travel/session', { destinationId }))
      return
    }
    resumeExpired.value = true
    error.value = '上次会话已过期，可重新开始规划'
    return
  }
  const s = await api.get('/travel/session/' + id)
  applyStep(s)
  const last = chatHistory.value[chatHistory.value.length - 1]
  if (last && last.role === 'assistant' && last.text === '已同步最新会话状态。') {
    last.text = '已恢复上次会话，继续你的规划。'
  } else if (!(last && last.role === 'assistant' && last.text === '已恢复上次会话，继续你的规划。')) {
    chatHistory.value.push({ role: 'assistant', text: '已恢复上次会话，继续你的规划。' })
  }
  if (r.generating && s.stage === 'PREFERENCE') {
    // 后端仍在做偏好分析：显示等待并轮询快照，阶段推进即同步（不再需要手动刷新）
    agentWorking.value = 'AI 正在结合你的偏好分析，正在等待结果…'
    const deadline = Date.now() + 180000
    while (Date.now() < deadline) {
      await new Promise((w) => setTimeout(w, 1000))
      const s2 = await api.get('/travel/session/' + id)
      if (s2.stage !== 'PREFERENCE') {
        applyStep(s2)
        break
      }
    }
    agentWorking.value = ''
  } else if (r.generating && s.stage === 'ITINERARY') {
    agentWorking.value = 'AI 正在生成中，正在等待结果…'
    await pollGenerationUntilDone()
    agentWorking.value = ''
  }
}

// 标签页切回时同步最新会话状态（用户可能在新标签页/后台停留后回来）
document.addEventListener('visibilitychange', async () => {
  if (document.visibilityState !== 'visible') return
  if (!sessionId.value || loading.value) return
  try {
    const s = await api.get('/travel/session/' + sessionId.value)
    // 无变化的切回同步不重复渲染同步文案，避免刷屏
    if (s && s.message === '已同步最新会话状态。' && s.stage === stage.value) {
      delete s.message
    }
    applyStep(s)
  } catch (e) { /* 会话可能已过期：界面维持现状 */ }
})

/** 断点恢复：按 sessionId 校验仍可恢复 → 回放聊天历史 → 重载快照 → 有进行中操作则轮询至终态 */
async function resumeFlow() {
  agentWorking.value = '正在恢复上次会话…'
  const r = await api.get('/travel/session/resumable?sessionId=' + encodeURIComponent(resumeSessionId))
  if (!r || r.sessionId !== resumeSessionId) {
    throw new Error('上次会话已过期，可重新开始规划')
  }
  // 历史回放失败不阻断恢复（会话状态本身仍可恢复）
  try {
    const history = await api.get('/travel/session/' + resumeSessionId + '/history') || []
    for (const h of history) {
      if (h && h.text) chatHistory.value.push({ role: h.role === 'user' ? 'user' : 'assistant', text: h.text })
    }
  } catch (e) { /* 忽略：仅聊天记录缺失 */ }
  const s = await api.get('/travel/session/' + resumeSessionId)
  applyStep(s)
  // 快照同步文案换成恢复语义；当前问题若未在历史末尾重复出现则补一条
  const last = chatHistory.value[chatHistory.value.length - 1]
  if (last && last.role === 'assistant' && last.text === '已同步最新会话状态。') {
    last.text = '已恢复上次会话，继续你的规划。'
  }
  if (s.question && s.question.text) {
    const tail = chatHistory.value[chatHistory.value.length - 1]
    if (!tail || tail.role !== 'assistant' || tail.text !== s.question.text) {
      chatHistory.value.push({ role: 'assistant', text: s.question.text })
    }
  }
  if (r.activeOperationId) {
    pendingOpId.value = r.activeOperationId
    agentWorking.value = 'AI 正在生成中，正在等待结果…'
    await pollOperationResume()
    pendingOpId.value = null
  } else if (r.generating) {
    // 同步生成进行中：轮询会话快照直至 DONE（不重复触发生成，避免浪费模型资源）
    agentWorking.value = 'AI 正在生成中，正在等待结果…'
    await pollGenerationUntilDone()
  }
}

/** 同步生成中轮询：只读会话快照直至 stage=DONE 或进入待确认（疲劳超载/预算超支草稿）；超时提示可重试（不自动重新生成） */
async function pollGenerationUntilDone() {
  const deadline = Date.now() + 180000
  for (;;) {
    const s = await api.get('/travel/session/' + sessionId.value)
    if (s.stage === 'DONE' || s.pendingFatigueConfirm || s.pendingBudgetConfirm) {
      applyStep(s)
      return
    }
    if (Date.now() > deadline) {
      error.value = '生成超时未完成，请刷新页面后重新确认选择'
      return
    }
    await new Promise((r) => setTimeout(r, 500))
  }
}

/** 恢复场景轮询：终态各自落提示/错误；超时降级为提示（稍后回来仍可继续恢复） */
async function pollOperationResume() {
  const deadline = Date.now() + 120000
  for (;;) {
    const v = await api.get('/travel/operations/' + pendingOpId.value)
    if (v.status === 'COMPLETED') {
      applyStep(await api.get('/travel/session/' + sessionId.value))
      return
    }
    if (v.status === 'FAILED') {
      error.value = v.errorDetail || v.errorCode || '上次生成失败，可重新生成'
      return
    }
    if (v.status === 'NEEDS_CONFIRMATION') {
      let detail = v.errorDetail
      try {
        const d = JSON.parse(detail || '{}')
        detail = d.userMessage || d.message || detail
      } catch (e) { /* 保留原文 */ }
      error.value = detail || '行程生成未通过发布检查，需调整后重试'
      return
    }
    if (v.status === 'UNKNOWN') {
      error.value = '上次操作结果未知（可能已产生模型费用），请勿更换 requestId 重试'
      return
    }
    if (v.status === 'CANCELLED') {
      error.value = '上次生成已取消，可重新生成'
      return
    }
    if (v.status === 'DEADLINE_EXCEEDED') {
      error.value = '上次生成超时未完成，可重新生成'
      return
    }
    if (Date.now() > deadline) {
      error.value = 'AI 仍在生成中，稍后重新进入本页即可看到结果'
      return
    }
    await new Promise((r) => setTimeout(r, 500))
  }
}

async function sendChat(text, onFail) {
  const t = (text || '').trim()
  if (!t) return
  chatHistory.value.push({ role: 'user', text: t })
  // 即时确认气泡：模型往返期间用户也能立刻看到「已收到、正在分析」的交互反馈
  const ack = { role: 'assistant', text: '收到，正在结合你的想法进行分析…', pending: true }
  chatHistory.value.push(ack)
  question.value = null
  loading.value = true
  error.value = ''
  if (stage.value === 'PREFERENCE') agentWorking.value = 'PreferenceAgent 正在理解你的想法…'
  else if (isDone.value) agentWorking.value = 'ItineraryAgent 正在为你调整行程…'
  else agentWorking.value = (agentLabel.value || 'AI') + ' 正在结合你的想法进行分析…'
  const dropAck = () => { chatHistory.value = chatHistory.value.filter(m => !m.pending) }
  try {
    const resp = await api.post('/travel/chat/sync', { sessionId: sessionId.value, message: t })
    applyStep(resp)
    dropAck()
  } catch (e) {
    dropAck()
    // S06-B §11.1：409 重新加载服务端状态，同时保留用户未提交输入；422 展示具体违规
    if (e.status === 409) {
      try {
        applyStep(await api.get('/travel/session/' + sessionId.value))
        if (onFail) onFail()
        error.value = '服务端状态已更新，请查看最新状态后重试：' + e.message
        return
      } catch (e2) {
        error.value = e2.message
        if (onFail) onFail()
        return
      }
    }
    error.value = e.message
    if (onFail) onFail()
  } finally {
    loading.value = false
  }
}

function sendFree() {
  const t = freeInput.value
  freeInput.value = ''
  sendChat(t, () => { freeInput.value = t })
}

function toggle(id) {
  const i = selectedIds.value.indexOf(id)
  if (i >= 0) selectedIds.value.splice(i, 1)
  else selectedIds.value.push(id)
}

async function confirmSelection(regenerate) {
  loading.value = true
  error.value = ''
  if (regenerate) {
    agentWorking.value = '正在从备选池为你翻页…'
  } else if (candidateType.value === 'HOTEL') {
    agentWorking.value = 'ItineraryAgent 正在结合偏好、劳累度和饭点编排行程…'
  } else {
    agentWorking.value = '正在生成下一级备选池…'
  }
  try {
    applyStep(await api.post('/travel/candidates/confirm', {
      sessionId: sessionId.value,
      candidateType: candidateType.value,
      // 换一批也带上已勾选：后端会把已选项跨批次保留置顶，且不重复出现
      selectedIds: selectedIds.value,
      regenerate: !!regenerate
    }))
    // 服务端返回「生成中」守卫（上一轮生成仍在进行）：轮询快照直至 DONE，不重复提交
    if (!regenerate && stage.value === 'ITINERARY') {
      agentWorking.value = 'AI 正在生成中，正在等待结果…'
      await pollGenerationUntilDone()
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

/** B 方案：疲劳超载草稿裁决（确认生成发布行程 / 返回调整回景点选择） */
async function confirmFatigue(confirm) {
  loading.value = true
  error.value = ''
  try {
    applyStep(await api.post('/travel/session/' + sessionId.value + '/fatigue-confirm', { confirm }))
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

/** 预算超支知情放行裁决（确认发布按超支自付 / 返回调整回美食选择） */
async function confirmBudget(confirm) {
  loading.value = true
  error.value = ''
  try {
    applyStep(await api.post('/travel/session/' + sessionId.value + '/budget-confirm', { confirm }))
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function regenerateItinerary() {
  loading.value = true
  error.value = ''
  agentWorking.value = '正在受理生成请求（operation）…'
  const requestId = (crypto.randomUUID && crypto.randomUUID()) || String(Date.now())
  try {
    // S06-B：202 受理 → 轮询 operation；轮询不重新生成，重试用同一 requestId
    const accepted = await api.post('/travel/operations/generate', {
      sessionId: sessionId.value,
      requestId,
      expectedRevision: sessionRevision.value ?? 0
    })
    pendingOpId.value = accepted.operationId
    await pollOperation()
  } catch (e) {
    if (e.status === 503 && pendingOpId.value) {
      // 受理前存储故障：先按原 requestId 查已有操作，不盲目重新生成
      error.value = ''
      try {
        await pollOperation()
      } catch (e2) {
        error.value = e2.message
      }
    } else {
      error.value = e.message
    }
  } finally {
    loading.value = false
    pendingOpId.value = null
  }
}

/** 轮询操作至终态：COMPLETED 后重载会话（已提交结果）；422 时错误体已带 violations 文案 */
async function pollOperation() {
  const deadline = Date.now() + 60000
  for (;;) {
    const v = await api.get('/travel/operations/' + pendingOpId.value)
    if (v.status === 'COMPLETED') {
      applyStep(await api.get('/travel/session/' + sessionId.value))
      return
    }
    if (v.status === 'FAILED') {
      throw new Error(v.errorDetail || v.errorCode || '操作失败')
    }
    if (v.status === 'NEEDS_CONFIRMATION') {
      let detail = v.errorDetail
      try {
        const d = JSON.parse(detail || '{}')
        detail = d.userMessage || d.message || detail
      } catch (e) { /* 保留原文 */ }
      throw new Error(detail || '行程生成未通过发布检查，需调整后重试')
    }
    if (v.status === 'UNKNOWN') {
      throw new Error('上次操作结果未知（可能已产生模型费用），请勿更换 requestId 重试')
    }
    if (Date.now() > deadline) throw new Error('生成超时，请按原 requestId 查询结果')
    await new Promise((r) => setTimeout(r, 500))
  }
}

function openRoute(payload) {
  routeDialog.value = {
    mode: 'session',
    sessionId: sessionId.value,
    day: payload.day.dayIndex,
    fromSeq: payload.prev.seq,
    toSeq: payload.node.seq,
    fromName: payload.prev.name,
    toName: payload.node.name
  }
}
</script>

<template>
  <div>
    <h2>规划 · {{ destinationName }}</h2>

    <!-- 步骤条 -->
    <div class="steps">
      <div v-for="(s, i) in STEPS" :key="s.key" class="step"
           :class="{ active: i === stepIndex, done: i < stepIndex }">{{ s.label }}</div>
    </div>

    <!-- 会话信息 -->
    <div class="card" v-if="preference">
      <h3>当前偏好</h3>
      <p class="tip">
        天数：{{ preference.days ?? '-' }} ｜ 预算：{{ preference.totalBudget ?? '-' }} 元 ｜
        人数：{{ preference.peopleCount ?? '-' }} 人 ｜ 景点偏好：{{ preference.attractionType ?? '-' }} ｜
        口味：{{ preference.foodTaste ?? '-' }} ｜ 体力：{{ preference.energyLevel ?? '-' }} ｜
        酒店：{{ preference.hotelStyle ?? '-' }}
        <template v-if="mealPlanText"> ｜ 餐次：{{ mealPlanText }}</template>
      </p>
    </div>

    <!-- 对话历史 -->
    <div class="card" v-if="chatHistory.length">
      <div class="chat" ref="chatBox">
        <div v-for="(m, i) in chatHistory" :key="i" class="msg" :class="m.role">
          <span class="bubble">{{ m.text }}</span>
        </div>
      </div>

      <!-- 偏好问询选项按钮 -->
      <template v-if="stage === 'PREFERENCE' && question">
        <div class="options">
          <button v-for="(o, i) in question.options" :key="i" class="opt" @click="sendChat(o)">{{ o }}</button>
        </div>
      </template>

      <!-- 全阶段自由输入：既可选按钮，也可以随时和 AI 对话 -->
      <div v-if="showInput" class="input-row">
        <input v-model="freeInput" type="text" :placeholder="inputPlaceholder" @keyup.enter="sendFree" />
        <button class="btn" :disabled="!freeInput.trim() || loading" @click="sendFree">发送</button>
      </div>
    </div>

    <!-- 候选确认 -->
    <div class="card" v-if="candidateType">
      <h3>
        <template v-if="candidateType === 'ATTRACTION'">选择想去的景点</template>
        <template v-else-if="candidateType === 'FOOD'">选择感兴趣的餐厅</template>
        <template v-else>选择住宿酒店</template>
      </h3>
      <p class="tip">{{ agentLabel }} 已从 {{ destinationName }} 知识库中筛出{{ agentTopic }}备选池（每批展示一部分）；
        勾选心仪的选项（可多选），「换一批」只翻页不重新分析、秒出且不重复，已勾选的会保留置顶；
        直接打字提要求时才会让 AI 重新分析。</p>
      <div class="ai-advice" v-if="aiAdvice">💡 AI 推荐方法：{{ aiAdvice }}</div>

      <!-- 景点 / 酒店：卡片 -->
      <div class="cand-grid" v-if="candidateType !== 'FOOD'">
        <div v-for="c in candidates" :key="c.attractionId || c.hotelId" class="cand"
             :class="{ checked: selectedIds.includes(c.attractionId || c.hotelId) }"
             @click="toggle(c.attractionId || c.hotelId)">
          <div class="name">{{ c.name }}<span class="score" v-if="c.score != null" :title="scoreTitle(c)">{{ c.score.toFixed(1) }}</span></div>
          <div class="sub" v-if="c.feature">{{ c.feature }}</div>
          <div class="sub" v-if="c.pricePerNight != null">¥{{ c.pricePerNight }}/晚 · 评分 {{ c.rating }} · 距景点美食中心 {{ c.distanceToCenter }}km</div>
          <div class="tags" v-if="c.tags"><span v-for="t in splitTags(c.tags)" :key="t" class="tag-chip">{{ t }}</span></div>
          <div class="why">{{ c.why }}</div>
        </div>
      </div>

      <!-- 美食：有餐次标注按餐次分组，否则按风味分组 -->
      <div v-else>
        <div class="tip" v-if="mealPlanText">已按你的餐次需求过滤候选：{{ mealPlanText }}</div>
        <div v-for="g in foodGroups" :key="g.label" class="cand-group">
          <div class="cuisine">{{ g.isMeal ? '餐次 · ' + g.label : g.label }}</div>
          <div v-for="r in g.items" :key="r.restaurantId" class="cand-row"
               :class="{ checked: selectedIds.includes(r.restaurantId) }"
               @click="toggle(r.restaurantId)">
            <div class="info">
              <div class="name">{{ r.name }}<span class="score" v-if="r.score != null" :title="scoreTitle(r)">{{ r.score.toFixed(1) }}</span></div>
              <div class="sub">人均 ¥{{ r.avgPrice }} · 招牌：{{ r.signatureDish || '-' }}</div>
              <div class="tags" v-if="r.tags"><span v-for="t in splitTags(r.tags)" :key="t" class="tag-chip">{{ t }}</span></div>
            </div>
          </div>
        </div>
        <!-- 阶段2：联网检索推荐（已通过校验并入候选池，可直接勾选；未通过校验的已拒绝并留审计） -->
        <div v-if="webFoodCandidates.length" class="cand-group web-group">
          <div class="cuisine">联网检索 · 已通过校验并入候选池（信息来自网络，价格仅供参考）</div>
          <div v-for="w in webFoodCandidates" :key="w.name" class="cand-row web-row">
            <div class="info">
              <div class="name">{{ w.name }}<span class="badge">网络检索</span></div>
              <div class="sub">人均约 ¥{{ w.avgPrice ?? '待确认' }} · {{ w.cuisine || '风味未知' }} · {{ w.address || '位置未提供' }}</div>
              <div class="why">{{ w.why }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="toolbar">
        <button class="btn" :disabled="!selectedIds.length || loading" @click="confirmSelection(false)">
          确认选择（{{ selectedIds.length }}）
        </button>
        <button class="btn ghost" :disabled="loading" @click="confirmSelection(true)">换一批（秒出）</button>
      </div>
    </div>

    <!-- 行程生成中（同步生成守卫命中或恢复时正在生成） -->
    <div class="card" v-if="stage === 'ITINERARY' && !plan && !pendingFatigue && !pendingBudget">
      <h3>行程生成中</h3>
      <p class="tip">ItineraryAgent 正在结合你的偏好编排行程（约 1~3 分钟），请稍候，无需重复提交；完成后会自动展示结果。</p>
    </div>

    <!-- B 方案：疲劳超载草稿待用户确认 -->
    <div class="card" v-if="stage === 'ITINERARY' && !plan && pendingFatigue">
      <h3>行程已生成，强度偏高待确认</h3>
      <p class="tip">
        当前行程对体力「偏弱」偏满<span v-if="fatigueScore != null">（疲劳分约 {{ Number(fatigueScore).toFixed(1) }}，安全值 9）</span>，已为你安排休息点。
        你可以确认生成，或返回调整减少景点后再重新确认。
      </p>
      <div class="toolbar">
        <button class="btn" :disabled="loading" @click="confirmFatigue(true)">确认生成</button>
        <button class="btn ghost" :disabled="loading" @click="confirmFatigue(false)">返回调整</button>
      </div>
    </div>

    <!-- 预算超支知情放行 -->
    <div class="card" v-if="stage === 'ITINERARY' && !plan && pendingBudget">
      <h3>行程已生成，餐饮超出预算待确认</h3>
      <p class="tip">
        当前行程餐饮费用超出预算<span v-if="budgetOver != null"> {{ Number(budgetOver).toFixed(0) }} 元</span>。
        你可以确认发布（超支部分自付），或返回调整换更实惠的餐厅。
      </p>
      <div class="toolbar">
        <button class="btn" :disabled="loading" @click="confirmBudget(true)">确认发布</button>
        <button class="btn ghost" :disabled="loading" @click="confirmBudget(false)">返回调整</button>
      </div>
    </div>

    <!-- 行程结果 -->
    <div class="card" v-if="isDone && plan">
      <h3>行程规划结果</h3>
      <ItineraryTimeline :plan="plan" mode="session" :session-id="sessionId" @open-route="openRoute" />
      <div class="toolbar">
        <button class="btn ghost" :disabled="loading" @click="regenerateItinerary">重新生成</button>
        <a class="btn" href="#/list">查看我的行程</a>
        <a class="btn ghost" href="#/">换个目的地</a>
      </div>
    </div>

    <div class="loading" v-if="loading">{{ agentWorking || 'AI 思考中……' }}</div>
    <div class="error" v-if="error">
      {{ error }}
      <div class="toolbar" v-if="resumeExpired">
        <a class="btn" :href="'#/plan?destinationId=' + destinationId + '&name=' + encodeURIComponent(destinationName)">重新开始</a>
        <a class="btn ghost" href="#/">返回首页</a>
      </div>
    </div>

    <RouteDialog v-if="routeDialog" v-bind="routeDialog" :visible="!!routeDialog" @close="routeDialog = null" />
  </div>
</template>
