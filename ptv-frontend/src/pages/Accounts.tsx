import { useEffect, useState } from 'react'
import { Alert, Button, Card, Descriptions, Drawer, Input, Space, Table, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { Account } from '../types'
import { StatusPill } from '../components/StatusPill'
import { useTableRowReveal } from '../hooks/useGSAP'

const { Title, Text } = Typography

export default function Accounts() {
  const [keyword, setKeyword] = useState('')
  const [list, setList] = useState<Account[]>([])
  const [err, setErr] = useState('')
  const [detail, setDetail] = useState<Account | null>(null)
  const { ref: tableRef, reveal } = useTableRowReveal<HTMLDivElement>({ x: -18 })

  async function load() {
    try {
      setList(await api.accounts.list(keyword))
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  // 检索结果整体替换后重播行入场，等新行进 DOM 再触发
  useEffect(() => {
    const id = requestAnimationFrame(reveal)
    return () => cancelAnimationFrame(id)
  }, [list, reveal])

  const columns: TableColumnsType<Account> = [
    { title: 'PTEID', dataIndex: 'pteid', render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    { title: '邮箱', dataIndex: 'email' },
    { title: '手机号', dataIndex: 'phone', width: 130, render: (v?: string) => v ?? <Text type="secondary">—</Text> },
    { title: '状态', dataIndex: 'status', width: 100, render: (v: Account['status']) => <StatusPill value={v} /> },
    { title: '信誉分', dataIndex: 'reputation', width: 100, render: (v: number) => <span>{v}/100</span> },
    { title: '红屏次数', dataIndex: 'totalRedscreen', width: 100 },
    { title: '注册时间', dataIndex: 'registeredAt', width: 180, render: (v?: string) => (v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '-') },
    { title: '操作', width: 100, render: (_, a: Account) => <Button size="small" onClick={() => setDetail(a)}>详情</Button> },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>PTEID 账号管理</Title>

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
        <div ref={tableRef}>
          <Table<Account>
            rowKey="pteid"
            columns={columns}
            dataSource={list}
            pagination={{ pageSize: 15, hideOnSinglePage: true }}
            scroll={{ x: 720 }}
            locale={{ emptyText: '无匹配账号' }}
          />
        </div>
      </Card>

      <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
        说明：PTEID 为独立反作弊账号，与游戏账号解耦。账号状态由红屏判定与查端结论驱动。
      </Text>

      <Drawer
        title={`账号详情 · ${detail?.pteid ?? ''}`}
        open={!!detail}
        onClose={() => setDetail(null)}
        width={420}
      >
        {detail && (
          <Descriptions column={1} size="small" labelStyle={{ width: 110 }}>
            <Descriptions.Item label="PTEID"><span style={{ fontFamily: 'monospace' }}>{detail.pteid}</span></Descriptions.Item>
            <Descriptions.Item label="邮箱">{detail.email}</Descriptions.Item>
            <Descriptions.Item label="手机号">{detail.phone ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="账号状态"><StatusPill value={detail.status} /></Descriptions.Item>
            <Descriptions.Item label="信誉分">{detail.reputation}/100</Descriptions.Item>
            <Descriptions.Item label="红屏次数">{detail.totalRedscreen}</Descriptions.Item>
            <Descriptions.Item label="注册时间">{detail.registeredAt ? new Date(detail.registeredAt).toLocaleString('zh-CN', { hour12: false }) : '—'}</Descriptions.Item>
            <Descriptions.Item label="最近红屏">{detail.lastRedScreenTime ? new Date(detail.lastRedScreenTime).toLocaleString('zh-CN', { hour12: false }) : '—'}</Descriptions.Item>
          </Descriptions>
        )}
        <Text type="secondary" style={{ display: 'block', marginTop: 16, fontSize: 12 }}>
          PTEID 为对外唯一标识，不可修改。设备绑定与查端历史请在对应功能页查看。
        </Text>
      </Drawer>
    </div>
  )
}