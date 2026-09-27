/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// PRL 管理端编辑器组件包。发布到 npm 的是库产物（src/index.ts → dist/index.js + dist/index.d.ts）；
// demo 页面（index.html + src/main.tsx）只用于本地 `npm run dev` 预览，不参与库构建。
export default defineConfig({
  plugins: [react()],
  build: {
    // 与 ptv-frontend 一致：发行产物不带 Source Map
    sourcemap: false,
    target: 'es2020',
    lib: {
      entry: 'src/index.ts',
      formats: ['es'],
      fileName: () => 'index.js',
      // 入口 import 了 styles.css，固定产物名，好让 package.json 的 "./style.css" 指得到
      cssFileName: 'style',
    },
    rollupOptions: {
      // React 由宿主（管理端）提供，作为 external 不打进产物，避免出现两份 React 实例
      external: ['react', 'react-dom', 'react/jsx-runtime'],
    },
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