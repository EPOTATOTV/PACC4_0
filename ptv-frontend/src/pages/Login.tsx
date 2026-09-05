import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Alert, Button, Card, Form, Input, Segmented, Typography } from 'antd'
import { AuditOutlined, LockOutlined, TeamOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import Brand from '../components/Brand'
import LanguageSwitcher from '../components/LanguageSwitcher'
import { useI18n } from '../i18n'

const { Text } = Typography
type Mode = 'key' | 'feishu'

export default function Login() {
  const { t } = useI18n()
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
      setErr(res.status === 429 ? t('login.errRateLimited') : t('login.errKey'))
    } catch {
      setErr(t('login.errNetwork'))
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
      setErr(t('login.errFeishu'))
    } catch {
      setErr(t('login.errNetwork'))
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
        <Brand size="md" subtitle={t('login.title')} />

        <Segmented
          block
          value={mode}
          onChange={(m) => { setMode(m as Mode); setErr('') }}
          options={[
            { label: t('login.superAdminKey'), value: 'key', icon: <AuditOutlined /> },
            { label: t('login.feishu'), value: 'feishu', icon: <TeamOutlined /> },
          ]}
          style={{ margin: '20px 0 4px' }}
        />

        {err && <Alert type="error" showIcon message={err} style={{ marginTop: 16 }} closable />}

        {mode === 'key' ? (
          <Form layout="vertical" onFinish={submit} style={{ marginTop: 16 }} requiredMark={false}>
            <Form.Item name="key" label={t('login.keyLabel')} rules={[{ required: true, message: t('login.keyRequired') }]}>
              <Input.Password prefix={<LockOutlined />} placeholder={t('login.keyPlaceholder')} size="large" />
            </Form.Item>
            <Form.Item style={{ marginBottom: 4 }}>
              <Button type="primary" htmlType="submit" block size="large" loading={busy} danger>
                {t('login.submit')}
              </Button>
            </Form.Item>
            <Text type="secondary" style={{ fontSize: 12 }}>{t('login.keyHint')}</Text>
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
            {t('login.feishuLogin')}
          </Button>
        )}

        <div style={{ marginTop: 18, textAlign: 'center' }}>
          <Link to="/portal">{t('login.toPortal')}</Link>
        </div>

        <div
          style={{
            marginTop: 16,
            textAlign: 'center',
            borderTop: '1px solid #30363d',
            paddingTop: 14,
          }}
        >
          <LanguageSwitcher />
        </div>
      </Card>
    </div>
  )
}