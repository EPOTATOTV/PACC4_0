/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// PTV 管理后台前端。开发时通过代理将 /api 转发到后端（本地联调为 9090 的 local profile 实例）。
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  // 发行产物加固（P0）：生产构建不产出 Source Map（否则 dist 里的 .map 会完整还原源码），
  // 并剥离 console / debugger，避免把内部状态与调用路径暴露在浏览器控制台。
  // CI 会二次校验 dist 中不存在 .map 文件。
  build: {
    sourcemap: false,
    target: 'es2020',
  },
  esbuild: mode === 'production' ? { drop: ['console', 'debugger'] } : {},
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:9090',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    css: false,
    // JUnit 报告落盘路径（由 test:ci 的 junit reporter 使用），供 CI 上传为 artifact。
    // 本地 npm test 不启用 junit reporter，这里只是路径声明，不会生成文件。
    outputFile: { junit: './reports/junit.xml' },
  },
}))