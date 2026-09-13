import { useEffect, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Form,
  Input,
  List,
  Modal,
  Popconfirm,
  Row,
  Space,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  KeyOutlined,
  LockOutlined,
  LogoutOutlined,
  MobileOutlined,
  SafetyCertificateOutlined,
  ScanOutlined,
} from '@ant-design/icons'
import { api } from '../../api/client'
// import { isDesktop, desktopBridge } from '../../services/desktopBridge'
import type { LoginDevice } from '../../types'

const { Title, Text } = Typography

const RISK_META: Record<string, { label: string; color: string }> = {
  LOW: { label: '基础', color: 'gold' },
  MEDIUM: { label: '中风险', color: 'orange' },
  HIGH: { label: '高风险', color: 'red' },
}

/**
 * 账号安全中心：安全评分、登录设备管理、密码管理、两步验证(TOTP)。
 * 数据来自 /api/player/security 系列，后端未就绪时显示空态与加载态。
 */
export default function PlayerSecurity() {
  const [score, setScore] = useState<{ score: number; level: string; suggestions: string[] } | null>(null)
  const [devices, setDevices] = useState<LoginDevice[]>([])
  const [err, setErr] = useState('')
  const [pwdOpen, setPwdOpen] = useState(false)
  const [totpOpen, setTotpOpen] = useState(false)
  const [totpSecret, setTotpSecret] = useState('')
  const [totpUri, setTotpUri] = useState('')
  const [totpEnabled, setTotpEnabled] = useState(false)
  const [totpRecoveryReady, setTotpRecoveryReady] = useState(false)
  const [totpUnlockOpen, setTotpUnlockOpen] = useState(false)
  const [recoveryModal, setRecoveryModal] = useState<string[] | null>(null)
  const [pwdForm] = Form.useForm()
  const [unlockForm] = Form.useForm()

  async function load() {
    try {
      const [s, d, t] = await Promise.all([
        api.player.security.score(),
        api.player.security.devices(),
        api.player.security.totpStatus().catch(() => ({ enabled: false, recovery_ready: false })),
      ])
      setScore(s)
      setDevices(d)
      setTotpEnabled(t.enabled)
      setTotpRecoveryReady(t.recovery_ready)
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  useEffect(() => {
    load()
  }, [])

  async function logoutDevice(deviceId: string) {
    try {
      await api.player.security.logoutDevice(deviceId)
      setDevices((p) => p.map((d) => (d.deviceId === deviceId ? { ...d, online: false } : d)))
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  async function submitPassword() {
    const v = await pwdForm.validateFields()
    try {
      await api.player.security.changePassword(v)
      message.success('密码已更新')
      setPwdOpen(false)
      pwdForm.resetFields()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function setupTotp() {
    try {
      const r = await api.player.security.totpSetup()
      setTotpSecret(r.secret)
      setTotpUri(r.otpauth)
      setTotpOpen(true)
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  /** 生成恢复码：生成后仅此一次展示明文，须由用户自行保存。 */
  async function generateRecovery() {
    try {
      const r = await api.player.security.totpRecovery()
      setRecoveryModal(r.recovery_codes)
      setTotpRecoveryReady(true)
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  /** 禁用 2FA：须先输入当前 TOTP 或恢复码解锁。 */
  async function submitDisable() {
    const v = await unlockForm.validateFields()
    try {
      const r = await api.player.security.totpDisable(v.code)
      if (!r.ok) {
        setErr('操作失败，请检查验证码')
        return
      }
      setTotpEnabled(false)
      setTotpRecoveryReady(false)
      message.success('两步验证已禁用')
      setTotpUnlockOpen(false)
      unlockForm.resetFields()
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  const risk = RISK_META[score?.level ?? ''] ?? null

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>账号安全</Title>
        {err && <Alert type="error" showIcon message={err} style={{ marginLeft: 8, flex: 1 }} closable onClose={() => setErr('')} />}
      </div>

      <Row gutter={[14, 14]}>
        <Col xs={24} lg={8}>
          <Card
            size="small"
            style={{
              background: 'radial-gradient(500px 260px at 20% -20%, rgba(88,166,255,.15), transparent 70%)',
            }}
          >
            <div style={{ textAlign: 'center', padding: '12px 0' }}>
              <div style={{ fontSize: 40, fontWeight: 800 }}>{score?.score ?? '-'}</div>
              <Tag color={risk?.color ?? 'default'} style={{ marginTop: 4 }}>
                {risk?.label ?? '未评估'}
              </Tag>
              <div style={{ marginTop: 12 }}>
                <SafetyCertificateOutlined style={{ fontSize: 22, color: '#58a6ff' }} />
              </div>
            </div>
            <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>安全评分：{score?.score ?? '-'}/100</Text>
            {(score?.suggestions ?? []).length > 0 && (
              <List
                size="small"
                style={{ marginTop: 8 }}
                dataSource={score!.suggestions}
                renderItem={(s) => (
                  <List.Item style={{ fontSize: 12 }}>
                    <Text type="secondary">{s}</Text>
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>

        <Col xs={24} lg={16}>
          <Card title="登录设备管理" size="small" extra={<Button size="small" onClick={load}>刷新</Button>}>
            {devices.length === 0 ? (
              <Empty description="暂无设备记录，后端数据就绪后展示" style={{ padding: 24 }} />
            ) : (
              <List
                dataSource={devices}
                renderItem={(d) => (
                  <List.Item
                    actions={
                      d.online
                        ? [
                            <Popconfirm key="lo" title="远程下线该设备？" onConfirm={() => logoutDevice(d.deviceId)}>
                              <Button size="small" icon={<LogoutOutlined />} danger>下线</Button>
                            </Popconfirm>,
                          ]
                        : []
                    }
                  >
                    <List.Item.Meta
                      avatar={<MobileOutlined style={{ fontSize: 20 }} />}
                      title={
                        <span>
                          {d.name}
                          {d.suspicious && <Tag color="error" style={{ marginLeft: 8 }}>异常</Tag>}
                        </span>
                      }
                      description={
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {d.platform ?? '-'} · IP {d.ip ?? '-'} ·{' '}
                          {new Date(d.lastActiveAt).toLocaleString('zh-CN', { hour12: false })}
                        </Text>
                      }
                    />
                  </List.Item>
                )}
              />
            )}
          </Card>
        </Col>
      </Row>

      <Row gutter={[14, 14]} style={{ marginTop: 14 }}>
        <Col xs={24} lg={8}>
          <Card size="small" title="密码安全">
            <Descriptions column={1} size="small">
              <Descriptions.Item label="登录密码">● ● ● ● ● ● ● ●</Descriptions.Item>
              <Descriptions.Item label="上次修改">—</Descriptions.Item>
            </Descriptions>
            <Button type="primary" block icon={<LockOutlined />} style={{ marginTop: 12 }} onClick={() => setPwdOpen(true)}>
              修改密码
            </Button>
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card size="small" title="两步验证 (2FA)">
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
              <ScanOutlined style={{ fontSize: 20 }} />
              <Text>动态口令（TOTP）</Text>
              <Tag color={totpEnabled ? 'success' : 'default'} style={{ marginLeft: 'auto' }}>
                {totpEnabled ? '已启用' : '未启用'}
              </Tag>
            </div>
            {totpEnabled ? (
              <Space direction="vertical" style={{ width: '100%' }}>
                <Button block icon={<KeyOutlined />} onClick={generateRecovery}>
                  {totpRecoveryReady ? '重新生成恢复码' : '生成恢复码'}
                </Button>
                <Button block danger icon={<LockOutlined />} onClick={() => setTotpUnlockOpen(true)}>
                  禁用两步验证
                </Button>
              </Space>
            ) : (
              <Button block icon={<KeyOutlined />} onClick={setupTotp}>
                绑定验证器
              </Button>
            )}
          </Card>
        </Col>
        <Col xs={24} lg={8}>
          <Card size="small" title="安全建议">
            <List size="small">
              <List.Item style={{ fontSize: 12 }}><Text type="secondary">开启两步验证提高安全性</Text></List.Item>
              <List.Item style={{ fontSize: 12 }}><Text type="secondary">定期检查登录设备</Text></List.Item>
              <List.Item style={{ fontSize: 12 }}><Text type="secondary">采用高强度独立密码</Text></List.Item>
            </List>
          </Card>
        </Col>
      </Row>

      <Modal title="修改密码" open={pwdOpen} onCancel={() => setPwdOpen(false)} onOk={submitPassword} okText="保存" cancelText="取消">
        <Form form={pwdForm} layout="vertical" style={{ marginTop: 12 }}>
          <Form.Item name="current_password" label="当前密码" rules={[{ required: true, message: '请输入当前密码' }]}>
            <Input.Password autoComplete="current-password" />
          </Form.Item>
          <Form.Item name="new_password" label="新密码" rules={[{ required: true, min: 6, message: '至少 6 位' }]}>
            <Input.Password autoComplete="new-password" />
          </Form.Item>
          <Form.Item
            name="confirm_password"
            label="确认新密码"
            dependencies={['new_password']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('new_password') === value) return Promise.resolve()
                  return Promise.reject(new Error('两次输入不一致'))
                },
              }),
            ]}
          >
            <Input.Password autoComplete="new-password" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title="绑定两步验证" open={totpOpen} onCancel={() => setTotpOpen(false)} footer={null}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 8 }}>
          <Text>使用验证器 App 扫描此二维码，或手动输入密钥：</Text>
          {totpUri && (
            <div style={{ textAlign: 'center' }}>
              <img src={totpUri} alt="TOTP QR" style={{ width: 160, height: 160, maxWidth: '100%' }} />
            </div>
          )}
          {totpSecret && <Text copyable style={{ fontFamily: 'monospace', textAlign: 'center' }}>{totpSecret}</Text>}
          <Text type="secondary">二维码由 otpauth URI / 密钥生成，后端就绪后可扫码绑定。</Text>
        </div>
      </Modal>

      <Modal title="禁用两步验证" open={totpUnlockOpen} onCancel={() => { setTotpUnlockOpen(false); unlockForm.resetFields() }} onOk={submitDisable} okText="禁用" okButtonProps={{ danger: true }} cancelText="取消">
        <Alert type="warning" showIcon message="请输入当前动态验证码（或一次性恢复码）以解锁禁用操作。" style={{ marginBottom: 16 }} />
        <Form form={unlockForm} layout="vertical">
          <Form.Item name="code" label="验证码" rules={[{ required: true, message: '请输入验证码' }]}>
            <Input maxLength={6} placeholder="6 位动态码 / 恢复码" autoComplete="one-time-code" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="一次性恢复码"
        open={!!recoveryModal}
        onCancel={() => setRecoveryModal(null)}
        footer={<Button type="primary" onClick={() => setRecoveryModal(null)}>我已保存</Button>}
      >
        <Alert type="warning" showIcon message="恢复码仅展示这一次，请立即妥善保存。每个恢复码仅能使用一次。" style={{ marginBottom: 16 }} />
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
          {(recoveryModal ?? []).map((c, i) => (
            <Text key={i} copyable style={{ fontFamily: 'monospace', textAlign: 'center' }}>{c}</Text>
          ))}
        </div>
      </Modal>
    </div>
  )
}