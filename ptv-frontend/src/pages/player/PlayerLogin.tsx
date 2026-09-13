import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Alert, Button, Checkbox, Form, Input, Typography } from 'antd'
import { LockOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import PlayerAuthShell from './PlayerAuthShell'

const { Text } = Typography

type Values = { identity: string; password: string }

export default function PlayerLogin() {
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState('')
  // 2FA 第二步状态：pending 令牌由第一步成功且启用 2FA 时下发
  const [pending, setPending] = useState('')
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
      const data = (await res.json().catch(() => ({}))) as { twofa_required?: boolean; pending?: string }
      if (data.twofa_required) {
        setPending(data.pending || '')
        return // 切换为第二步视图
      }
      // 会话由后端写入 HttpOnly cookie，JS 不再持有令牌；跳转后由 /api/player/me 探测登录态
      navigate('/portal', { replace: true })
    } catch (e) {
      setErr((e as Error)?.name === 'AbortError' ? '登录请求超时，请检查网络后重试' : '无法连接 PTV 后端，请稍后再试')
    } finally {
      setBusy(false)
    }
  }

  async function onTwofa(code: string) {
    if (busy) return
    setBusy(true)
    setErr('')
    try {
      const res = await api.auth.login2fa(pending, code, true)
      if (!res.ok) {
        const data = await res.json().catch(() => ({}))
        setErr((data as { error?: string }).error || `验证失败 (${res.status})`)
        return
      }
      navigate('/portal', { replace: true })
    } catch (e) {
      setErr((e as Error)?.name === 'AbortError' ? '验证请求超时，请稍后重试' : '无法连接 PTV 后端，请稍后再试')
    } finally {
      setBusy(false)
    }
  }

  // 第二步视图
  if (pending) {
    return (
      <PlayerAuthShell
        title="两步验证"
        subtitle="请输入身份验证器中的 6 位动态验证码"
        footer={
          <span
            style={{ fontSize: 12, cursor: 'pointer', color: 'var(--pacc-primary-color, #1677ff)' }}
            onClick={() => setPending('')}
          >
            ‹ 返回重新登录
          </span>
        }
      >
        {err && <Alert type="error" showIcon message={err} style={{ marginTop: 16 }} closable />}

        <Form
          layout="vertical"
          onFinish={(v: { code: string }) => onTwofa(v.code)}
          style={{ marginTop: 20 }}
          requiredMark={false}
        >
          <Form.Item
            name="code"
            label="动态验证码"
            rules={[{ required: true, message: '请输入动态验证码' }]}
          >
            <Input
              prefix={<SafetyCertificateOutlined />}
              placeholder="6 位验证码"
              size="large"
              maxLength={6}
              autoComplete="one-time-code"
            />
          </Form.Item>

          <Form.Item style={{ marginBottom: 8 }}>
            <Button type="primary" htmlType="submit" block size="large" loading={busy}>
              验证并登录
            </Button>
          </Form.Item>
          <Text type="secondary" style={{ fontSize: 12 }}>
            无法使用验证器？请使用登录时保留的一次性恢复码
          </Text>
        </Form>
      </PlayerAuthShell>
    )
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