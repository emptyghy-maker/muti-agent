import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 前端请求 /api 时，由 Vite 转发到后端 8080（可用 VITE_API_TARGET 覆盖，避免跨域）
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true,
        // 代理转发时去掉浏览器 Origin：后端 CORS 白名单只放行 localhost:5173，
        // 换端口起 dev server 会被 Spring MVC 按 403 拒绝。代理场景本应同源语义。
        configure: (proxy) => proxy.on('proxyReq', (req) => req.removeHeader('origin'))
      }
    }
  }
})
