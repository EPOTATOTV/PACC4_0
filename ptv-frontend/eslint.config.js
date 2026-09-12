// ESLint 9/10 flat config —— PTV 前端
// 策略：先把高价值规则（React Hooks、未用变量/导入）设为 error，其余告警只读；
// 后续可将全量规则逐步收紧并接入 CI 门禁。
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
      // v7 新增的 set-state-in-effect 对“异步加载数据”这类既有模式过度激进，降为只读
      'react-hooks/set-state-in-effect': 'warn',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
      '@typescript-eslint/no-explicit-any': 'warn',
      // 测试文件使用 vitest globals（describe/it/expect）
      'no-undef': 'off',
    },
  },
]