<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '../api'

const destinations = ref([])
const loading = ref(false)
const error = ref('')
const resumable = ref(null)

const STAGE_LABEL = {
  PREFERENCE: '偏好问询',
  ATTRACTIONS: '选择景点',
  FOODS: '选择美食',
  HOTELS: '选择酒店',
  PLAN_QUIZ: '行程偏好',
  ITINERARY: '生成行程中',
  CONFLICT: '待确认冲突',
  ADJUST: '调整行程中'
}

const stageLabel = computed(() =>
  (resumable.value && (STAGE_LABEL[resumable.value.stage] || resumable.value.stage)) || '')

const minutesAgo = computed(() => {
  if (!resumable.value || !resumable.value.updatedAt) return ''
  const min = Math.max(0, Math.round((Date.now() - resumable.value.updatedAt) / 60000))
  return min < 1 ? '刚刚' : '约 ' + min + ' 分钟前'
})

onMounted(async () => {
  loading.value = true
  try {
    destinations.value = await api.get('/travel/destinations')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
  // 断点恢复入口：失败静默（服务端异常/未启用不阻断目的地加载）
  try {
    resumable.value = await api.get('/travel/session/resumable')
  } catch (e) {
    resumable.value = null
  }
})

function start(d) {
  // 标记「本次是新建会话」：PlanView 据此开新会话，而不是自动恢复上一段未结束会话
  sessionStorage.setItem('plan.new', '1')
  location.hash = '#/plan?destinationId=' + d.id + '&name=' + encodeURIComponent(d.name)
}

function resume() {
  const r = resumable.value
  location.hash = '#/plan?sessionId=' + encodeURIComponent(r.sessionId)
    + '&destinationId=' + r.destinationId
    + '&name=' + encodeURIComponent(r.destinationName || '')
}
</script>

<template>
  <div>
    <h2>选择目的地</h2>
    <p class="tip">你好，我是你的旅游攻略智能规划助手，请先选择目的地，我会帮你规划天数、预算、景点、美食与酒店。</p>
    <div class="loading" v-if="loading">加载目的地中……</div>
    <div class="error" v-if="error">{{ error }}</div>

    <!-- 断点恢复：未结束会话回到原窗口；已结束/超 30 分钟不展示，直接开新会话 -->
    <div class="dest-card resume-card" v-if="resumable">
      <h3>继续上次规划</h3>
      <p class="intro">
        目的地：{{ resumable.destinationName || '未知' }} ·
        当前阶段：{{ stageLabel }}<span v-if="resumable.activeOperationId || resumable.generating">（AI 正在生成中）</span>
      </p>
      <p class="resume-meta">{{ minutesAgo }} · 未结束的规划会话将在离开 30 分钟后过期</p>
      <button class="btn" @click="resume">回到上次会话</button>
    </div>

    <div class="dest-grid" v-if="destinations.length">
      <div class="dest-card" v-for="d in destinations" :key="d.id">
        <h3>{{ d.name }} <span class="province">{{ d.province }}</span></h3>
        <p class="intro">{{ d.intro }}</p>
        <p class="tags" v-if="d.tags">{{ d.tags }}</p>
        <p class="budget" v-if="d.dailyBudgetMin != null">参考预算 {{ d.dailyBudgetMin }} ~ {{ d.dailyBudgetMax }} 元/人/天</p>
        <button class="btn small" @click="start(d)">开始规划</button>
      </div>
    </div>
  </div>
</template>
