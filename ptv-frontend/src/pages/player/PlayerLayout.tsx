import { NavLink, useNavigate } from 'react-router-dom'
import { clearPlayerAuth } from '../../api/client'
import type { ReactNode } from 'react'

const navItems = [
  { to: '/portal', label: '我的概览', end: true },
  { to: '/portal/tournament', label: '赛事中心', end: false },
  { to: '/portal/records', label: '作弊记录', end: false },
  { to: '/portal/devices', label: '我的设备', end: false },
  { to: '/portal/appeals', label: '在线申诉', end: false },
  { to: '/portal/tickets', label: '客服工单', end: false },
]

export default function PlayerLayout({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  return (
    <div style={{ display: 'flex', minHeight: '100vh' }}>
      <aside
        style={{
          width: 220, background: '#0d1117', borderRight: '1px solid #30363d',
          padding: '18px 12px', display: 'flex', flexDirection: 'column', gap: 4,
        }}
      >
        {/* ============ 品牌 LOGO 占位点 ============
            说明：Logo/企业外观标识本版本不替换。
            替换时在此插入 <img src="/logo.png" width={120} /> 并放置
            ptv-frontend/public/logo.png；当前保留文字标题。 */}
        <div style={{ fontSize: 18, fontWeight: 700, color: '#ff6b5e', padding: '6px 10px 16px' }}>
          PACC 玩家中心
          <div style={{ fontSize: 11, fontWeight: 400, color: '#8b949e', marginTop: 2 }}>
            Potatotv Player Portal
          </div>
        </div>
        {navItems.map((it) => (
          <NavLink
            key={it.to}
            to={it.to}
            end={it.end}
            style={({ isActive }) => ({
              display: 'block', padding: '10px 12px', borderRadius: 6,
              color: isActive ? '#0d1117' : '#e6edf3',
              background: isActive ? '#ff6b5e' : 'transparent',
              fontWeight: isActive ? 600 : 400,
            })}
          >
            {it.label}
          </NavLink>
        ))}
        <button
          onClick={() => { clearPlayerAuth(); navigate('/portal') }}
          style={{
            marginTop: 'auto', padding: '10px 12px', border: '1px solid #30363d',
            background: '#161b22', color: '#e6edf3', borderRadius: 6, cursor: 'pointer',
          }}
        >
          退出登录
        </button>
      </aside>
      <main style={{ flex: 1, padding: 24 }}>{children}</main>
    </div>
  )
}