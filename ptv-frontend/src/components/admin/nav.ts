/**
 * 管理端导航与页面元数据：分组菜单 + 面包屑/标题 的单一事实源。
 * 按 v4.1 设计文档的「顶部导航栏 + 左侧分组菜单 + 面包屑」布局组织。
 */

export interface NavItem {
  key: string
  to: string
  label: string
  exact?: boolean
}

export interface NavGroup {
  groupKey: string
  groupLabel: string
  items: NavItem[]
}

export const navGroups: NavGroup[] = [
  {
    groupKey: 'overview',
    groupLabel: '数据概览',
    items: [{ key: '/', to: '/', label: '数据大盘', exact: true }],
  },
  {
    groupKey: 'detect',
    groupLabel: '检测管理',
    items: [
      { key: '/redscreen', to: '/redscreen', label: '红屏管理' },
      { key: '/inspect', to: '/inspect', label: '查端控制台' },
      { key: '/detection41', to: '/detection41', label: 'v4.1 检测引擎' },
      { key: '/records', to: '/records', label: '作弊记录' },
    ],
  },
  {
    groupKey: 'account',
    groupLabel: '账号管理',
    items: [
      { key: '/accounts', to: '/accounts', label: 'PTEID 账号' },
      { key: '/admins', to: '/admins', label: '管理员管理' },
    ],
  },
  {
    groupKey: 'rules',
    groupLabel: '规则配置',
    items: [
      { key: '/signatures', to: '/signatures', label: '特征库' },
      { key: '/compliance', to: '/compliance', label: '合规 · SLA · 客服' },
    ],
  },
  {
    groupKey: 'event',
    groupLabel: '赛事风控',
    items: [
      { key: '/competition', to: '/competition', label: '赛事风控' },
      { key: '/tournament', to: '/tournament', label: '赛事进程' },
    ],
  },
  {
    groupKey: 'system',
    groupLabel: '系统管理',
    items: [
      { key: '/login-logs', to: '/login-logs', label: '登录日志' },
      { key: '/audit', to: '/audit', label: '审计日志' },
      { key: '/system', to: '/system', label: '系统设置' },
    ],
  },
]

/** 路由 → 页面标题 + 所属分组，供面包屑使用 */
export const routeMeta: Record<string, { title: string; group: string }> = {
  '/': { title: '数据大盘', group: '数据概览' },
  '/redscreen': { title: '红屏管理', group: '检测管理' },
  '/inspect': { title: '查端控制台', group: '检测管理' },
  '/detection41': { title: 'v4.1 检测引擎', group: '检测管理' },
  '/records': { title: '作弊记录', group: '检测管理' },
  '/accounts': { title: 'PTEID 账号', group: '账号管理' },
  '/admins': { title: '管理员管理', group: '账号管理' },
  '/signatures': { title: '特征库', group: '规则配置' },
  '/compliance': { title: '合规 · SLA · 客服', group: '规则配置' },
  '/competition': { title: '赛事风控', group: '赛事风控' },
  '/tournament': { title: '赛事进程', group: '赛事风控' },
  '/login-logs': { title: '登录日志', group: '系统管理' },
  '/audit': { title: '审计日志', group: '系统管理' },
  '/system': { title: '系统设置', group: '系统管理' },
}

/** 根据当前路径匹配选中的菜单 key */
export function findSelectedKey(pathname: string): string | undefined {
  for (const g of navGroups) {
    for (const i of g.items) {
      if (i.exact ? pathname === i.to : pathname.startsWith(i.to)) {
        return i.key
      }
    }
  }
  return undefined
}
