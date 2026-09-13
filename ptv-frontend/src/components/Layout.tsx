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
            style={{ background: 'transparent', borderInlineEnd: 'none', paddingTop: 4 }}
            items={menuItems}
          />
        </Sider>
        <Layout>
          <Content style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
            <Breadcrumb
              style={{ padding: '17px 26px 9px', fontSize: 12.5, letterSpacing: '.02em' }}
              items={
                [navGroups.find((g) => g.items.some((i) => i.key === selected))?.groupI18nKey, navGroups.flatMap((g) => g.items).find((i) => i.key === selected)?.i18nKey]
                  .filter((k): k is string => !!k)
                  .map((k) => ({ title: t(k) }))
              }
            />
            <div style={{ padding: '21px 23px 34px', flex: 1, overflow: 'auto' }}>
              <div style={{ maxWidth: 1400, margin: '0 auto', width: '100%' }}>{children}</div>
            </div>
          </Content>
          <Footer style={{ textAlign: 'center', padding: '12px 16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8 }}>
              <Brand size="xs" title="PACC" />
              <span style={{ fontSize: 12, color: 'var(--muted)' }}>
                © 2026 POTATOTV · PACC Anti-Cheat · v5.0.0
              </span>
            </div>
          </Footer>
        </Layout>
      </Layout>
    </Layout>
    </div>
  )
}
