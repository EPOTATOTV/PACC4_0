import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Alert, Button, Form, Input } from 'antd'
import { LockOutlined, MailOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import PlayerAuthShell from './PlayerAuthShell'

const emailRe = /^[\w.+-]+@[\w-]+(\.[\w-]+)+$/
const passOk = (v: string) =>
  v != null && v.length >= 8 && v.length <= 32 && /[A-Z]/.test(v) && /[a-z]/.test(v) &&
  /[0-9]/.test(v) && /[^A-Za-z0-9]/.test(v)

type ReqValues = { email: string }
type ResetValues = { password: string; confirm: string }

export default function PlayerForget() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''

  const [busy, setBusy] = useState(false)
  const [sent, setSent] = useState(false)
  const [devLink, setDevLink] = useState('')
  const [reqMsg, setReqMsg] = useState('')
  const [res, setRes] = useState<{ ok: boolean; msg: string } | null>(null)

  async function requestReset(v: ReqValues) {
    if (!emailRe.test(v.email)) return setReqMsg('请输入有效的邮箱地址')
    setBusy(true)
    setReqMsg('')
    try {
      const r = await api.auth.forget(v.email)
      const data = (await r.json()) as { message?: string; dev_link?: string }
      setSent(true)
      setReqMsg(data.message || '处理完成')
      if (data.dev_link) setDevLink(data.dev_link)
    } catch {
      setReqMsg('无法连接 PTV 后端，请稍后再试')
    } finally {
      setBusy(false)
    }
  }

  async function doReset(v: ResetValues) {
    setBusy(true)
    try {
      const r = await api.auth.reset(token, v.password)
      const data = (await r.json()) as { message?: string; error?: string }
      setRes({ ok: r.ok, msg: (r.ok ? data.message : data.error) || (r.ok ? '密码已重置' : '重置失败') })
    } catch {
      setRes({ ok: false, msg: '无法连接 PTV 后端，请稍后再试' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <PlayerAuthShell
      title={token ? '设置新密码' : '找回密码'}
      subtitle={token ? '为账号设置新的登录密码' : '输入注册邮箱，如该邮箱已注册，重置链接将发送至邮箱'}
      footer={<Link to="/portal/login">‹ 返回登录</Link>}
    >
      {!token ? (
        <>
          {reqMsg && (
            <Alert type={sent ? 'success' : 'error'} showIcon message={reqMsg} style={{ marginTop: 16 }} />
          )}
            {!sent && (
              <Form layout="vertical" onFinish={requestReset} requiredMark={false} style={{ marginTop: 20 }}>
                <Form.Item
                  name="email" label="注册邮箱"
                  rules={[
                    { required: true, message: '请输入邮箱' },
                    { pattern: emailRe, message: '请输入有效的邮箱地址' },
                  ]}
                >
                  <Input prefix={<MailOutlined />} placeholder="you@example.com" size="large" autoComplete="email" />
                </Form.Item>
                <Form.Item style={{ marginBottom: 8 }}>
                  <Button type="primary" htmlType="submit" block size="large" loading={busy}>
                    发送重置链接
                  </Button>
                </Form.Item>
              </Form>
            )}
            {devLink && (
              <a href={devLink} style={{ display: 'block', marginTop: 14, fontSize: 13 }}>
                演示环境：点击直达重置页（正式环境邮件发送）
              </a>
            )}
          </>
        ) : (
          <>
            {res && <Alert type={res.ok ? 'success' : 'error'} showIcon message={res.msg} style={{ marginTop: 16 }} />}
            {!res?.ok && (
              <Form layout="vertical" onFinish={doReset} requiredMark={false} style={{ marginTop: 20 }}>
                <Form.Item
                  name="password" label="新密码"
                  rules={[
                    { required: true, message: '请输入新密码' },
                    { validator: (_, v) => (passOk(v) ? Promise.resolve() : Promise.reject(new Error('需 8-32 位，含大小写字母、数字与特殊符号'))) },
                  ]}
                >
                  <Input.Password prefix={<LockOutlined />} placeholder="含大小写字母、数字与特殊符号" size="large" autoComplete="new-password" />
                </Form.Item>
                <Form.Item
                  name="confirm" label="确认新密码"
                  dependencies={['password']}
                  rules={[
                    { required: true, message: '请再次输入新密码' },
                    ({ getFieldValue }) => ({
                      validator: (_, v) =>
                        !v || getFieldValue('password') === v ? Promise.resolve() : Promise.reject(new Error('两次输入的密码不一致')),
                    }),
                  ]}
                >
                  <Input.Password prefix={<LockOutlined />} placeholder="再次输入" size="large" autoComplete="new-password" />
                </Form.Item>
                <Form.Item style={{ marginBottom: 8 }}>
                  <Button type="primary" htmlType="submit" block size="large" loading={busy}>
                    重置密码
                  </Button>
                </Form.Item>
              </Form>
            )}
          </>
        )}
    </PlayerAuthShell>
  )
}