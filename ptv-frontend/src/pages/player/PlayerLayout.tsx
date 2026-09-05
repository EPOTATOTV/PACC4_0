import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Button, Layout, Menu } from 'antd'
import { LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import Brand from '../../components/Brand'
import type { ReactNode } from 'react'

const { Sider, Content, Footer, Header } = Layout

const navItems = [
  { key: '/portal', to: '/portal', label: '我的概览' },
  { key: '/portal/tournament', to: '/portal/tournament', label: '赛事中心' },
  { key: '/portal/records', to: '/portal/records', label: '作弊记录' },
  { key: '/portal/devices', to: '/portal/devices', label: '我的设备' },
  { key: '/portal/appeals', to: '/portal/appeals', label: '在线申诉' },
  { key: '/portal/tickets', to: '/portal/tickets', label: '客服工单' },
  { key: '/portal/settings', to: '/portal/settings', label: '客户端设置' },
  { key: '/portal/diagnostics', to: '/portal/diagnostics', label: '诊断工具' },
]

export default function PlayerLayout({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)
  const selected = navItems.find((i) =>
    i.to === '/portal'
      ? location.pathname === '/portal'
      : location.pathname.startsWith(i.to),
  )?.key

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header
        style={{
          height: 56,
          lineHeight: '56px',
          background: '#0d1117',
          borderBottom: '1px solid #30363d',
          padding: '0 16px',
          display: 'flex',
          alignItems: 'center',
          gap: 12,
        }}
      >
        <Button
          type="text"
          aria-label={collapsed ? '展开导航' : '收起导航'}
          icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
          onClick={() => setCollapsed((v) => !v)}
          style={{ color: '#c9d1d9' }}
        />
        <Brand size="sm" subtitle="玩家自助门户" />
      </Header>
      <Layout>
        <Sider
          width={220}
          collapsible
          collapsed={collapsed}
          trigger={null}
          breakpoint="lg"
          collapsedWidth={0}
          onBreakpoint={(broken) => setCollapsed(broken)}
          style={{ background: '#0d1117', borderRight: '1px solid #30363d' }}
        >
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={selected ? [selected] : []}
            style={{ background: 'transparent', borderInlineEnd: 'none', paddingTop: 8 }}
            items={navItems.map((i) => ({ key: i.key, label: i.label, onClick: () => navigate(i.to) }))}
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
              退出登录
            </Button>
          </div>
        </Sider>
        <Layout>
          <Content style={{ padding: 24, overflow: 'auto' }}>{children}</Content>
          <Footer style={{ textAlign: 'center', padding: '12px 16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8 }}>
              <Brand size="xs" title="PACC" />
              <span style={{ fontSize: 12, color: '#8b949e' }}>© 2026 POTATOTV · 玩家自助门户</span>
            </div>
          </Footer>
        </Layout>
      </Layout>
    </Layout>
  )
}
