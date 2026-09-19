<script setup>
// 使用记录页：按阶段汇总 token 消耗与费用 + 最近操作明细（普通用户看自己的，管理员也可见全部）
import { ref, onMounted } from 'vue'
import { api } from '../api'

const summary = ref([])
const records = ref([])
const loading = ref(false)
const error = ref('')

const STAGE_CN = {
  PREFERENCE: '偏好问询', ATTRACTIONS: '景点推荐', FOODS: '美食推荐',
  HOTELS: '酒店推荐', ITINERARY: '行程规划', ADJUST: '行程调整',
  ROUTE: '路径推荐', FEEDBACK: '评价反馈'
}
const CHANNEL_CN = {
  KB: '知识库', CACHE: '缓存翻页', AGENT: 'AI 分析',
  RULE_FALLBACK: '规则兜底', OP: '操作'
}
const stageCn = (s) => STAGE_CN[s] || s || '-'
const channelCn = (c) => CHANNEL_CN[c] || c || '-'
const fmtNum = (n) => (n == null ? 0 : n).toLocaleString()
const fmtCost = (c) => (c == null || c === '' ? '-' : '¥' + Number(c).toFixed(4))
const fmtMs = (ms) => {
  const v = ms == null ? 0 : ms
  return v >= 1000 ? (v / 1000).toFixed(1) + ' 秒' : v + ' 毫秒'
}
const fmtTime = (t) => (t || '').replace('T', ' ').slice(0, 19)

onMounted(async () => {
  loading.value = true
  try {
    const [s, r] = await Promise.all([
      api.get('/usage/summary'),
      api.get('/usage/records?limit=100')
    ])
    summary.value = s
    records.value = r
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <h2>使用记录与 Token 消耗</h2>
    <p class="tip">渠道说明：知识库=规则/数据库推荐，缓存翻页=换一批，AI 分析=大模型分析成功，规则兜底=AI 失败后回退知识库。</p>
    <div class="loading" v-if="loading">加载中……</div>
    <div class="error" v-if="error">{{ error }}</div>

    <div class="card" v-if="summary.length">
      <h3>按阶段汇总（成本核算）</h3>
      <table class="tb">
        <thead>
          <tr><th>阶段</th><th>调用次数</th><th>输入 token</th><th>输出 token</th><th>总 token</th><th>预估费用</th><th>总耗时</th></tr>
        </thead>
        <tbody>
          <tr v-for="s in summary" :key="s.stage">
            <td>{{ stageCn(s.stage) }}</td>
            <td>{{ fmtNum(s.count) }}</td>
            <td>{{ fmtNum(s.totalInputTokens) }}</td>
            <td>{{ fmtNum(s.totalOutputTokens) }}</td>
            <td>{{ fmtNum(s.totalTokens) }}</td>
            <td>{{ fmtCost(s.totalCost) }}</td>
            <td>{{ fmtMs(s.totalDurationMs) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card">
      <h3>最近操作明细</h3>
      <p class="tip" v-if="!records.length">暂无记录</p>
      <table class="tb" v-if="records.length">
        <thead>
          <tr>
            <th>时间</th><th>用户</th><th>阶段</th><th>动作</th><th>渠道</th>
            <th>模型</th><th>入token</th><th>出token</th><th>总token</th><th>费用</th><th>耗时</th><th>状态</th><th>备注</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in records" :key="r.id">
            <td>{{ fmtTime(r.createdAt) }}</td>
            <td>{{ r.username || '-' }}</td>
            <td>{{ stageCn(r.stage) }}</td>
            <td>{{ r.action }}</td>
            <td><span class="badge" :class="r.channel || 'OP'">{{ channelCn(r.channel || 'OP') }}</span></td>
            <td>{{ r.model || '-' }}</td>
            <td>{{ fmtNum(r.inputTokens) }}</td>
            <td>{{ fmtNum(r.outputTokens) }}</td>
            <td>{{ fmtNum(r.totalTokens) }}</td>
            <td>{{ fmtCost(r.cost) }}</td>
            <td>{{ fmtMs(r.durationMs) }}</td>
            <td>{{ r.status }}</td>
            <td class="rmk" :title="r.remark">{{ r.remark || '' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
