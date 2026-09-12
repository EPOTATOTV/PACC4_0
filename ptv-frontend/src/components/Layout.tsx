import { useEffect, useMemo, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { Breadcrumb, Layout, Menu } from 'antd'
import Brand from './Brand'
import AdminHeader from './admin/AdminHeader'
import { findSelectedKey, navGroups } from './admin/nav'
import { useI18n } from '../i18n'
import type { ReactNode } from 'react'

const { Sider, Content, Footer } = Layout

export default function AdminLayout({ children, role }: { children: ReactNode; role?: string }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [collapsed, setCollapsed] = useState(false)
  const [search, setSearch] = useState('')
  const { t } = useI18n()

  // 「文档与协议」等分组仅超级管理员可见，非超管前置过滤掉对应分组
  const isSuper = role === 'super-admin'
  const visibleGroups = useMemo(
    () => navGroups.filter((g) => (g.superAdminOnly ? isSuper : true)),
    [isSuper],
  )

  // 分组菜单：搜索关键词过滤菜单项（客户端过滤，无后端全局搜索），标签经 i18n 翻译。
  // 分组用可折叠 submenu（大标题可收起/展开显示小标题），路由所在分组自动展开。
  const menuItems = useMemo(() => {
    const kw = search.trim().toLowerCase()
    return visibleGroups
      .map((g) => ({
        ...g,
        items: kw ? g.items.filter((i) => i.i18nKey.includes(kw) || i.label.toLowerCase().includes(kw)) : g.items,
      }))
      .filter((g) => g.items.length > 0)
      .map((g) => ({
        key: g.groupKey,
        label: t(g.groupI18nKey),
        children: g.items.map((i) => ({
          key: i.key,
          label: t(i.i18nKey),
          onClick: () => navigate(i.to),
        })),
      }))
  }, [search, navigate, t, visibleGroups])

  const selected = findSelectedKey(location.pathname)
  // 当前路由所属分组：路由切换时自动展开该分组（仅统计可见分组）
  const selectedGroup = visibleGroups.find((g) => g.items.some((i) => i.key === selected))?.groupKey
  const [openKeys, setOpenKeys] = useState<string[]>([])
  useEffect(() => {
    if (selectedGroup && !openKeys.includes(selectedGroup)) {
      setOpenKeys((prev) => (prev.includes(selectedGroup) ? prev : [...prev, selectedGroup]))
    }
  }, [selectedGroup])

  return (
    <Layout
      style={{
        minHeight: '100vh',
        background:
          'radial-gradient(1000px 520px at 82% -160px, rgba(255,77,61,.10), transparent 60%), var(--bg)',
      }}
    >
      <AdminHeader collapsed={collapsed} onToggle={() => setCollapsed((v) => !v)} onSearch={setSearch} />
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
            openKeys={openKeys}
            onOpenChange={(keys) => setOpenKeys(keys as string[])}
            style={{ background: 'transparent', borderInlineEnd: 'none', paddingTop: 4 }}
            items={menuItems}
          />
        </Sider>
        <Layout>
          <Content style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
            <Breadcrumb
              style={{ padding: '14px 24px 0', fontSize: 13 }}
              items={
                [navGroups.find((g) => g.items.some((i) => i.key === selected))?.groupI18nKey, navGroups.flatMap((g) => g.items).find((i) => i.key === selected)?.i18nKey]
                  .filter((k): k is string => !!k)
                  .map((k) => ({ title: t(k) }))
              }
            />
            <div style={{ padding: '16px 20px', flex: 1, overflow: 'auto' }}>
              <div style={{ maxWidth: 1400, margin: '0 auto', width: '100%' }}>{children}</div>
            </div>
          </Content>
          <Footer style={{ textAlign: 'center', padding: '12px 16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8 }}>
              <Brand size="xs" title="PACC" />
              <span style={{ fontSize: 12, color: 'var(--muted)' }}>
                © 2026 POTATOTV · PACC Anti-Cheat · v4.2.0
              </span>
            </div>
          </Footer>
        </Layout>
      </Layout>
    </Layout>
  )
}
