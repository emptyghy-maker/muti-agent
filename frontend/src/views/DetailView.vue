<script setup>
// 行程详情：结构化行程（刷新不丢）+ 地点超链接路径弹层 + ADJUST 调整 + 评价反馈
import { ref, computed, onMounted } from 'vue'
import { api } from '../api'
import ItineraryTimeline from '../components/ItineraryTimeline.vue'
import RouteDialog from '../components/RouteDialog.vue'

const itineraryId = computed(() => Number(location.hash.split('/')[2] || 0))

const detail = ref(null)
const loading = ref(false)
const error = ref('')

const adjustText = ref('')
const adjustLoading = ref(false)
const adjustResult = ref(null)
const adjustError = ref('')

const fb = ref({
  rating: 5,
  paceRating: 3,
  attractionSatisfy: 4,
  foodSatisfy: 4,
  hotelSatisfy: 4,
  budgetFit: '刚好',
  tags: [],
  comment: ''
})
const fbSubmitted = ref(false)
const fbError = ref('')
const TAG_OPTIONS = ['景点太多', '行程太赶', '行程太松', '预算偏高', '美食推荐好', '酒店推荐好']

const routeDialog = ref(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    detail.value = await api.get(`/travel/itinerary/${itineraryId.value}`)
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

onMounted(load)

function star(level) {
  fb.value.rating = level
}

function toggleTag(t) {
  const i = fb.value.tags.indexOf(t)
  if (i >= 0) fb.value.tags.splice(i, 1)
  else fb.value.tags.push(t)
}

async function submitFeedback() {
  fbError.value = ''
  try {
    await api.post(`/travel/itinerary/${itineraryId.value}/feedback`, fb.value)
    fbSubmitted.value = true
  } catch (e) {
    fbError.value = e.message
  }
}

async function submitAdjust() {
  if (!adjustText.value.trim()) return
  adjustLoading.value = true
  adjustError.value = ''
  adjustResult.value = null
  try {
    adjustResult.value = await api.post(`/travel/itinerary/${itineraryId.value}/adjust`, {
      message: adjustText.value.trim()
    })
    adjustText.value = ''
  } catch (e) {
    adjustError.value = e.message
  } finally {
    adjustLoading.value = false
  }
}

function openRoute(payload) {
  routeDialog.value = {
    mode: 'itinerary',
    itineraryId: itineraryId.value,
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
    <div class="loading" v-if="loading">加载中……</div>
    <div class="error" v-if="error">{{ error }}</div>

    <template v-if="detail">
      <h2>{{ detail.title }}</h2>
      <p class="tip">
        {{ detail.destinationName }} · {{ detail.days }} 天 · v{{ detail.version }} ·
        预算 {{ detail.totalBudget }} 元 · 预计消费 ¥{{ detail.totalCost }} ·
        {{ (detail.createdAt || '').replace('T', ' ').slice(0, 16) }}
      </p>

      <div class="card">
        <h3>行程安排（点击地点查看路径）</h3>
        <ItineraryTimeline :plan="detail.plan" mode="itinerary" :itinerary-id="detail.id"
                           @open-route="openRoute" />
      </div>

      <div class="card">
        <h3>调整行程</h3>
        <p class="tip">输入你的诉求（如：第二天太累了，减少一个景点；换个更便宜的酒店），会基于当前行程生成新版本。</p>
        <div class="input-row">
          <input v-model="adjustText" type="text" placeholder="例：第二天太赶，帮我删掉一个景点"
                 @keyup.enter="submitAdjust" />
          <button class="btn" :disabled="!adjustText.trim() || adjustLoading" @click="submitAdjust">生成新版本</button>
        </div>
        <div class="loading" v-if="adjustLoading">调整中，AI 正在重新规划……</div>
        <div class="error" v-if="adjustError">{{ adjustError }}</div>
        <div v-if="adjustResult" class="card">
          <p>新版本已生成：<a :href="'#/detail/' + adjustResult.id">{{ adjustResult.title }}（v{{ adjustResult.version }}）</a></p>
        </div>
      </div>

      <div class="card">
        <h3>行程评价</h3>
        <p class="tip" v-if="fbSubmitted">感谢反馈！我们会根据评价持续优化推荐。</p>
        <template v-else>
          <div class="fb-row">
            <label>综合评分</label>
            <div class="stars">
              <span v-for="n in 5" :key="n" class="star" :class="{ on: n <= fb.rating }" @click="star(n)">★</span>
            </div>
          </div>
          <div class="fb-row">
            <label>行程节奏</label>
            <select v-model="fb.paceRating">
              <option :value="1">太赶</option>
              <option :value="3">刚好</option>
              <option :value="5">太松</option>
            </select>
          </div>
          <div class="fb-row">
            <label>景点满意度</label>
            <select v-model="fb.attractionSatisfy">
              <option v-for="n in 5" :key="n" :value="n">{{ n }} 分</option>
            </select>
          </div>
          <div class="fb-row">
            <label>美食满意度</label>
            <select v-model="fb.foodSatisfy">
              <option v-for="n in 5" :key="n" :value="n">{{ n }} 分</option>
            </select>
          </div>
          <div class="fb-row">
            <label>酒店满意度</label>
            <select v-model="fb.hotelSatisfy">
              <option v-for="n in 5" :key="n" :value="n">{{ n }} 分</option>
            </select>
          </div>
          <div class="fb-row">
            <label>预算执行</label>
            <select v-model="fb.budgetFit">
              <option value="超支">超支</option>
              <option value="刚好">刚好</option>
              <option value="富余">富余</option>
            </select>
          </div>
          <div class="fb-row">
            <label>快捷标签</label>
            <div class="chips">
              <span v-for="t in TAG_OPTIONS" :key="t" class="chip" :class="{ on: fb.tags.includes(t) }"
                    @click="toggleTag(t)">{{ t }}</span>
            </div>
          </div>
          <div class="fb-row">
            <label>补充评价</label>
            <textarea v-model="fb.comment" rows="3" placeholder="还有什么想告诉我们的？"></textarea>
          </div>
          <button class="btn" @click="submitFeedback">提交评价</button>
          <div class="error" v-if="fbError">{{ fbError }}</div>
        </template>
      </div>
    </template>

    <RouteDialog v-if="routeDialog" v-bind="routeDialog" :visible="!!routeDialog" @close="routeDialog = null" />
  </div>
</template>
