import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Button, Layout, Menu, Typography } from 'antd'
import { LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { ReactNode } from 'react'

const { Sider, Content } = Layout
const { Text } = Typography

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
        {!collapsed && (
          <div style={{ padding: '20px 20px 12px' }}>
            {/* ============ 品牌 LOGO 占位点 ============
                说明：Logo/企业外观标识本版本不替换。
                替换时在此插入 <img src="/logo.png" width={120} /> 并放置
                ptv-frontend/public/logo.png；当前保留文字标题。 */}
            <div style={{ fontSize: 18, fontWeight: 700, color: '#ff6b5e' }}>PACC 玩家中心</div>
            <Text type="secondary" style={{ fontSize: 11 }}>Potatotv Player Portal</Text>
          </div>
        )}
        {!collapsed && (
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={selected ? [selected] : []}
            style={{ background: 'transparent', borderInlineEnd: 'none' }}
            items={navItems.map((i) => ({ key: i.key, label: i.label, onClick: () => navigate(i.to) }))}
          />
        )}
        {!collapsed && (
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
        )}
      </Sider>
      <Content style={{ padding: 24 }}>{children}</Content>
      <Button
        type="text"
        aria-label={collapsed ? '展开导航' : '收起导航'}
        icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
        onClick={() => setCollapsed((v) => !v)}
        style={{
          position: 'fixed', right: 16, bottom: 16, zIndex: 10,
          color: '#c9d1d9', fontSize: 16, width: 40, height: 40,
          borderRadius: '50%',
        }}
      />
    </Layout>
  )
}