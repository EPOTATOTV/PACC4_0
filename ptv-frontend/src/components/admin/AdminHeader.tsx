import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Avatar, Badge, Button, Dropdown, Input, Layout, Space, Tooltip } from 'antd'
import {
  BellOutlined,
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import { api } from '../../api/client'
import Brand from '../Brand'
import LanguageSwitcher from '../LanguageSwitcher'
import { useI18n } from '../../i18n'

const { Header } = Layout

/**
 * 管理端顶部导航栏：折叠按钮 + 品牌 + 全局搜索 + 通知铃铛 + 管理员头像。
 * 搜索为客户端菜单过滤（无后端全局搜索 API）；铃铛汇总待审申诉与开启工单，点击进合规页。
 */
export default function AdminHeader({
  collapsed,
  onToggle,
  onSearch,
}: {
  collapsed: boolean
  onToggle: () => void
  onSearch: (k: string) => void
}) {
  const navigate = useNavigate()
  const { t } = useI18n()
  const [todo, setTodo] = useState(0)

  useEffect(() => {
    let alive = true
    api.compliance
      .supportSummary()
      .then((s) => {
        if (alive) setTodo((s?.pending_appeals ?? 0) + (s?.open_tickets ?? 0))
      })
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [])

  async function logout() {
    try {
      await api.adminSession.logout()
    } finally {
      // 清 cookie 后整页刷新，由 /api/admin/me 重新判定登录态
      navigate(0)
    }
  }

  return (
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
        aria-label={collapsed ? t('common.expandNav') : t('common.collapseNav')}
        icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
        onClick={onToggle}
        style={{ color: '#c9d1d9' }}
      />
      <Brand size="sm" subtitle="PTV 管控后台" />
      <div style={{ flex: 1 }} />
      <Input.Search
        placeholder={t('common.searchMenu')}
        allowClear
        style={{ width: 240 }}
        onChange={(e) => onSearch(e.target.value)}
        onSearch={onSearch}
      />
      <Tooltip title={t('common.todo')}>
        <Badge count={todo} size="small">
          <Button
            type="text"
            aria-label={t('common.todo')}
            icon={<BellOutlined />}
            style={{ color: '#c9d1d9', fontSize: 16 }}
            onClick={() => navigate('/compliance')}
          />
        </Badge>
      </Tooltip>
      <LanguageSwitcher compact />
      <Dropdown
        menu={{
          items: [
            { key: 'settings', icon: <SettingOutlined />, label: t('common.systemSettings') },
            { type: 'divider' },
            { key: 'logout', icon: <LogoutOutlined />, label: t('common.logout'), danger: true },
          ],
          onClick: ({ key }) => {
            if (key === 'settings') navigate('/system')
            if (key === 'logout') logout()
          },
        }}
      >
        <Space style={{ cursor: 'pointer', padding: '0 4px' }}>
          <Avatar size={28} style={{ background: '#ff6b5e', fontSize: 13 }}>
            A
          </Avatar>
        </Space>
      </Dropdown>
    </Header>
  )
}
