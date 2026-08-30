import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, Button, Card, Form, Input, Segmented, Typography } from 'antd'
import { AuditOutlined, LockOutlined, TeamOutlined, UserOutlined } from '@ant-design/icons'
import { api } from '../api/client'

const { Title, Text } = Typography
type Mode = 'key' | 'feishu'

export default function Login() {
  const [mode, setMode] = useState<Mode>('key')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const [feishuEnabled, setFeishuEnabled] = useState(false)

  useEffect(() => {
    api.feishu
      .url()
      .then((r) => (r.ok ? r.json() : null))
      .then((d) => d && setFeishuEnabled(Boolean(d.enabled)))
      .catch(() => {})
  }, [])

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

  async function feishuLogin(v: { code: string }) {
    if (!v.code.trim()) return
    setBusy(true)
    setErr('')
    try {
      const res = await api.feishu.callback(v.code.trim())
      if (res.ok) {
        // 会话已写入 HttpOnly cookie，重载后由 /me 判定进入后台
        location.reload()
        return
      }
      setErr('飞书授权验证失败')
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
        {/* ============ 品牌 LOGO 占位点 ============
            说明：Logo/企业外观标识本版本不替换。
            替换时在此插入 <img src="/logo.png" width={120} /> 并放置
            ptv-frontend/public/logo.png；当前保留文字标题。 */}
        <Title level={3} style={{ marginTop: 0, marginBottom: 0, color: '#ff3b30' }}>
          PACC 管控后台
        </Title>
        <Text type="secondary">PTV 管控平台 · 管理员登录</Text>

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
          <>
            <Text type="secondary" style={{ display: 'block', marginTop: 16 }}>
              {feishuEnabled
                ? '使用公司飞书账号授权登录（需对接飞书开放平台应用）'
                : '当前未配置飞书企业应用，处于演示模式。请输入演示授权码联调：'}
            </Text>
            <Form layout="vertical" onFinish={feishuLogin} style={{ marginTop: 12 }} requiredMark={false}>
              <Form.Item name="code" label="飞书 OAuth 授权码" rules={[{ required: true, message: '请输入授权码' }]}>
                <Input.Password prefix={<UserOutlined />} placeholder="授权码" size="large" />
              </Form.Item>
              <Form.Item style={{ marginBottom: 4 }}>
                <Button type="primary" htmlType="submit" block size="large" loading={busy}>
                  飞书授权登录
                </Button>
              </Form.Item>
              {!feishuEnabled && (
                <Text type="secondary" style={{ fontSize: 12 }}>
                  演示授权码：<code style={{ color: '#e6edf3' }}>FEISHU_TEST_CODE_2026</code>
                </Text>
              )}
            </Form>
          </>
        )}

        <div style={{ marginTop: 18, textAlign: 'center' }}>
          <Link to="/portal">前往玩家自助门户 ›</Link>
        </div>
      </Card>
    </div>
  )
}