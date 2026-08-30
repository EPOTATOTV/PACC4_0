import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Button, Layout, Menu, Typography } from 'antd'
import { LogoutOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import type { ReactNode } from 'react'

const { Sider, Content } = Layout
const { Text } = Typography

const navItems = [
  { key: '/', to: '/', label: '数据大盘', exact: true },
  { key: '/redscreen', to: '/redscreen', label: '红屏管理' },
  { key: '/inspect', to: '/inspect', label: '查端控制台' },
  { key: '/signatures', to: '/signatures', label: '特征库' },
  { key: '/records', to: '/records', label: '作弊记录' },
  { key: '/competition', to: '/competition', label: '赛事风控' },
  { key: '/tournament', to: '/tournament', label: '赛事进程' },
  { key: '/accounts', to: '/accounts', label: '账号' },
  { key: '/detection41', to: '/detection41', label: 'v4.1 检测引擎' },
  { key: '/compliance', to: '/compliance', label: '合规·SLA·客服' },
  { key: '/login-logs', to: '/login-logs', label: '登录日志' },
]

export default function AdminLayout({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)

  async function logout() {
    try {
      await api.adminSession.logout()
    } finally {
      // 清 cookie 后整页刷新，由 /api/admin/me 重新判定登录态
      navigate(0)
    }
  }

  const selected = navItems.find((i) =>
    i.exact ? location.pathname === i.to : location.pathname.startsWith(i.to),
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
                说明：按用户要求，Logo/企业外观标识本版本不替换。
                若要挂载正式 LOGO，请在此插入
                <img src="/logo.png" width={120} /> 并将资产放入
                ptv-frontend/public/logo.png。当前保留文字标题。 */}
            <div style={{ fontSize: 18, fontWeight: 700, color: '#ff3b30' }}>PACC 管控后台</div>
            <Text type="secondary" style={{ fontSize: 11 }}>Potatotv Anti-Cheat (PTV)</Text>
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
              onClick={() => logout()}
            >
              退出登录
            </Button>
          </div>
        )}
      </Sider>
      <Content style={{ padding: 24, overflow: 'auto' }}>{children}</Content>
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