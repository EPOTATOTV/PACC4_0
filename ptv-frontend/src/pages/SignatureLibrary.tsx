import { useEffect, useState } from 'react'
import { Alert, Button, Card, Input, Segmented, Space, Table, Typography, message } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { Signature } from '../types'
import { StatusPill } from '../components/StatusPill'

const { Title, Text } = Typography

export default function SignatureLibrary() {
  const [edition, setEdition] = useState<'BEDROCK' | 'JAVA'>('BEDROCK')
  const [list, setList] = useState<Signature[]>([])
  const [err, setErr] = useState('')

  const [name, setName] = useState('')
  const [pattern, setPattern] = useState('')
  const [risk, setRisk] = useState('3')

  async function load(ed = edition) {
    try {
      setList(await api.signatures.list(ed))
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  useEffect(() => {
    load()
  }, [edition])

  async function add() {
    if (!name || !pattern) return setErr('名称与特征码必填')
    try {
      await api.signatures.add({ name, pattern, risk_level: risk, edition, library_version: 'v4.0.0', operator: 'admin' })
      setName(''); setPattern('')
      message.success('特征已加入草稿，需灰度发布后生效')
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function gray(percent: number) {
    try {
      await api.signatures.grayRelease(edition, percent)
      message.success(`已灰度发布至 ${percent}%`)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  async function rollback() {
    try {
      const r = await api.signatures.rollback(edition)
      message.success(`已回滚 ${r.rolled_back} 条`)
      load()
    } catch (e) { setErr((e as Error).message) }
  }

  const columns: TableColumnsType<Signature> = [
    { title: '名称', dataIndex: 'name' },
    { title: '特征码', dataIndex: 'pattern', render: (v: string) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</span> },
    { title: '风险', dataIndex: 'riskLevel', width: 80, render: (v: number) => <span>{v}/5</span> },
    { title: '版本', dataIndex: 'libraryVersion', width: 100 },
    { title: '灰度', dataIndex: 'state', width: 90, render: (_, s) => (s.state === 'GRAY' ? `${s.grayPercent ?? 0}%` : '-') },
    { title: '状态', dataIndex: 'state', width: 110, render: (v: Signature['state']) => <StatusPill value={v} /> },
  ]

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>特征库管理</Title>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable />}

      <Space style={{ marginBottom: 16 }} wrap>
        <Segmented
          value={edition}
          onChange={(v) => setEdition(v as 'BEDROCK' | 'JAVA')}
          options={['BEDROCK', 'JAVA']}
        />
        <Button onClick={() => gray(10)}>灰度 10%</Button>
        <Button onClick={() => gray(50)}>灰度 50%</Button>
        <Button onClick={() => gray(100)}>全量发布</Button>
        <Button danger onClick={rollback}>回滚</Button>
      </Space>

      <Card title="新增特征（草稿）" style={{ marginBottom: 16 }} styles={{ body: { padding: 20 } }}>
        <Space.Compact style={{ width: '100%' }}>
          <Input placeholder="特征名称" value={name} onChange={(e) => setName(e.target.value)} />
          <Input placeholder="特征码（如 E8 ?? ?? ?? ?? 74 2B）" value={pattern} onChange={(e) => setPattern(e.target.value)} />
          <Input placeholder="风险 1-5" value={risk} onChange={(e) => setRisk(e.target.value)} style={{ width: 100 }} />
          <Button type="primary" style={{ background: '#3fb950' }} onClick={add}>新增</Button>
        </Space.Compact>
      </Card>

      <Card styles={{ body: { padding: 0 } }}>
        <Table<Signature>
          rowKey="id"
          columns={columns}
          dataSource={list}
          pagination={{ pageSize: 15, hideOnSinglePage: true }}
          scroll={{ x: 640 }}
          locale={{ emptyText: '暂无已发布特征（草稿需通过灰度发布）' }}
        />
      </Card>

      <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
        说明：新增特征先入草稿（DRAFT），需经灰度发布逐步放量，确认无异常后再全量发布；支持一键回滚。
      </Text>
    </div>
  )
}