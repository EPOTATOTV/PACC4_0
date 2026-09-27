/// <reference types="vitest/config" />
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// PTV 管理后台前端。开发时通过代理将 /api 转发到后端（本地联调为 9090 的 local profile 实例）。
export default defineConfig(() => ({
  plugins: [react()],
  resolve: {
    // @potatotv/prl-editor 是 file: 依赖（软链到 pacc-rule-language/prl-editor），
    // 它自己的 node_modules 里也装了一份 react。不 dedupe 的话，编辑器产物里的
    // `import 'react'` 会从真实路径往上解析到那一份，页面上出现两套 React。
    dedupe: ['react', 'react-dom'],
  },
  // 发行产物加固（P0）：生产构建不产出 Source Map（否则 dist 里的 .map 会完整还原源码），
  // 并剥离 console / debugger，避免把内部状态与调用路径暴露在浏览器控制台。
  // CI 会二次校验 dist 中不存在 .map 文件。
  build: {
    sourcemap: false,
    target: 'es2020',
    // 剥离 console 要写在 oxc 的压缩选项里，不能写在顶层 esbuild.drop。
    // Vite 8 的转换与压缩都走 oxc，@vitejs/plugin-react 也会带上一组 oxc 选项；
    // 两者同时存在时 Vite 取 oxc、把 esbuild 那组整块丢掉（构建日志有一行提示，
    // 但构建照常成功），结果是生产包里仍留着几十处 console.*。
    // debugger 本来就被 oxc 的 compress 默认剥掉，只需要显式管 console。
    rolldownOptions: {
      output: {
        minify: { compress: { dropConsole: true } },
        // 分块三层：页面按路由懒加载（见 src/App.tsx），运行时库单独成块便于长期缓存，
        // 重型库再按自己的目录切开，任何一块都不贴近 Vite 默认 500 kB 的告警线。
        // 分组按声明顺序生效，先命中的先拿走模块，且默认连依赖一起递归捕获，
        // 所以被依赖的包要写在依赖它的包前面（zrender 必须先于 echarts）。
        codeSplitting: {
          groups: [
            { name: 'zrender', test: /node_modules[\\/]zrender[\\/]/ },
            // echarts 只被 EChart 用到，合在一起 391 kB 会一路逼近告警线，
            // 按包内目录拆成图表实现、组件实现、其余核心三块；图表页多几个并行请求，
            // 换来的是后续任意一块增长都不会再触发告警。
            { name: 'echarts-charts', test: /node_modules[\\/]echarts[\\/]lib[\\/]chart[\\/]/ },
            { name: 'echarts-components', test: /node_modules[\\/]echarts[\\/]lib[\\/]component[\\/]/ },
            // 兜底接住 echarts 剩下的 coord/model/util 等；不足阈值就交还给默认分块，免得碎成一地。
            { name: 'echarts-core', test: /node_modules[\\/]echarts[\\/]/, minSize: 24 * 1024 },
            // react/react-dom 几乎不随业务迭代变化，单独成块既便于缓存复用，
            // 也避免被自动分块并进名字和内容对不上的大块里。
            { name: 'react', test: /node_modules[\\/](react|react-dom|scheduler)[\\/]/ },
          ],
        },
      },
    },
  },
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