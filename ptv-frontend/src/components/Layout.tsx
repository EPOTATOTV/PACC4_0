import { useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Breadcrumb, Layout, Menu } from 'antd'
import Brand from './Brand'
import AdminHeader from './admin/AdminHeader'
import { findSelectedKey, navGroups } from './admin/nav'
import type { ReactNode } from 'react'

const { Sider, Content, Footer } = Layout

export default function AdminLayout({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)
  const [search, setSearch] = useState('')

  // 分组菜单：搜索关键词过滤菜单项（客户端过滤，无后端全局搜索）
  const menuItems = useMemo(() => {
    const kw = search.trim().toLowerCase()
    return navGroups
      .map((g) => ({
        ...g,
        items: kw ? g.items.filter((i) => i.label.toLowerCase().includes(kw)) : g.items,
      }))
      .filter((g) => g.items.length > 0)
      .map((g) => ({
        type: 'group' as const,
        label: g.groupLabel,
        children: g.items.map((i) => ({
          key: i.key,
          label: i.label,
          onClick: () => navigate(i.to),
        })),
      }))
  }, [search, navigate])

  const selected = findSelectedKey(location.pathname)

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <AdminHeader collapsed={collapsed} onToggle={() => setCollapsed((v) => !v)} onSearch={setSearch} />
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
            style={{ background: 'transparent', borderInlineEnd: 'none', paddingTop: 4 }}
            items={menuItems}
          />
        </Sider>
        <Layout>
          <Content style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
            <Breadcrumb
              style={{ padding: '14px 24px 0', fontSize: 13 }}
              items={[
                { title: navGroups.find((g) => g.items.some((i) => i.key === selected))?.groupLabel },
                { title: navGroups.flatMap((g) => g.items).find((i) => i.key === selected)?.label },
              ].filter((i) => i.title)}
            />
            <div style={{ padding: 16, flex: 1, overflow: 'auto' }}>{children}</div>
          </Content>
          <Footer style={{ textAlign: 'center', padding: '12px 16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8 }}>
              <Brand size="xs" title="PACC" />
              <span style={{ fontSize: 12, color: '#8b949e' }}>
                © 2026 POTATOTV · PACC Anti-Cheat · v4.2.0
              </span>
            </div>
          </Footer>
        </Layout>
      </Layout>
    </Layout>
  )
}
