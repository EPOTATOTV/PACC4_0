// ESLint flat config —— PRL 管理端编辑器
// 策略与 ptv-frontend 一致：未用变量/导入、React Hooks 规则为 error，其余按需收紧。
// 本包要求零告警，所以没有 warning 级别的规则。
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
      '@typescript-eslint/no-explicit-any': 'error',
      // 标识符是否定义交给 TS 判定；测试里的 describe/it/expect 不在 TS 视野内，所以这里整体关掉
      'no-undef': 'off',
    },
  },
]