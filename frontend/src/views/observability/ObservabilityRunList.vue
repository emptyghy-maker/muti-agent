<script setup>
import { ref, onMounted } from 'vue'
import { obs, STATUS_CN, STATUS_CLASS, fmtTime, fmtMs } from '../../api/observability.js'

const loading = ref(false)
const error = ref('')
const page = ref({ items: [], nextCursor: null })
const cursor = ref(null)
const limit = ref(50)
const ownerId = ref('')

async function load(reset = true) {
  loading.value = true
  error.value = ''
  try {
    const data = await obs.runs({
      cursor: reset ? null : cursor.value,
      limit: limit.value,
      ownerId: ownerId.value === '' ? null : Number(ownerId.value)
    })
    page.value = data || { items: [], nextCursor: null }
    if (!reset) {
      page.value = { ...data, items: [...(page.value.items || []), ...(data.items || [])] }
    }
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function more() {
  if (page.value.nextCursor) {
    cursor.value = page.value.nextCursor
    load(false)
  }
}

onMounted(() => load(true))
</script>

<template>
  <div>
    <h3>运行记录（Observability）</h3>
    <p class="tip">后端指标口径统一计算；未知费用显示「未知」而不是 0；owner 为空的隔离域记录不可见。</p>
    <div class="filters">
      <input v-model="ownerId" type="text" placeholder="ownerId（ADMIN 跨用户，留空=全部）" />
      <select v-model="limit">
        <option :value="50">50 条</option>
        <option :value="100">100 条</option>
        <option :value="200">200 条</option>
      </select>
      <button class="btn small" @click="load(true)">查询</button>
    </div>

    <div v-if="loading" class="loading">加载中…</div>
    <div v-else-if="error" class="error">{{ error }}</div>
    <p v-else-if="!page.items.length" class="tip">暂无运行记录</p>

    <template v-else>
      <table class="tb">
        <thead>
          <tr>
            <th>runId</th><th>生命周期</th><th>业务结果</th><th>完整性</th><th>来源</th>
            <th>开始时间</th><th>耗时</th><th>模型版本</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in page.items" :key="r.runId" class="clickable"
              @click="location.hash = '#/observability/runs/' + r.runId">
            <td class="mono">{{ r.runId }}</td>
            <td><span :class="STATUS_CLASS[r.runStatus] || 'badge'">{{ STATUS_CN[r.runStatus] || r.runStatus }}</span></td>
            <td><span :class="STATUS_CLASS[r.businessStatus] || 'badge'">{{ STATUS_CN[r.businessStatus] || r.businessStatus || '—' }}</span></td>
            <td><span :class="STATUS_CLASS[r.dataCompleteness] || 'badge'">{{ STATUS_CN[r.dataCompleteness] || r.dataCompleteness }}</span></td>
            <td>{{ r.sourceSystem }}</td>
            <td>{{ fmtTime(r.startedAt) }}</td>
            <td>{{ fmtMs(r.durationMs) }}</td>
            <td>{{ r.modelVersion || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div class="pager">
        <button v-if="page.nextCursor" class="btn small" @click="more">加载更多</button>
        <span v-else class="tip">已到底</span>
      </div>
    </template>
  </div>
</template>
