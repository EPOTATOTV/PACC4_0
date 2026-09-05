import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Alert, Button, Col, Divider, Form, Input, Row, Typography } from 'antd'
import { LockOutlined, MailOutlined, NumberOutlined, UserOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import PlayerAuthShell from './PlayerAuthShell'

const { Text } = Typography

// 与后端校验规则保持一致
const pattern = {
  phone: /^1[3-9]\d{9}$/,
  mcid: /^[A-Za-z0-9_]{3,16}$/,
  qq: /^\d{5,12}$/,
  email: /^[\w.+-]+@[\w-]+(\.[\w-]+)+$/,
  uuid: /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/,
}
// 密码强度：8-32 位，含大小写字母、数字与特殊符号
const strongPassword = (v: string) =>
  v != null && v.length >= 8 && v.length <= 32 && /[A-Z]/.test(v) && /[a-z]/.test(v) &&
  /[0-9]/.test(v) && /[^A-Za-z0-9]/.test(v)

type Values = Record<string, string> & { password?: string }

export default function PlayerRegister() {
  const [form] = Form.useForm<Values>()
  const [busy, setBusy] = useState(false)
  const [devLink, setDevLink] = useState('')
  const navigate = useNavigate()

  async function onFinish(v: Values) {
    setBusy(true)
    try {
      const res = await api.auth.register({
        email: v.email!,
        phone: v.phone!,
        mcid: v.mcid!,
        ecid: v.ecid!,
        qq: v.qq!,
        netease_uuid: (v.neteaseUuid ?? '').trim(),
        password: v.password!,
        device_fingerprint: 'web-reg-' + crypto.randomUUID(),
        code: v.code!,
      })
      const data = (await res.json()) as { error?: string }
      if (!res.ok) {
        setDevLink(data.error || `注册失败 (${res.status})`)
        return
      }
      // 注册即登录：会话由后端写入 HttpOnly cookie，跳转后由 /api/player/me 探测登录态
      navigate('/portal')
    } catch {
      setDevLink('无法连接 PTV 后端，请稍后再试')
    } finally {
      setBusy(false)
    }
  }

  async function sendCode() {
    const email = form.getFieldValue('email')
    if (!email || !pattern.email.test(email)) {
      setDevLink('请先填写正确的邮箱')
      return
    }
    try {
      const res = await api.auth.sendCode({ target: email, scene: 'register' })
      const data = (await res.json()) as { error?: string; message?: string }
      setDevLink(res.ok ? data.message || '验证码已发送' : data.error || `发送失败 (${res.status})`)
    } catch {
      setDevLink('无法连接 PTV 后端，请稍后再试')
    }
  }

  return (
    <PlayerAuthShell
      width={520}
      title="注册 PACC 玩家账号"
      subtitle="手机号 / MCID / ECID / QQ / 邮箱用于多凭证登录与客服身份核验"
    >
      {devLink && (
        <Alert
          type={devLink.startsWith('请先填写正确的邮箱') || devLink.startsWith('无法') ? 'error' : 'info'}
          showIcon message={devLink} style={{ marginTop: 16 }} closable
        />
      )}

        <Form
          form={form}
          layout="vertical"
          onFinish={onFinish}
          style={{ marginTop: 20 }}
          requiredMark={false}
        >
          <Row gutter={14}>
            <Col xs={24} sm={12}>
              <Form.Item
                name="phone" label="手机号"
                rules={[
                  { required: true, message: '请输入手机号' },
                  { pattern: pattern.phone, message: '请输入有效的 11 位手机号' },
                ]}
              >
                <Input prefix={<NumberOutlined />} placeholder="11 位手机号" autoComplete="tel" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item
                name="mcid" label="MCID（Minecraft ID）"
                tooltip="需与官方 ID 一致，可用于登录"
                rules={[
                  { required: true, message: '请输入 MCID' },
                  { pattern: pattern.mcid, message: '需为 3-16 位字母/数字/下划线' },
                ]}
              >
                <Input prefix={<UserOutlined />} placeholder="如 PlayerOne" autoComplete="username" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item
                name="ecid" label="ECID（EaseCation 平台 ID）"
                rules={[{ required: true, whitespace: true, message: '请输入 ECID' }]}
              >
                <Input placeholder="EaseCation 平台 ID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item
                name="qq" label="QQ 号"
                rules={[
                  { required: true, message: '请输入 QQ 号' },
                  { pattern: pattern.qq, message: 'QQ 号需为 5-12 位数字' },
                ]}
              >
                <Input prefix={<NumberOutlined />} placeholder="5-12 位数字" inputMode="numeric" />
              </Form.Item>
            </Col>
            <Col xs={24}>
              <Form.Item
                name="email" label="邮箱"
                tooltip="用于密码找回与客服联系，需唯一"
                rules={[
                  { required: true, message: '请输入邮箱' },
                  { pattern: pattern.email, message: '请输入有效的邮箱地址' },
                ]}
              >
                <Input prefix={<MailOutlined />} placeholder="you@example.com" autoComplete="email" />
              </Form.Item>
            </Col>
            <Col xs={24}>
              <Form.Item
                name="code" label="邮箱验证码"
                tooltip="点击发送后，验证码将发送至上方邮箱"
                dependencies={['email']}
                rules={[
                  { required: true, message: '请输入邮箱验证码' },
                ]}
              >
                <Input.Search
                  placeholder="6 位验证码"
                  enterButton="发送验证码"
                  onSearch={sendCode}
                />
              </Form.Item>
            </Col>
            <Col xs={24}>
              <Form.Item
                name="neteaseUuid" label="网易 UUID"
                tooltip="格式：8-4-4-4-12，如 1a2b3c4d-5e6f-7a8b-9c0d-123456789abc"
                rules={[{ pattern: pattern.uuid, message: 'UUID 格式应为 8-4-4-4-12' }]}
              >
                <Input placeholder="可选 · 网易 UUID" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item
                name="password" label="密码"
                rules={[
                  { required: true, message: '请输入密码' },
                  { validator: (_, v) => (strongPassword(v) ? Promise.resolve() : Promise.reject(new Error('需 8-32 位，含大小写字母、数字与特殊符号'))) },
                ]}
                hasFeedback
              >
                <Input.Password prefix={<LockOutlined />} placeholder="含大小写字母、数字与特殊符号" autoComplete="new-password" />
              </Form.Item>
            </Col>
            <Col xs={24} sm={12}>
              <Form.Item
                name="confirm" label="确认密码"
                dependencies={['password']}
                hasFeedback
                rules={[
                  { required: true, message: '请再次输入密码' },
                  ({ getFieldValue }) => ({
                    validator: (_, v) =>
                      !v || getFieldValue('password') === v ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致')),
                  }),
                ]}
              >
                <Input.Password prefix={<LockOutlined />} placeholder="再次输入" autoComplete="new-password" />
              </Form.Item>
            </Col>
          </Row>

          <Divider style={{ margin: '4px 0 16px' }} />

          <Form.Item style={{ marginBottom: 8 }}>
            <Button type="primary" htmlType="submit" block size="large" loading={busy}>
              注 册
            </Button>
          </Form.Item>
          <div style={{ textAlign: 'center' }}>
            <Text type="secondary">已有账号？</Text>{' '}
            <Link to="/portal/login">直接登录</Link>
          </div>
        </Form>
    </PlayerAuthShell>
  )
}