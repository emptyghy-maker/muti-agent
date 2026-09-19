<script setup>
import { ref, computed } from 'vue'
import { obs, STATUS_CN, STATUS_CLASS, fmtTime, fmtMs, fmtCost, fmtToken } from '../../api/observability.js'

const loading = ref(false)
const error = ref('')
const detail = ref(null)
const events = ref({ items: [], nextCursor: null })
const eventCursor = ref(0)
const annotation = ref('')
const annotationState = ref('')

const runId = computed(() => {
  const raw = (location.hash || '').split('?')[0]
  return raw.replace('#/observability/runs/', '').replace('#/', '')
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [d, e] = await Promise.all([
      obs.run(runId.value),
      obs.events(runId.value, 0, 100)
    ])
    detail.value = d
    events.value = e || { items: [], nextCursor: null }
  } catch (err) {
    error.value = err.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function moreEvents() {
  if (!events.value.nextCursor) return
  eventCursor.value = events.value.nextCursor
  try {
    const e = await obs.events(runId.value, eventCursor.value, 100)
    events.value = { items: [...events.value.items, ...e.items], nextCursor: e.nextCursor }
  } catch (err) {
    error.value = err.message
  }
}

async function annotate() {
  annotationState.value = ''
  try {
    await obs.annotate(runId.value, annotation.value)
    annotation.value = ''
    annotationState.value = '已提交备注（追加保留，不覆盖证据）'
  } catch (err) {
    annotationState.value = err.message
  }
}

/** 按 parentSpanId 组树；孤儿挂根（标 orphan，不造父节点） */
const spanTree = computed(() => {
  const spans = detail.value?.spans || []
  const byParent = {}
  spans.forEach(s => {
    const p = s.parentSpanId || (s.parentSpanId ? s.parentSpanId : '__root__')
    ;(byParent[p] = byParent[p] || []).push(s)
  })
  return byParent
})

const rootSpans = computed(() => spanTree.value['__root__'] || [])

function childrenOf(spanId) {
  return spanTree.value[spanId] || []
}

/** CSS 时间轴：span 按开始时间排序，宽度按耗时比例 */
const timeline = computed(() => {
  const spans = (detail.value?.spans || []).filter(s => s.startedAt != null)
  if (!spans.length) return []
  const min = Math.min(...spans.map(s => s.startedAt))
  const max = Math.max(...spans.map(s => (s.endedAt || s.startedAt) + (s.durationMs || 0)))
  const total = Math.max(1, max - min)
  return spans.map(s => ({
    ...s,
    left: ((s.startedAt - min) / total) * 100,
    width: Math.max(1.5, ((s.durationMs || 0) / total) * 100)
  }))
})

function fmtMs0(v) { return fmtMs(v) }

load()
</script>

<template>
  <div>
    <p><a href="#/observability/runs">← 返回运行列表</a></p>
    <div v-if="loading" class="loading">加载中…</div>
    <div v-else-if="error" class="error">{{ error }}</div>

    <template v-else-if="detail">
      <h3>运行详情 <span class="mono">{{ runId }}</span></h3>
      <p v-if="detail.pathDefinitionMissing" class="tip">
        ⚠ 路径定义缺失：以下为真实事件树，节点未按 workflow 定义标注。
      </p>

      <div class="stat-grid">
        <div class="stat"><b>生命周期</b>
          <span :class="STATUS_CLASS[detail.run.runStatus] || 'badge'">{{ STATUS_CN[detail.run.runStatus] || detail.run.runStatus }}</span>
        </div>
        <div class="stat"><b>业务结果</b>
          <span :class="STATUS_CLASS[detail.run.businessStatus] || 'badge'">{{ STATUS_CN[detail.run.businessStatus] || detail.run.businessStatus || '—' }}</span>
        </div>
        <div class="stat"><b>数据完整性</b>
          <span :class="STATUS_CLASS[detail.run.dataCompleteness] || 'badge'">{{ STATUS_CN[detail.run.dataCompleteness] || detail.run.dataCompleteness }}</span>
        </div>
        <div class="stat"><b>来源</b><span>{{ detail.run.sourceSystem }}</span></div>
        <div class="stat"><b>墙钟耗时</b><span>{{ fmtMs0(detail.metrics.wallMs) }}</span></div>
        <div class="stat"><b>并行工作量</b><span>{{ fmtMs0(detail.metrics.providerWorkMs) }}</span></div>
        <div class="stat"><b>模型版本</b><span>{{ detail.run.modelVersion || '—' }}</span></div>
        <div class="stat"><b>快照哈希</b><span class="mono">{{ detail.run.snapshotHash || '—' }}</span></div>
      </div>

      <div class="stat-grid">
        <div class="stat"><b>逻辑操作</b><span>{{ detail.metrics.operationCount }}</span></div>
        <div class="stat"><b>成功</b><span>{{ detail.metrics.successCount }}</span></div>
        <div class="stat"><b>在途</b><span>{{ detail.metrics.inFlightCount }}</span></div>
        <div class="stat"><b>完成率</b>
          <span>{{ detail.metrics.successRate == null ? '—' : (detail.metrics.successRate * 100).toFixed(0) + '%' }}</span>
        </div>
        <div class="stat"><b>已知总费用</b><span>{{ fmtCost(detail.metrics.totalKnownCost) }}</span></div>
        <div class="stat"><b>单位成功成本</b><span>{{ fmtCost(detail.metrics.costPerSuccessfulPlan) }}</span></div>
        <div class="stat"><b>未知费用</b><span>{{ detail.metrics.unknownCostCount }}（{{ detail.metrics.costComplete ? '费用完整' : '不完整' }}）</span></div>
      </div>

      <h4>Span 树</h4>
      <div v-if="!rootSpans.length" class="tip">暂无 span（事件仍在缓冲或尚未批写）</div>
      <template v-for="s in rootSpans" :key="s.spanId">
        <div class="span-node">
          <span :class="STATUS_CLASS[s.status] || 'badge'">{{ STATUS_CN[s.status] || s.status }}</span>
          <b>{{ s.agent || s.kind || s.spanId }}</b>
          <span class="mono small">{{ s.spanId }}</span>
          <span class="tip">{{ fmtMs0(s.durationMs) }}</span>
        </div>
        <div v-for="c in childrenOf(s.spanId)" :key="c.spanId" class="span-node child">
          <span :class="STATUS_CLASS[c.status] || 'badge'">{{ STATUS_CN[c.status] || c.status }}</span>
          <b>{{ c.agent || c.kind || c.spanId }}</b>
          <span class="mono small">{{ c.spanId }}</span>
          <span class="tip">{{ fmtMs0(c.durationMs) }}</span>
          <div v-if="c.summary" class="qa-cell">{{ c.summary }}</div>
        </div>
      </template>

      <h4>时间轴（并行工作量 ≠ 墙钟）</h4>
      <div class="obs-timeline">
        <div v-for="t in timeline" :key="'tl-' + t.spanId" class="obs-timeline-row">
          <span class="obs-timeline-label">{{ t.agent || t.kind || t.spanId }}</span>
          <div class="obs-timeline-track">
            <div class="obs-timeline-bar"
                 :class="t.status === 'FAILED' || t.status === 'CANCELLED' ? 'obs-bar-bad' : 'obs-bar-ok'"
                 :style="{ left: t.left + '%', width: t.width + '%' }"></div>
          </div>
        </div>
      </div>

      <h4>事件（增量游标）</h4>
      <table class="tb">
        <thead><tr><th>类型</th><th>时间</th><th>span</th><th>摘要</th></tr></thead>
        <tbody>
          <tr v-for="e in events.items" :key="e.eventId">
            <td>{{ e.eventType }}</td>
            <td>{{ fmtTime(e.occurredAt) }}</td>
            <td class="mono">{{ e.spanId || '—' }}</td>
            <td class="qa-cell">{{ JSON.stringify(e.summary || {}) }}</td>
          </tr>
        </tbody>
      </table>
      <div class="pager">
        <button v-if="events.nextCursor" class="btn small" @click="moreEvents">加载更多事件</button>
        <span v-else class="tip">事件已全部加载</span>
      </div>

      <h4>旧用量记录（LEGACY，按当前价估算）</h4>
      <p v-if="!detail.legacyAttempts.length" class="tip">无旧记录</p>
      <table v-else class="tb">
        <thead><tr><th>agent</th><th>模型</th><th>input</th><th>output</th><th>状态</th><th>费用</th></tr></thead>
        <tbody>
          <tr v-for="(a, i) in detail.legacyAttempts" :key="'legacy-' + i">
            <td>{{ a.agent || '—' }}</td>
            <td>{{ a.model || '—' }}</td>
            <td>{{ fmtToken(a.inputTokens, a.usageStatus) }}</td>
            <td>{{ fmtToken(a.outputTokens, a.usageStatus) }}</td>
            <td>{{ a.status || '—' }}</td>
            <td>{{ fmtCost(a.costAmount) }}（LEGACY_ESTIMATE）</td>
          </tr>
        </tbody>
      </table>

      <h4>人工备注（追加保留，已有 {{ detail.annotationCount }} 条）</h4>
      <textarea v-model="annotation" rows="2" placeholder="备注内容"></textarea>
      <div>
        <button class="btn small" @click="annotate">提交备注</button>
        <span class="tip">{{ annotationState }}</span>
      </div>
    </template>
  </div>
</template>
