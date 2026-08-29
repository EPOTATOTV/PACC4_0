import { NavLink, useNavigate } from 'react-router-dom'
import { clearAuth } from '../api/client'
import type { ReactNode } from 'react'

export default function Layout({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const navItems = [
    { to: '/', label: '数据大盘' },
    { to: '/redscreen', label: '红屏管理' },
    { to: '/inspect', label: '查端控制台' },
    { to: '/signatures', label: '特征库' },
    { to: '/accounts', label: '账号' },
  ]
  return (
    <div style={{ display: 'flex', minHeight: '100vh' }}>
      <aside
        style={{
          width: 220,
          background: '#0d1117',
          borderRight: '1px solid #30363d',
          padding: '18px 12px',
          display: 'flex',
          flexDirection: 'column',
          gap: 4,
        }}
      >
        {/* ============ 品牌 LOGO 占位点 ============
            说明：按用户要求，Logo/企业外观标识本版本不替换。
            若要为侧边栏挂载正式 LOGO，请在此处插入
            <img src="/logo.png" width={120} /> 并将资产放入
            ptv-frontend/public/logo.png。当前保留文字标题。 */}
        <div style={{ fontSize: 18, fontWeight: 700, color: '#ff3b30', padding: '6px 10px 16px' }}>
          PACC 管控后台
          <div style={{ fontSize: 11, fontWeight: 400, color: '#8b949e', marginTop: 2 }}>
            Potatotv Anti-Cheat (PTV)
          </div>
        </div>
        <NavLink
          key="/detection41"
          to="/detection41"
          end={false}
          style={({ isActive }) => ({
            display: 'block',
            padding: '10px 12px',
            borderRadius: 6,
            color: isActive ? '#0d1117' : '#e6edf3',
            background: isActive ? '#ff6b5e' : 'transparent',
            fontWeight: isActive ? 600 : 400,
          })}
        >
          v4.1 检测引擎
        </NavLink>
        <NavLink
          key="/compliance"
          to="/compliance"
          end={false}
          style={({ isActive }) => ({
            display: 'block',
            padding: '10px 12px',
            borderRadius: 6,
            color: isActive ? '#0d1117' : '#e6edf3',
            background: isActive ? '#ff6b5e' : 'transparent',
            fontWeight: isActive ? 600 : 400,
          })}
        >
          合规·SLA·客服
        </NavLink>
        {navItems.map((it) => (
          <NavLink
            key={it.to}
            to={it.to}
            end={it.to === '/'}
            style={({ isActive }) => ({
              display: 'block',
              padding: '10px 12px',
              borderRadius: 6,
              color: isActive ? '#0d1117' : '#e6edf3',
              background: isActive ? '#ff6b5e' : 'transparent',
              fontWeight: isActive ? 600 : 400,
            })}
          >
            {it.label}
          </NavLink>
        ))}
        <button
          onClick={() => {
            clearAuth()
            navigate('/')
          }}
          style={{
            marginTop: 'auto',
            padding: '10px 12px',
            border: '1px solid #30363d',
            background: '#161b22',
            color: '#e6edf3',
            borderRadius: 6,
            cursor: 'pointer',
          }}
        >
          退出登录
        </button>
      </aside>
      <main style={{ flex: 1, padding: 24, overflow: 'auto' }}>{children}</main>
    </div>
  )
}