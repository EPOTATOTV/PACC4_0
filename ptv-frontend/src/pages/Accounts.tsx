import { useState } from 'react'
import { Alert, Button, Card, Input, Space, Table, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { Account } from '../types'
import { StatusPill } from '../components/StatusPill'

const { Title, Text } = Typography

export default function Accounts() {
  const [keyword, setKeyword] = useState('')
  const [list, setList] = useState<Account[]>([])
  const [err, setErr] = useState('')

  async function load() {
    try {
      setList(await api.accounts.list(keyword))
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  const columns: TableColumnsType<Account> = [
    { title: 'PTEID', dataIndex: 'pteid', render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: '邮箱', dataIndex: 'email' },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: Account['status']) => <StatusPill value={v} /> },
    { title: '信誉分', dataIndex: 'reputation', width: 100, render: (v: number) => <span>{v}/100</span> },
    { title: '红屏次数', dataIndex: 'totalRedscreen', width: 100 },
    { title: '注册时间', dataIndex: 'registeredAt', width: 180, render: (v?: string) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-') },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>反作弊账号</Title>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      <Space style={{ marginBottom: 16 }} wrap>
        <Input
          placeholder="按 PTEID 或邮箱检索"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onPressEnter={load}
          style={{ width: 340 }}
          allowClear
        />
        <Button type="primary" onClick={load}>查询</Button>
      </Space>

      <Card styles={{ body: { padding: 0 } }}>
        <Table<Account>
          rowKey="pteid"
          columns={columns}
          dataSource={list}
          pagination={{ pageSize: 15, hideOnSinglePage: true }}
          scroll={{ x: 640 }}
          locale={{ emptyText: '无匹配账号' }}
        />
      </Card>

      <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
        说明：PTEID 为独立反作弊账号，与游戏账号解耦。账号状态由红屏判定与查端结论驱动。
      </Text>
    </div>
  )
}