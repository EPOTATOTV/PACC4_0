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
    if (busy) return // 防止 Enter 重复提交触发登录限流
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
    } catch (e) {
      setErr((e as Error)?.name === 'AbortError' ? t('login.errTimeout') : t('login.errNetwork'))
    } finally {
      setBusy(false)
    }
  }

  async function feishuLogin() {
    if (busy) return
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
    } catch (e) {
      setErr((e as Error)?.name === 'AbortError' ? t('login.errTimeout') : t('login.errNetwork'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      style={{
        position: 'relative',
        minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px 12px',
      }}
    >
      {/* 环境氛围：顶部微光 + 细网格 */}
      <div
        aria-hidden
        style={{
          position: 'fixed', inset: 0, zIndex: 0, overflow: 'hidden',
          background:
            'radial-gradient(760px 430px at 50% -120px, rgba(255,77,61,.16), transparent 62%), var(--bg)',
        }}
      />
      <div
        aria-hidden
        style={{
          position: 'fixed', inset: 0, zIndex: 0, opacity: .5,
          backgroundImage:
            'linear-gradient(rgba(255,255,255,.035) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.035) 1px, transparent 1px)',
          backgroundSize: '52px 52px',
          maskImage: 'radial-gradient(circle at 50% 0%, #000 0%, transparent 70%)',
          WebkitMaskImage: 'radial-gradient(circle at 50% 0%, #000 0%, transparent 70%)',
        }}
      />
      <Card
        style={{
          position: 'relative', zIndex: 1, width: '100%', maxWidth: 400,
          background: 'rgba(17,20,27,.72)',
          backdropFilter: 'blur(16px) saturate(140%)',
          border: '1px solid var(--border-strong)',
          boxShadow: '0 24px 80px rgba(0,0,0,.5)',
        }}
        styles={{ body: { padding: 30 } }}
      >
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
            borderTop: '1px solid var(--border)',
            paddingTop: 14,
          }}
        >
          <LanguageSwitcher />
        </div>
      </Card>
    </div>
  )
}