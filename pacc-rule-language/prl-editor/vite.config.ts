/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// PRL 管理端编辑器组件包。demo 页面（index.html + src/main.tsx）只是给人肉看效果用的；
// 真实接入方式是管理端直接引用 src/index.ts 的组件。
export default defineConfig({
  plugins: [react()],
  build: {
    // 与 ptv-frontend 一致：发行产物不带 Source Map
    sourcemap: false,
    target: 'es2020',
  },
  server: {
    port: 5183,
    proxy: {
      '/api': {
        target: 'http://localhost:9090',
        changeOrigin: true,
      },
    },
  },
  test: {
    // tokenizer / linter 都是纯函数，不需要 DOM
    environment: 'node',
    globals: true,
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
  },
})