import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

/**
 * Vite 配置。
 *
 * 开发时前端跑在 5173、后端跑在 8080，属于不同源。
 * 这里把 `/api` 代理到后端，前端代码里就只写相对路径 `/api/...`，
 * 既不用处理跨域，也不用在代码里硬编码后端地址。
 */
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
  },
})
