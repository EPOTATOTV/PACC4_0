// ESLint 9/10 flat config —— PTV 前端
// 策略：React Hooks 与未用变量/导入设为 error，no-explicit-any 设为 warn；
// `npm run lint` 带 --max-warnings 0 且已接入 CI，三者任一生效都会让流水线变红。
import js from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'
import reactHooks from 'eslint-plugin-react-hooks'

const hooks = reactHooks.configs?.flat?.recommended
  ? reactHooks.configs.flat.recommended
  : { rules: reactHooks.configs.recommended.rules }

export default [
  { ignores: ['dist', 'node_modules', 'coverage', 'vite.config.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: {
        ...globals.browser,
        ...globals.es2022,
      },
      parserOptions: {
        ecmaFeatures: { jsx: true },
      },
    },
    plugins: hooks.plugins,
    rules: {
      ...(hooks.rules ?? {}),
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      '@typescript-eslint/no-explicit-any': 'warn',
      // 测试文件使用 vitest globals（describe/it/expect）
      'no-undef': 'off',
    },
  },
  {
    // v7 的 set-state-in-effect 会把「effect 调用 useCallback 包着的异步加载器」判为级联渲染，
    // 即便 setState 严格发生在 await 之后（已用最小复现确认）。58 个页面清一色是这一种加载模式，
    // 要让它闭嘴只能把 setState 挪进 .then / 内联 IIFE 让静态分析看不见——那是迎合检查，不是改对代码。
    // 只对页面放宽；hooks 与其余组件仍按 error 走，避免全局关掉后漏掉「effect 里同步派生状态」这类真问题。
    files: ['src/pages/**/*.{ts,tsx}'],
    rules: {
      'react-hooks/set-state-in-effect': 'off',
    },
  },
]