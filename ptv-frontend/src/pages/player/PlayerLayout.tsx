import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Button, Layout, Menu } from 'antd'
import { LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import Brand from '../../components/Brand'
import LanguageSwitcher from '../../components/LanguageSwitcher'
import { useI18n } from '../../i18n'
import type { ReactNode } from 'react'

const { Sider, Content, Footer, Header } = Layout

const navGroups = [
  {
    groupKey: 'player.group.protection',
    groupI18nKey: 'player.group.protection',
    items: [
      { key: '/portal/protection', to: '/portal/protection', i18nKey: 'player.protection' },
      { key: '/portal/monitor', to: '/portal/monitor', i18nKey: 'player.monitor' },
      { key: '/portal/devices', to: '/portal/devices', i18nKey: 'player.devices' },
      { key: '/portal/diagnostics', to: '/portal/diagnostics', i18nKey: 'player.diagnostics' },
    ],
  },
  {
    groupKey: 'player.group.event',
    groupI18nKey: 'player.group.event',
    items: [
      { key: '/portal/tournament', to: '/portal/tournament', i18nKey: 'player.tournament' },
      { key: '/portal/maps', to: '/portal/maps', i18nKey: 'player.mapPools' },
      { key: '/portal/stream-live', to: '/portal/stream-live', i18nKey: 'player.streamLive.title' },
    ],
  },
  {
    groupKey: 'player.group.records',
    groupI18nKey: 'player.group.records',
    items: [
      { key: '/portal/records', to: '/portal/records', i18nKey: 'player.records' },
      { key: '/portal/appeals', to: '/portal/appeals', i18nKey: 'player.appeals' },
      { key: '/portal/tickets', to: '/portal/tickets', i18nKey: 'player.tickets' },
    ],
  },
  {
    groupKey: 'player.group.account',
    groupI18nKey: 'player.group.account',
    items: [
      { key: '/portal/notifications', to: '/portal/notifications', i18nKey: 'player.notifications' },
      { key: '/portal/security', to: '/portal/security', i18nKey: 'player.security' },
      { key: '/portal/settings', to: '/portal/settings', i18nKey: 'player.settings' },
    ],
  },
  {
    groupKey: 'player.group.service',
    groupI18nKey: 'player.group.service',
    items: [
      { key: '/portal/reputation', to: '/portal/reputation', i18nKey: 'player.reputation' },
      { key: '/portal/help', to: '/portal/help', i18nKey: 'player.help' },
      { key: '/portal/download', to: '/portal/download', i18nKey: 'player.download' },
      { key: '/portal/about', to: '/portal/about', i18nKey: 'player.about' },
    ],
  },
]

export default function PlayerLayout({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const location = useLocation()
  const { t } = useI18n()
  const [collapsed, setCollapsed] = useState(false)
  const allNav = navGroups.flatMap((g) => g.items)
  const selected = allNav.find((i) =>
    i.to === '/portal'
      ? location.pathname === '/portal'
      : location.pathname.startsWith(i.to),
  )?.key
  // 路由所在分组自动展开，其余可手动收起/展开
  const selectedGroup = navGroups.find((g) => g.items.some((i) => i.key === selected))?.groupKey
  const [openKeys, setOpenKeys] = useState<string[]>([])
  useEffect(() => {
    if (!selectedGroup) return
    setOpenKeys((prev) => (prev.includes(selectedGroup) ? prev : [...prev, selectedGroup]))
  }, [selectedGroup])

  return (
    <div style={{ position: 'relative', minHeight: '100vh' }}>
      {/* 全站液态玻璃底光斑层：置于内容之下，玻璃面板可对其透光折射 */}
      <div aria-hidden className="pacc-backdrop-a" />
      <div aria-hidden className="pacc-backdrop-b" />
      <Layout
        style={{
          position: 'relative', zIndex: 1,
          minHeight: '100vh',
          background: 'transparent',
        }}
      >
      <Header
        style={{
          position: 'sticky',
          top: 0,
          zIndex: 20,
          height: 56,
          lineHeight: '56px',
          background: 'var(--glass-bar)',
          backdropFilter: 'blur(14px) saturate(140%)',
          borderBottom: '1px solid var(--border)',
          padding: '0 16px',
          display: 'flex',
          alignItems: 'center',
          gap: 12,
        }}
      >
        <Button
          type="text"
          aria-label={collapsed ? t('common.expandNav') : t('common.collapseNav')}
          icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
          onClick={() => setCollapsed((v) => !v)}
          style={{ color: '#c9d1d9' }}
        />
        <Brand size="sm" subtitle={t('player.portal')} />
        <div style={{ flex: 1 }} />
        <LanguageSwitcher compact />
      </Header>
      <Layout style={{ background: 'transparent' }}>
        <Sider
          width={220}
          collapsible
          collapsed={collapsed}
          trigger={null}
          breakpoint="lg"
          collapsedWidth={0}
          onBreakpoint={(broken) => setCollapsed(broken)}
          style={{
            background: 'var(--glass-bar)',
            borderRight: '1px solid var(--border)',
            backdropFilter: 'blur(6px)',
          }}
        >
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={selected ? [selected] : []}
            openKeys={openKeys}
            onOpenChange={(keys) => setOpenKeys(keys as string[])}
            style={{ background: 'transparent', borderInlineEnd: 'none', paddingTop: 8 }}
            items={[
              { key: '/portal', label: t('player.overview'), onClick: () => navigate('/portal') },
              ...navGroups.map((g) => ({
                key: g.groupKey,
                label: t(g.groupI18nKey),
                children: g.items.map((i) => ({
                  key: i.key,
                  label: t(i.i18nKey),
                  onClick: () => navigate(i.to),
                })),
              })),
            ]}
          />
          <div style={{ padding: '16px' }}>
            <Button
              block
              icon={<LogoutOutlined />}
              onClick={async () => {
                await api.player.logout().catch(() => {})
                navigate('/portal')
              }}
            >
              {t('player.logout')}
            </Button>
          </div>
        </Sider>
        <Layout>
          <Content style={{ padding: '24px 22px', overflow: 'auto' }}>
            <div style={{ maxWidth: 1320, margin: '0 auto', width: '100%' }}>{children}</div>
          </Content>
          <Footer style={{ textAlign: 'center', padding: '12px 16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8 }}>
              <Brand size="xs" title="PACC" />
              <span style={{ fontSize: 12, color: 'var(--muted)' }}>© 2026 POTATOTV · {t('player.portal')}</span>
            </div>
          </Footer>
        </Layout>
      </Layout>
    </Layout>
    </div>
  )
}
