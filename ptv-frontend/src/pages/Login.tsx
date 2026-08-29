import { useState } from 'react'
import { api, setAdminKey } from '../api/client'

export default function Login() {
  const [key, setKey] = useState('')
  const [err, setErr] = useState('')
  const [busy, setBusy] = useState(false)

  async function submit() {
    setBusy(true)
    setErr('')
    try {
      const res = await api.login(key)
      if (res.ok) {
        setAdminKey(key)
        location.reload()
      } else {
        setErr('管理密钥验证失败')
      }
    } catch {
      setErr('无法连接 PTV 后端，请确认服务已启动')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
    >
      <div className="card" style={{ width: 380 }}>
        <h2 style={{ marginTop: 0, color: '#ff3b30' }}>PACC 管控后台</h2>
        <p style={{ color: '#8b949e', marginTop: -8 }}>请输入管理员密钥以进入 PTV 管控平台</p>
        <input
          type="password"
          placeholder="管理员 API Key"
          value={key}
          onChange={(e) => setKey(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submit()}
          style={{
            width: '100%',
            padding: '10px 12px',
            margin: '12px 0',
            borderRadius: 6,
            border: '1px solid #30363d',
            background: '#0d1117',
            color: '#e6edf3',
          }}
        />
        {err && <div style={{ color: '#ff3b30', fontSize: 13, marginBottom: 8 }}>{err}</div>}
        <button
          onClick={submit}
          disabled={busy}
          style={{
            width: '100%',
            padding: '11px',
            borderRadius: 6,
            border: 'none',
            background: '#ff3b30',
            color: '#fff',
            fontWeight: 600,
            cursor: 'pointer',
          }}
        >
          {busy ? '验证中…' : '登 录'}
        </button>
        <div style={{ marginTop: 14, fontSize: 12, color: '#8b949e' }}>
          默认密钥：<code>pacc-admin-secret-key</code>（见 application.yml）
        </div>
      </div>
    </div>
  )
}