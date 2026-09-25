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
  /** 仅超级管理员可见（如「文档与协议」等含内部技术资料的入口） */
  superAdminOnly?: boolean
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
      { key: '/maps/bp', to: '/maps/bp', label: 'BP 会话', i18nKey: 'nav.bpSessions' },
      { key: '/maps', to: '/maps', label: '地图池', i18nKey: 'nav.mapPool' },
      { key: '/stream-live', to: '/stream-live', label: '直播转播', i18nKey: 'nav.streamLive' },
    ],
  },
  {
    groupKey: 'system',
    groupLabel: '系统管理',
    groupI18nKey: 'nav.group.system',
    items: [
      { key: '/login-logs', to: '/login-logs', label: '登录日志', i18nKey: 'nav.loginLogs' },
      { key: '/audit', to: '/audit', label: '审计日志', i18nKey: 'nav.audit' },
      { key: '/tenant', to: '/tenant', label: '租户管理', i18nKey: 'nav.tenant' },
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
  {
    groupKey: 'p1',
    groupLabel: '反作弊运营',
    groupI18nKey: 'nav.group.p1',
    items: [
      { key: '/effectiveness', to: '/effectiveness', label: '效果分析', i18nKey: 'nav.effectiveness' },
      { key: '/lists', to: '/lists', label: '黑白名单', i18nKey: 'nav.lists' },
      { key: '/config', to: '/config', label: '检测配置', i18nKey: 'nav.detectorConfig' },
      { key: '/motion', to: '/motion', label: '动效配置', i18nKey: 'nav.motionConfig' },
      { key: '/releases', to: '/releases', label: '版本发布', i18nKey: 'nav.releases' },
      { key: '/export', to: '/export', label: '数据导出', i18nKey: 'nav.exportCenter' },
    ],
  },
  {
    groupKey: 'v52',
    groupLabel: '智能检测运营',
    groupI18nKey: 'nav.group.v52',
    items: [
      { key: '/v52/model', to: '/v52/model', label: '模型管理', i18nKey: 'nav.v52Model' },
      { key: '/v52/profile', to: '/v52/profile', label: '行为画像', i18nKey: 'nav.v52Profile' },
      { key: '/v52/reputation', to: '/v52/reputation', label: '信誉管理', i18nKey: 'nav.v52Reputation' },
      { key: '/v52/replay', to: '/v52/replay', label: '查端回放', i18nKey: 'nav.v52Replay' },
      { key: '/v52/devices', to: '/v52/devices', label: '硬件指纹', i18nKey: 'nav.v52Devices' },
    ],
  },
  {
    groupKey: 'v54',
    groupLabel: '性能与安全加固',
    groupI18nKey: 'nav.group.v54',
    items: [
      { key: '/v54/apm', to: '/v54/apm', label: 'APM 性能监控', i18nKey: 'nav.v54Apm' },
      { key: '/v54/security', to: '/v54/security', label: '安全审计', i18nKey: 'nav.v54Security' },
      { key: '/v54/keys', to: '/v54/keys', label: '密钥管理', i18nKey: 'nav.v54Keys' },
    ],
  },
  {
    groupKey: 'df',
    groupLabel: '深度堡垒 DF',
    groupI18nKey: 'nav.group.df',
    items: [
      { key: '/df/screen', to: '/df/screen', label: '数据可视化大屏', i18nKey: 'nav.dfScreen' },
      { key: '/df/alerts-noise', to: '/df/alerts-noise', label: '告警降噪', i18nKey: 'nav.dfAlertNoise' },
      { key: '/df/automation', to: '/df/automation', label: '自动化响应', i18nKey: 'nav.dfAutomation' },
      { key: '/df/plugins', to: '/df/plugins', label: '插件运行时', i18nKey: 'nav.dfPlugins' },
      { key: '/df/tenant-quota', to: '/df/tenant-quota', label: '租户配额', i18nKey: 'nav.dfTenantQuota' },
    ],
  },
  {
    groupKey: 'docs',
    groupLabel: '文档与协议',
    groupI18nKey: 'nav.group.docs',
    superAdminOnly: true,
    items: [
      { key: '/detection41', to: '/detection41', label: 'v4.1 检测引擎', i18nKey: 'nav.detection41' },
      { key: '/countermeasure', to: '/countermeasure', label: '对抗巡检', i18nKey: 'nav.countermeasure' },
      { key: '/v46', to: '/v46', label: '检测深化', i18nKey: 'nav.v46' },
      { key: '/v47', to: '/v47', label: '情报运营中台', i18nKey: 'nav.v47' },
      { key: '/openapi', to: '/openapi', label: '开放 API', i18nKey: 'nav.openapi' },
      { key: '/bi', to: '/bi', label: 'BI 报表', i18nKey: 'nav.bi' },
    ],
  },
]

/** 路由 → 页面标题 i18n 键 + 所属分组 i18n 键，供面包屑使用 */
export const routeMeta: Record<string, { titleKey: string; groupKey: string }> = {
  '/': { titleKey: 'nav.dashboard', groupKey: 'nav.group.overview' },
  '/redscreen': { titleKey: 'nav.redscreen', groupKey: 'nav.group.detect' },
  '/inspect': { titleKey: 'nav.inspect', groupKey: 'nav.group.detect' },
  '/detection41': { titleKey: 'nav.detection41', groupKey: 'nav.group.docs' },
  '/countermeasure': { titleKey: 'nav.countermeasure', groupKey: 'nav.group.docs' },
  '/v46': { titleKey: 'nav.v46', groupKey: 'nav.group.docs' },
  '/v47': { titleKey: 'nav.v47', groupKey: 'nav.group.docs' },
  '/records': { titleKey: 'nav.records', groupKey: 'nav.group.detect' },
  '/accounts': { titleKey: 'nav.accounts', groupKey: 'nav.group.account' },
  '/admins': { titleKey: 'nav.admins', groupKey: 'nav.group.account' },
  '/signatures': { titleKey: 'nav.signatures', groupKey: 'nav.group.rules' },
  '/compliance': { titleKey: 'nav.compliance', groupKey: 'nav.group.rules' },
  '/competition': { titleKey: 'nav.competition', groupKey: 'nav.group.event' },
  '/tournament': { titleKey: 'nav.tournament', groupKey: 'nav.group.event' },
  '/maps': { titleKey: 'nav.mapPool', groupKey: 'nav.group.event' },
  '/maps/bp': { titleKey: 'nav.bpSessions', groupKey: 'nav.group.event' },
  '/stream-live': { titleKey: 'nav.streamLive', groupKey: 'nav.group.event' },
  '/login-logs': { titleKey: 'nav.loginLogs', groupKey: 'nav.group.system' },
  '/audit': { titleKey: 'nav.audit', groupKey: 'nav.group.system' },
  '/tenant': { titleKey: 'nav.tenant', groupKey: 'nav.group.system' },
  '/system': { titleKey: 'nav.system', groupKey: 'nav.group.system' },
  '/openapi': { titleKey: 'nav.openapi', groupKey: 'nav.group.docs' },
  '/bi': { titleKey: 'nav.bi', groupKey: 'nav.group.docs' },
  '/ab': { titleKey: 'nav.ab', groupKey: 'nav.group.ops' },
  '/ops': { titleKey: 'nav.ops', groupKey: 'nav.group.ops' },
  '/support': { titleKey: 'nav.support', groupKey: 'nav.group.ops' },
  '/effectiveness': { titleKey: 'nav.effectiveness', groupKey: 'nav.group.p1' },
  '/lists': { titleKey: 'nav.lists', groupKey: 'nav.group.p1' },
  '/config': { titleKey: 'nav.detectorConfig', groupKey: 'nav.group.p1' },
  '/motion': { titleKey: 'nav.motionConfig', groupKey: 'nav.group.p1' },
  '/releases': { titleKey: 'nav.releases', groupKey: 'nav.group.p1' },
  '/export': { titleKey: 'nav.exportCenter', groupKey: 'nav.group.p1' },
  '/v52/model': { titleKey: 'nav.v52Model', groupKey: 'nav.group.v52' },
  '/v52/profile': { titleKey: 'nav.v52Profile', groupKey: 'nav.group.v52' },
  '/v52/reputation': { titleKey: 'nav.v52Reputation', groupKey: 'nav.group.v52' },
  '/v52/replay': { titleKey: 'nav.v52Replay', groupKey: 'nav.group.v52' },
  '/v52/devices': { titleKey: 'nav.v52Devices', groupKey: 'nav.group.v52' },
  '/v54/apm': { titleKey: 'nav.v54Apm', groupKey: 'nav.group.v54' },
  '/v54/security': { titleKey: 'nav.v54Security', groupKey: 'nav.group.v54' },
  '/v54/keys': { titleKey: 'nav.v54Keys', groupKey: 'nav.group.v54' },
  '/df/screen': { titleKey: 'nav.dfScreen', groupKey: 'nav.group.df' },
  '/df/alerts-noise': { titleKey: 'nav.dfAlertNoise', groupKey: 'nav.group.df' },
  '/df/automation': { titleKey: 'nav.dfAutomation', groupKey: 'nav.group.df' },
  '/df/plugins': { titleKey: 'nav.dfPlugins', groupKey: 'nav.group.df' },
  '/df/tenant-quota': { titleKey: 'nav.dfTenantQuota', groupKey: 'nav.group.df' },
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
