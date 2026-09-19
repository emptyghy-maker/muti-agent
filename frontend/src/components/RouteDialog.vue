<script setup>
// 路径弹层：点击行程地点超链接后，查询并展示上一地点到该地点的具体路径方案
import { ref, watch } from 'vue'
import { api } from '../api'

const props = defineProps({
  visible: Boolean,
  mode: { type: String, default: 'session' }, // session | itinerary
  sessionId: { type: String, default: '' },
  itineraryId: { type: [Number, String], default: null },
  day: { type: Number, default: 0 },
  fromSeq: { type: Number, default: 0 },
  toSeq: { type: Number, default: 0 },
  fromName: { type: String, default: '' },
  toName: { type: String, default: '' }
})
const emit = defineEmits(['close'])

const loading = ref(false)
const result = ref(null)
const error = ref('')

watch(() => props.visible, async (v) => {
  if (!v) return
  load()
}, { immediate: true })

async function load() {
  loading.value = true
  result.value = null
  error.value = ''
  try {
    const q = `day=${props.day}&fromSeq=${props.fromSeq}&toSeq=${props.toSeq}`
    if (props.mode === 'itinerary') {
      result.value = await api.get(`/travel/route/itinerary?itineraryId=${props.itineraryId}&${q}`)
    } else {
      result.value = await api.get(`/travel/route?sessionId=${encodeURIComponent(props.sessionId)}&${q}`)
    }
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

const MODE_CN = { walk: '步行', transit: '公交/地铁', taxi: '出租车' }
</script>

<template>
  <div class="mask" v-if="visible" @click.self="emit('close')">
    <div class="dialog">
      <button class="close" @click="emit('close')">✕</button>
      <h3>路径规划：{{ fromName }} → {{ toName }}</h3>
      <div class="loading" v-if="loading">路径规划中……</div>
      <div class="error" v-else-if="error">{{ error }}</div>
      <template v-else-if="result">
        <p class="tip">直线距离约 {{ result.distanceKm }} 公里</p>
        <div class="route-opt" v-for="(o, i) in result.options" :key="i">
          <div class="head">
            <span>{{ MODE_CN[o.mode] || o.mode }}</span>
            <span>约 {{ o.durationMin }} 分钟</span>
            <span class="cost">¥{{ o.cost }}</span>
          </div>
          <ol>
            <li v-for="(s, j) in o.steps" :key="j">{{ s }}</li>
          </ol>
        </div>
      </template>
    </div>
  </div>
</template>
