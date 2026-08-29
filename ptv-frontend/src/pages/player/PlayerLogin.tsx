import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, setPlayerToken } from '../../api/client'

export default function PlayerLogin() {
  const [identity, setIdentity] = useState('')
  const [password, setPassword] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)
  const navigate = useNavigate()

  async function submit() {
    if (!identity || !password) return setErr('请输入账号与密码')
    setBusy(true)
    setErr('')
    try {
      const res = await api.auth.login(identity, password, true)
      if (!res.ok) {
        const data = await res.json().catch(() => ({}))
        setErr((data as { error?: string }).error || `登录失败 (${res.status})`)
        return
      }
      const data = (await res.json()) as { access_token: string }
      setPlayerToken(data.access_token)
      navigate('/portal')
    } catch {
      setErr('无法连接 PTV 后端，请稍后再试')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div className="card" style={{ width: 380 }}>
      {/* ============ 品牌 LOGO 占位点 ============
          说明：Logo/企业外观标识本版本不替换。
          替换时在此插入 <img src="/logo.png" width={120} /> 并放置
         ptv-frontend/public/logo.png；当前保留文字标题。 */}
        <h2 style={{ marginTop: 0, color: '#ff6b5e', marginBottom: 2 }}>PACC 玩家自助中心</h2>
        <p style={{ color: '#8b949e', marginTop: 0 }}>查询个人记录 · 在线申诉 · 客服工单</p>
        <input
          placeholder="账号（PTEID 或注册邮箱/手机）"
          value={identity}
          onChange={(e) => setIdentity(e.target.value)}
          style={input}
        />
        <input
          type="password"
          placeholder="密码"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submit()}
          style={{ ...input, marginTop: 8 }}
        />
        {err && <div style={{ color: '#ff3b30', fontSize: 13, margin: '8px 0' }}>{err}</div>}
        <button
          onClick={submit}
          disabled={busy}
          style={{
            width: '100%', padding: 11, marginTop: 12, borderRadius: 6, border: 'none',
            background: '#ff6b5e', color: '#fff', fontWeight: 600, cursor: 'pointer',
          }}
        >
          {busy ? '登录中…' : '登 录'}
        </button>
        <div style={{ marginTop: 14, fontSize: 12, color: '#8b949e', textAlign: 'center' }}>
          <Link to="/" style={{ color: '#58a6ff', textDecoration: 'none' }}>‹ 返回管理后台</Link>
        </div>
      </div>
    </div>
  )
}

const input = {
  width: '100%', padding: '10px 12px', margin: '12px 0 0', borderRadius: 6,
  border: '1px solid #30363d', background: '#0d1117', color: '#e6edf3',
} as const