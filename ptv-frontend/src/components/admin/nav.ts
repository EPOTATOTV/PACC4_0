/**
 * 管理端导航与页面元数据：分组菜单 + 面包屑/标题 的单一事实源。
 * 按 v4.1 设计文档的「顶部导航栏 + 左侧分组菜单 + 面包屑」布局组织。
 */

export interface NavItem {
  key: string
  to: string
  /** 静态中文标签（兼容无 i18n 的消费方） */
  label: string
  /** i18n 文案键，如 /redscreen → nav.redscreen */
  i18nKey: string
  exact?: boolean
}

export interface NavGroup {
  groupKey: string
  groupLabel: string
  /** i18n 分组键，如 detect → nav.group.detect */
  groupI18nKey: string
  items: NavItem[]
}

export const navGroups: NavGroup[] = [
  {
    groupKey: 'overview',
    groupLabel: '数据概览',
    groupI18nKey: 'nav.group.overview',
    items: [{ key: '/', to: '/', label: '数据大盘', i18nKey: 'nav.dashboard', exact: true }],
  },
  {
    groupKey: 'detect',
    groupLabel: '检测管理',
    groupI18nKey: 'nav.group.detect',
    items: [
      { key: '/redscreen', to: '/redscreen', label: '红屏管理', i18nKey: 'nav.redscreen' },
      { key: '/inspect', to: '/inspect', label: '查端控制台', i18nKey: 'nav.inspect' },
      { key: '/detection41', to: '/detection41', label: 'v4.1 检测引擎', i18nKey: 'nav.detection41' },
      { key: '/countermeasure', to: '/countermeasure', label: '对抗巡检', i18nKey: 'nav.countermeasure' },
      { key: '/v46', to: '/v46', label: '检测深化', i18nKey: 'nav.v46' },
      { key: '/records', to: '/records', label: '作弊记录', i18nKey: 'nav.records' },
    ],
  },
  {
    groupKey: 'account',
    groupLabel: '账号管理',
    groupI18nKey: 'nav.group.account',
    items: [
      { key: '/accounts', to: '/accounts', label: 'PTEID 账号', i18nKey: 'nav.accounts' },
      { key: '/admins', to: '/admins', label: '管理员管理', i18nKey: 'nav.admins' },
    ],
  },
  {
    groupKey: 'rules',
    groupLabel: '规则配置',
    groupI18nKey: 'nav.group.rules',
    items: [
      { key: '/signatures', to: '/signatures', label: '特征库', i18nKey: 'nav.signatures' },
      { key: '/compliance', to: '/compliance', label: '合规 · SLA · 客服', i18nKey: 'nav.compliance' },
    ],
  },
  {
    groupKey: 'event',
    groupLabel: '赛事风控',
    groupI18nKey: 'nav.group.event',
    items: [
      { key: '/competition', to: '/competition', label: '赛事风控', i18nKey: 'nav.competition' },
      { key: '/tournament', to: '/tournament', label: '赛事进程', i18nKey: 'nav.tournament' },
    ],
  },
  {
    groupKey: 'system',
    groupLabel: '系统管理',
    groupI18nKey: 'nav.group.system',
    items: [
      { key: '/login-logs', to: '/login-logs', label: '登录日志', i18nKey: 'nav.loginLogs' },
      { key: '/audit', to: '/audit', label: '审计日志', i18nKey: 'nav.audit' },
      { key: '/system', to: '/system', label: '系统设置', i18nKey: 'nav.system' },
    ],
  },
  {
    groupKey: 'ops',
    groupLabel: '运营自动化',
    groupI18nKey: 'nav.group.ops',
    items: [
      { key: '/ab', to: '/ab', label: 'A/B 实验', i18nKey: 'nav.ab' },
      { key: '/ops', to: '/ops', label: '运维中心', i18nKey: 'nav.ops' },
      { key: '/support', to: '/support', label: '客服工单', i18nKey: 'nav.support' },
    ],
  },
]

/** 路由 → 页面标题 i18n 键 + 所属分组 i18n 键，供面包屑使用 */
export const routeMeta: Record<string, { titleKey: string; groupKey: string }> = {
  '/': { titleKey: 'nav.dashboard', groupKey: 'nav.group.overview' },
  '/redscreen': { titleKey: 'nav.redscreen', groupKey: 'nav.group.detect' },
  '/inspect': { titleKey: 'nav.inspect', groupKey: 'nav.group.detect' },
  '/detection41': { titleKey: 'nav.detection41', groupKey: 'nav.group.detect' },
  '/countermeasure': { titleKey: 'nav.countermeasure', groupKey: 'nav.group.detect' },
  '/v46': { titleKey: 'nav.v46', groupKey: 'nav.group.detect' },
  '/records': { titleKey: 'nav.records', groupKey: 'nav.group.detect' },
  '/accounts': { titleKey: 'nav.accounts', groupKey: 'nav.group.account' },
  '/admins': { titleKey: 'nav.admins', groupKey: 'nav.group.account' },
  '/signatures': { titleKey: 'nav.signatures', groupKey: 'nav.group.rules' },
  '/compliance': { titleKey: 'nav.compliance', groupKey: 'nav.group.rules' },
  '/competition': { titleKey: 'nav.competition', groupKey: 'nav.group.event' },
  '/tournament': { titleKey: 'nav.tournament', groupKey: 'nav.group.event' },
  '/login-logs': { titleKey: 'nav.loginLogs', groupKey: 'nav.group.system' },
  '/audit': { titleKey: 'nav.audit', groupKey: 'nav.group.system' },
  '/system': { titleKey: 'nav.system', groupKey: 'nav.group.system' },
  '/ab': { titleKey: 'nav.ab', groupKey: 'nav.group.ops' },
  '/ops': { titleKey: 'nav.ops', groupKey: 'nav.group.ops' },
  '/support': { titleKey: 'nav.support', groupKey: 'nav.group.ops' },
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
