import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Button, Layout, Menu } from 'antd'
import { LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import Brand from '../../components/Brand'
import LanguageSwitcher from '../../components/LanguageSwitcher'
import { useI18n } from '../../i18n'
import type { ReactNode } from 'react'

const { Sider, Content, Footer, Header } = Layout

const navItems = [
  { key: '/portal', to: '/portal', i18nKey: 'player.overview' },
  { key: '/portal/protection', to: '/portal/protection', i18nKey: 'player.protection' },
  { key: '/portal/monitor', to: '/portal/monitor', i18nKey: 'player.monitor' },
  { key: '/portal/tournament', to: '/portal/tournament', i18nKey: 'player.tournament' },
  { key: '/portal/records', to: '/portal/records', i18nKey: 'player.records' },
  { key: '/portal/devices', to: '/portal/devices', i18nKey: 'player.devices' },
  { key: '/portal/appeals', to: '/portal/appeals', i18nKey: 'player.appeals' },
  { key: '/portal/tickets', to: '/portal/tickets', i18nKey: 'player.tickets' },
  { key: '/portal/notifications', to: '/portal/notifications', i18nKey: 'player.notifications' },
  { key: '/portal/security', to: '/portal/security', i18nKey: 'player.security' },
  { key: '/portal/settings', to: '/portal/settings', i18nKey: 'player.settings' },
  { key: '/portal/diagnostics', to: '/portal/diagnostics', i18nKey: 'player.diagnostics' },
]

export default function PlayerLayout({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const location = useLocation()
  const { t } = useI18n()
  const [collapsed, setCollapsed] = useState(false)
  const selected = navItems.find((i) =>
    i.to === '/portal'
      ? location.pathname === '/portal'
      : location.pathname.startsWith(i.to),
  )?.key

  return (
    <Layout
      style={{
        minHeight: '100vh',
        background:
          'radial-gradient(1000px 520px at 82% -160px, rgba(255,77,61,.10), transparent 60%), var(--bg)',
      }}
    >
      <Header
        style={{
          position: 'sticky',
          top: 0,
          zIndex: 20,
          height: 56,
          lineHeight: '56px',
          background: 'rgba(5,6,8,.55)',
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
            background: 'rgba(5,6,8,.45)',
            borderRight: '1px solid var(--border)',
            backdropFilter: 'blur(6px)',
          }}
        >
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={selected ? [selected] : []}
            style={{ background: 'transparent', borderInlineEnd: 'none', paddingTop: 8 }}
            items={navItems.map((i) => ({ key: i.key, label: t(i.i18nKey), onClick: () => navigate(i.to) }))}
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
          <Content style={{ padding: 24, overflow: 'auto' }}>{children}</Content>
          <Footer style={{ textAlign: 'center', padding: '12px 16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8 }}>
              <Brand size="xs" title="PACC" />
              <span style={{ fontSize: 12, color: '#8b949e' }}>© 2026 POTATOTV · {t('player.portal')}</span>
            </div>
          </Footer>
        </Layout>
      </Layout>
    </Layout>
  )
}
