<script setup>
// 行程时间线：每天的主题/劳累分/节点列表；节点（除当日首个）为超链接，点击查看两点间路径
const props = defineProps({
  plan: { type: Object, required: true },
  mode: { type: String, default: 'session' }, // session | itinerary
  sessionId: { type: String, default: '' },
  itineraryId: { type: [Number, String], default: null }
})
const emit = defineEmits(['open-route'])

const DAY_CN = ['一', '二', '三', '四', '五', '六', '七', '八', '九', '十']

function dayLabel(i) {
  return i >= 1 && i <= 10 ? DAY_CN[i - 1] : i
}

function totalCost() {
  return (props.plan.days || []).reduce((s, d) => s + (Number(d.estimatedCost) || 0), 0)
}

function typeText(t) {
  return { transport: '交通', hotel: '酒店', attraction: '景点', restaurant: '餐饮', rest: '休息' }[t] || t
}

function durationText(m) {
  if (m == null) return ''
  if (m >= 60) return (m / 60).toFixed(1).replace('.0', '') + ' 小时'
  return m + ' 分钟'
}

function open(day, node, prev) {
  emit('open-route', { day, node, prev })
}
</script>

<template>
  <div>
    <div class="day" v-for="d in plan.days" :key="d.dayIndex">
      <div class="day-head">
        <span class="title">第{{ dayLabel(d.dayIndex) }}天</span>
        <span v-if="d.theme" class="meta">主题：{{ d.theme }}</span>
        <span class="meta">劳累度 {{ (d.fatigueScore || 0).toFixed(1) }}</span>
        <span class="meta">预计消费 {{ d.estimatedCost }} 元</span>
      </div>
      <ul class="timeline">
        <li class="tl-node" :class="n.type" v-for="(n, i) in d.nodes" :key="i">
          <span class="dot"></span>
          <span class="time">{{ n.time }}</span>
          <span v-if="n.type" class="type-chip">[{{ typeText(n.type) }}]</span>
          <template v-if="i > 0 && n.placeId != null && d.nodes[i - 1].placeId != null">
            <a class="link" @click="open(d, n, d.nodes[i - 1])">{{ n.name }}</a>
          </template>
          <template v-else>{{ n.name }}</template>
          <span v-if="n.note" class="note">（{{ n.note }}）</span>
          <span v-if="i > 0 && n.departTime" class="commute">
            {{ n.departTime }} 出发 · 通勤约 {{ n.travelMinutes }} 分钟
            <template v-if="n.durationMinutes"> · 停留约 {{ durationText(n.durationMinutes) }}</template>
          </span>
          <span v-if="i === d.nodes.length - 1" class="day-cost"> 本日约 {{ d.estimatedCost }} 元</span>
        </li>
      </ul>
    </div>
    <div class="total-line">全程预计消费 {{ totalCost().toFixed(0) }} 元</div>
  </div>
</template>

<style scoped>
.type-chip { color: #888; font-size: 11px; margin-right: 4px; }
.commute { color: #aaa; font-size: 11px; margin-left: 6px; }
</style>
