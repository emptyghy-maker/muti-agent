<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import HomeView from './views/HomeView.vue'
import PlanView from './views/PlanView.vue'
import ListView from './views/ListView.vue'
import DetailView from './views/DetailView.vue'
import UsageView from './views/UsageView.vue'
import AdminUsageView from './views/AdminUsageView.vue'
import ObservabilityRunList from './views/observability/ObservabilityRunList.vue'
import ObservabilityRunDetail from './views/observability/RunDetail.vue'

// ===== 登录（沿用既有账号体系） =====
const token = ref(localStorage.getItem('token') || '')
const username = ref('admin')
const password = ref('admin123')
const me = ref({
  username: localStorage.getItem('username') || '',
  role: localStorage.getItem('role') || ''
})
const error = ref('')
const loggedIn = computed(() => !!token.value)
const isAdmin = computed(() => me.value.role === 'ADMIN')

async function login() {
  error.value = ''
  try {
    const resp = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username.value, password: password.value })
    })
    const json = await resp.json()
    if (json.code === 0 && json.data && json.data.token) {
      token.value = json.data.token
      localStorage.setItem('token', token.value)
      me.value.username = json.data.username || username.value
      me.value.role = json.data.role || ''
      localStorage.setItem('username', me.value.username)
      localStorage.setItem('role', me.value.role)
    } else {
      error.value = json.message || '登录失败'
    }
  } catch (e) {
    error.value = '登录请求出错：' + e.message
  }
}

function logout() {
  token.value = ''
  me.value = { username: '', role: '' }
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  localStorage.removeItem('role')
  location.hash = '#/'
}

// ===== 简易 hash 路由（#/、#/plan?...、#/list、#/detail/{id}） =====
const hash = ref(location.hash || '#/')

function onHashChange() {
  hash.value = location.hash
}

// api.js 收到 401 时触发：本地 token 已失效，回登录页
function onAuthExpired() {
  token.value = ''
  me.value = { username: '', role: '' }
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  localStorage.removeItem('role')
  hash.value = '#/'
  location.hash = '#/'
}

onMounted(() => {
  window.addEventListener('hashchange', onHashChange)
  window.addEventListener('auth-expired', onAuthExpired)
})
onUnmounted(() => {
  window.removeEventListener('hashchange', onHashChange)
  window.removeEventListener('auth-expired', onAuthExpired)
})

const routePath = computed(() => (hash.value || '#/').split('?')[0].slice(1))

const view = computed(() => {
  if (routePath.value.startsWith('/plan')) return PlanView
  if (routePath.value.startsWith('/detail')) return DetailView
  if (routePath.value.startsWith('/list')) return ListView
  if (routePath.value.startsWith('/observability/runs/')) return ObservabilityRunDetail
  if (routePath.value.startsWith('/observability/runs')) return ObservabilityRunList
  if (routePath.value.startsWith('/admin')) return isAdmin.value ? AdminUsageView : UsageView
  if (routePath.value.startsWith('/usage')) return UsageView
  return HomeView
})
</script>

<template>
  <div class="page">
    <div class="topbar">
      <span class="brand" @click="location.hash = '#/'">✈ 多 Agent 旅游攻略规划</span>
      <span class="spacer"></span>
      <template v-if="loggedIn">
        <a href="#/">规划行程</a>
        <a href="#/list">我的行程</a>
        <a href="#/usage">使用记录</a>
        <a v-if="isAdmin" href="#/admin">管理后台</a>
        <a v-if="isAdmin" href="#/observability/runs">运行记录</a>
        <a @click="logout">退出（{{ me.username || username }}）</a>
      </template>
    </div>

    <div v-if="!loggedIn" class="card">
      <h3>登录</h3>
      <div class="login-row">
        <input v-model="username" type="text" placeholder="用户名" @keyup.enter="login" />
        <input v-model="password" type="password" placeholder="密码" @keyup.enter="login" />
        <button class="btn" @click="login">登录</button>
      </div>
      <p class="tip">演示账号：admin / admin123，analyst / analyst123</p>
    </div>

    <template v-else>
      <component :is="view" :key="hash" />
    </template>

    <div v-if="error" class="error">{{ error }}</div>
  </div>
</template>
