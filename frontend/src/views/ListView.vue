<script setup>
import { ref, onMounted } from 'vue'
import { api } from '../api'

const list = ref([])
const loading = ref(false)
const error = ref('')

onMounted(async () => {
  loading.value = true
  try {
    list.value = await api.get('/travel/itineraries')
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div>
    <h2>我的行程</h2>
    <div class="loading" v-if="loading">加载中……</div>
    <div class="error" v-if="error">{{ error }}</div>
    <div v-if="!loading && !error && !list.length" class="card">
      <p class="tip">还没有生成过行程，<a href="#/">去规划一个吧</a></p>
    </div>
    <div class="dest-grid">
      <div class="dest-card" v-for="it in list" :key="it.id">
        <h3>{{ it.title || it.destinationName }}</h3>
        <p class="intro">{{ it.destinationName }} · {{ it.days }} 天 · v{{ it.version }}</p>
        <p class="budget">预计总消费 ¥{{ it.totalCost }}</p>
        <p class="tip">{{ (it.createdAt || '').replace('T', ' ').slice(0, 16) }}</p>
        <a class="btn small" :href="'#/detail/' + it.id">查看详情</a>
      </div>
    </div>
  </div>
</template>
