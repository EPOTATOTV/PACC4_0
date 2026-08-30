import { useEffect, useState } from 'react'
import { Alert, Card, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { AdminLoginLog } from '../types'

const { Title, Text } = Typography

export default function AdminLoginLogs() {
  const [logs, setLogs] = useState<AdminLoginLog[]>([])
  const [err, setErr] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.adminLoginLogs()
      .then((d) => setLogs(d.logs))
      .catch((e) => setErr(String(e.message || e)))
      .finally(() => setLoading(false))
  }, [])

  const columns: TableColumnsType<AdminLoginLog> = [
    {
      title: '时间',
      dataIndex: 'created_at',
      width: 180,
      render: (v: string) => (
        <Text type="secondary" style={{ whiteSpace: 'nowrap' }}>{new Date(v).toLocaleString()}</Text>
      ),
    },
    {
      title: '身份（脱敏）',
      dataIndex: 'identity',
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    {
      title: '方式',
      dataIndex: 'method',
      width: 100,
      render: (m: string) => (m === 'feishu' ? <Tag color="geekblue">飞书</Tag> : <Tag color="gold">授权密钥</Tag>),
    },
    {
      title: '角色',
      dataIndex: 'role',
      width: 120,
      render: (r?: string | null) =>
        r === 'super-admin' ? <Tag color="volcano">超级管理员</Tag>
          : r === 'operator' ? <Tag color="cyan">运维</Tag> : <Text type="secondary">—</Text>,
    },
    {
      title: '结果',
      dataIndex: 'result',
      width: 90,
      render: (r: string) => (r === 'success' ? <Tag color="success">成功</Tag> : <Tag color="error">失败</Tag>),
    },
    {
      title: '来源 IP',
      dataIndex: 'ip',
      width: 150,
      render: (ip: string) => <span style={{ fontFamily: 'monospace', color: '#8b949e' }}>{ip}</span>,
    },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>管理员登录日志</Title>
      <Text type="secondary">
        审计记录管理后台登录行为（只存来源、方式与结果，不落任何密钥/密码明文），仅超级管理员授权可访问。
      </Text>

      {err && <Alert type="error" showIcon message={err} style={{ marginTop: 16 }} closable />}

      <Card style={{ marginTop: 20 }} styles={{ body: { padding: 0 } }}>
        <Table<AdminLoginLog>
          rowKey="id"
          columns={columns}
          dataSource={logs}
          loading={loading}
          pagination={{ pageSize: 15, hideOnSinglePage: true }}
          scroll={{ x: 720 }}
          locale={{ emptyText: '暂无登录记录' }}
        />
      </Card>
    </div>
  )
}