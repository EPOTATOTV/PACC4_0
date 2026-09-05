import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, Button, Card, Form, Input, Segmented, Typography } from 'antd'
import { AuditOutlined, LockOutlined, TeamOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import Brand from '../components/Brand'

const { Text } = Typography
type Mode = 'key' | 'feishu'

export default function Login() {
  const [mode, setMode] = useState<Mode>('key')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')

  async function submit(v: { key: string }) {
    setBusy(true)
    setErr('')
    try {
      const res = await api.login(v.key)
      if (res.ok) {
        // 会话已写入 HttpOnly cookie，交由 /me 探测确认后重载进入后台
        location.reload()
        return
      }
      setErr(res.status === 429 ? '尝试过于频繁，请稍后再试' : '管理密钥错误或权限不足')
    } catch {
      setErr('无法连接 PTV 后端，请确认服务已启动')
    } finally {
      setBusy(false)
    }
  }

  async function feishuLogin() {
    setBusy(true)
    setErr('')
    try {
      const res = await api.feishu.url()
      const data = res.ok ? await res.json() : null
      if (data && data.url) {
        // 整页跳转到飞书授权页，员工同意后由后端回调签发 cookie 并跳回
        window.location.href = data.url
        return
      }
      setErr('飞书登录暂不可用，请使用授权密钥登录')
    } catch {
      setErr('无法连接 PTV 后端，请确认服务已启动')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      style={{
        minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px 12px',
      }}
    >
      <Card style={{ width: 400, boxShadow: '0 4px 24px rgba(0,0,0,0.4)' }} styles={{ body: { padding: 28 } }}>
        <Brand size="md" subtitle="PTV 管控平台 · 管理员登录" />

        <Segmented
          block
          value={mode}
          onChange={(m) => { setMode(m as Mode); setErr('') }}
          options={[
            { label: '超级管理员授权', value: 'key', icon: <AuditOutlined /> },
            { label: '公司飞书登录', value: 'feishu', icon: <TeamOutlined /> },
          ]}
          style={{ margin: '20px 0 4px' }}
        />

        {err && <Alert type="error" showIcon message={err} style={{ marginTop: 16 }} closable />}

        {mode === 'key' ? (
          <Form layout="vertical" onFinish={submit} style={{ marginTop: 16 }} requiredMark={false}>
            <Form.Item name="key" label="管理员授权密钥" rules={[{ required: true, message: '请输入授权密钥' }]}>
              <Input.Password prefix={<LockOutlined />} placeholder="由超级管理员分配的密钥" size="large" />
            </Form.Item>
            <Form.Item style={{ marginBottom: 4 }}>
              <Button type="primary" htmlType="submit" block size="large" loading={busy} danger>
                登 录
              </Button>
            </Form.Item>
            <Text type="secondary" style={{ fontSize: 12 }}>由系统超级管理员分配的授权密钥（含权限分级）</Text>
          </Form>
        ) : (
          <Button
            type="primary"
            block
            size="large"
            icon={<TeamOutlined />}
            loading={busy}
            onClick={feishuLogin}
            style={{ marginTop: 20 }}
          >
            使用飞书账号授权登录
          </Button>
        )}

        <div style={{ marginTop: 18, textAlign: 'center' }}>
          <Link to="/portal">前往玩家自助门户 ›</Link>
        </div>
      </Card>
    </div>
  )
}