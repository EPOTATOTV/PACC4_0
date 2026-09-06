import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Alert, Button, Checkbox, Form, Input, Typography } from 'antd'
import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import PlayerAuthShell from './PlayerAuthShell'

const { Text } = Typography

type Values = { identity: string; password: string }

export default function PlayerLogin() {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  const navigate = useNavigate()

  async function onFinish(v: Values) {
    if (busy) return // 防止 Enter 重复提交触发后端限流
    setBusy(true)
    setErr('')
    try {
      const res = await api.auth.login(v.identity, v.password, true)
      if (!res.ok) {
        const data = await res.json().catch(() => ({}))
        setErr((data as { error?: string }).error || `登录失败 (${res.status})`)
        return
      }
      // 会话由后端写入 HttpOnly cookie，JS 不再持有令牌；跳转后由 /api/player/me 探测登录态
      navigate('/portal', { replace: true })
    } catch (e) {
      setErr((e as Error)?.name === 'AbortError' ? '登录请求超时，请检查网络后重试' : '无法连接 PTV 后端，请稍后再试')
    } finally {
      setBusy(false)
    }
  }

  return (
    <PlayerAuthShell
      title="PACC 玩家自助中心"
      subtitle="手机号 / MCID / ECID / QQ / 邮箱任一均可登录"
      footer={<Link to="/" style={{ fontSize: 12 }}>‹ 返回管理后台</Link>}
    >
      {err && <Alert type="error" showIcon message={err} style={{ marginTop: 16 }} closable />}

      <Form layout="vertical" onFinish={onFinish} style={{ marginTop: 20 }} requiredMark={false}>
        <Form.Item
          name="identity" label="账号"
          rules={[{ required: true, message: '请输入账号' }]}
        >
          <Input
            prefix={<UserOutlined />}
            placeholder="手机号 / MCID / ECID / QQ / 邮箱"
            size="large"
            autoComplete="username"
          />
        </Form.Item>
        <Form.Item
          name="password" label="密码"
          rules={[{ required: true, message: '请输入密码' }]}
        >
          <Input.Password
            prefix={<LockOutlined />}
            placeholder="密码"
            size="large"
            autoComplete="current-password"
          />
        </Form.Item>

        <Form.Item style={{ marginBottom: 8 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <Form.Item name="remember" valuePropName="checked" noStyle initialValue={true}>
              <Checkbox>保持登录状态</Checkbox>
            </Form.Item>
            <Link to="/portal/forget">忘记密码</Link>
          </div>
        </Form.Item>

        <Form.Item style={{ marginBottom: 8 }}>
          <Button type="primary" htmlType="submit" block size="large" loading={busy}>
            登 录
          </Button>
        </Form.Item>
        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
          <Text type="secondary">还没有账号？</Text>
          <Link to="/portal/register">立即注册</Link>
        </div>
      </Form>
    </PlayerAuthShell>
  )
}