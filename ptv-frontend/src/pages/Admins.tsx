import { useEffect, useState } from 'react'
import { Alert, Card, Tag, Typography } from 'antd'
import { api } from '../api/client'
import type { AdminIdentity, SystemAdmins } from '../types'

const { Title, Text } = Typography

export default function Admins() {
  const [data, setData] = useState<SystemAdmins | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    api.system.admins().then(setData).catch((e) => setErr(String(e.message || e)))
  }, [])

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>管理员管理</Title>
      <Text type="secondary">
        管理员由部署侧配置固化（静态授权密钥 + 飞书 SSO 白名单），本页只读展示，不在此处做增删改。
      </Text>

      {err && <Alert type="error" showIcon message={err} style={{ marginTop: 16 }} closable />}
      {data?.note && (
        <Alert type="info" showIcon message={data.note} style={{ marginTop: 16 }} closable />
      )}

      <Card style={{ marginTop: 20 }} styles={{ body: { padding: 0 } }}>
        {(data?.accounts ?? []).map((a: AdminIdentity) => (
          <div
            key={a.identity + a.method}
            style={{
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              padding: '14px 20px', borderBottom: '1px solid #f0f0f0',
            }}
          >
            <div>
              <div style={{ fontWeight: 600 }}>{a.identity}</div>
              <Text type="secondary" style={{ fontSize: 12 }}>
                登录方式：{a.method === 'key' ? '静态授权密钥' : a.method === 'feishu' ? '飞书 SSO' : '无'}
              </Text>
            </div>
            <div>
              {a.enabled ? <Tag color="success">已启用</Tag> : <Tag>未启用</Tag>}
              {a.role === 'super-admin' ? (
                <Tag color="volcano">超级管理员</Tag>
              ) : a.role === 'operator' ? (
                <Tag color="cyan">运维</Tag>
              ) : (
                <Tag>{a.role}</Tag>
              )}
            </div>
          </div>
        ))}
        {data && data.accounts.length === 0 && (
          <div style={{ padding: 24, textAlign: 'center', color: '#8b949e' }}>暂无配置</div>
        )}
      </Card>

      {data && (
        <Card style={{ marginTop: 16 }}>
          <Text type="secondary">累计登录事件 <b>{data.recent_login_events}</b> 条（含成功与失败），详见「审计日志」。</Text>
        </Card>
      )}
    </div>
  )
}