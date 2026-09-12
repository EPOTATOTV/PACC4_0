import { useCallback, useEffect, useState } from 'react'
import { Alert, Button, Card, Input, Modal, Segmented, Space, Table, Typography, message } from 'antd'
import type { TableColumnsType } from 'antd'
import { api } from '../api/client'
import type { Signature } from '../types'
import { StatusPill } from '../components/StatusPill'

const { Title, Text } = Typography

// v4.7 特征库热更新：增量 diff / 自动回滚。直接 fetch 相对路径，凭据由 HttpOnly cookie 同源携带。
async function adminFetch(path: string, init?: RequestInit) {
  const res = await fetch(`/api/admin${path}`, { ...init, credentials: 'same-origin' })
  if (!res.ok) throw new Error((await res.text().catch(() => '')) || `请求失败 (${res.status})`)
  return res.json()
}

interface DiffResult {
  count: number
  digest: string
  signature?: string
  library_version?: string
  changes?: Signature[]
}

export default function SignatureLibrary() {
  const [edition, setEdition] = useState<'BEDROCK' | 'JAVA'>('BEDROCK')
  const [state, setState] = useState<string>('ALL')
  const [list, setList] = useState<Signature[]>([])
  const [err, setErr] = useState('')

  const [name, setName] = useState('')
  const [pattern, setPattern] = useState('')
  const [risk, setRisk] = useState('3')

  const load = useCallback(async (ed: 'BEDROCK' | 'JAVA', st: string) => {
    try {
      setList(await api.signatures.list(ed, st))
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }, [])

  useEffect(() => {
    load(edition, state)
  }, [edition, state, load])

  async function add() {
    if (!name || !pattern) return setErr('名称与特征码必填')
    try {
      await api.signatures.add({ name, pattern, risk_level: risk, edition, library_version: 'v4.2.0', operator: 'admin' })
      setName(''); setPattern('')
      message.success('特征已加入草稿，需灰度发布后生效')
      load(edition, state)
    } catch (e) { setErr((e as Error).message) }
  }

  async function gray(percent: number) {
    try {
      await api.signatures.grayRelease(edition, percent)
      message.success(`已灰度发布至 ${percent}%`)
      load(edition, state)
    } catch (e) { setErr((e as Error).message) }
  }

  async function rollback() {
    try {
      const r = await api.signatures.rollback(edition)
      message.success(`已回滚 ${r.rolled_back} 条`)
      load(edition, state)
    } catch (e) { setErr((e as Error).message) }
  }

  // ---- v4.7 热更新：查看增量 / 自动回滚 ----
  const [diffOpen, setDiffOpen] = useState(false)
  const [diffLoading, setDiffLoading] = useState(false)
  const [diff, setDiff] = useState<DiffResult | null>(null)
  const [rolling, setRolling] = useState(false)

  async function openDiff() {
    setDiffLoading(true)
    try {
      const d = await adminFetch(`/signatures/diff?edition=${edition}&afterVersion=0&signed=true`) as DiffResult
      setDiff(d)
      setDiffOpen(true)
    } catch (e) { setErr((e as Error).message) }
    finally { setDiffLoading(false) }
  }

  async function autoRollback() {
    if (rolling) return
    setRolling(true)
    try {
      // 演示：硬编码误报率 0.6%（>0.5% 阈值），触发自动回滚
      const r = await adminFetch('/signatures/auto-rollback', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ edition, false_positive_rate: 0.6, operator: 'admin' }),
      }) as { rolled_back: number }
      message.success(r.rolled_back > 0 ? `误报超阈值，已自动回滚 ${r.rolled_back} 条` : '误报率未超阈值，无需回滚')
      load(edition, state)
    } catch (e) { setErr((e as Error).message) }
    finally { setRolling(false) }
  }

  const columns: TableColumnsType<Signature> = [
    { title: '名称', dataIndex: 'name' },
    { title: '特征码', dataIndex: 'pattern', render: (v: string) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{v}</span> },
    { title: '风险', dataIndex: 'riskLevel', width: 80, render: (v: number) => <span>{v}/5</span> },
    { title: '版本', dataIndex: 'version', width: 80, render: (v: number) => <span>v{v}</span> },
    { title: '库版本', dataIndex: 'libraryVersion', width: 100 },
    { title: '灰度', dataIndex: 'state', width: 90, render: (_, s) => (s.state === 'GRAY' ? `${s.grayPercent ?? 0}%` : '-') },
    { title: '状态', dataIndex: 'state', width: 110, render: (v: Signature['state']) => <StatusPill value={v} /> },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 14, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>特征库管理</Title>
        <Text type="secondary" style={{ fontSize: 12 }}>
          特征码灰度发布与自动回滚（BEDROCK / JAVA 双版本，草稿经灰度放量后生效）
        </Text>
        <div style={{ flex: 1 }} />
        <Space wrap size={6}>
          <Button onClick={() => gray(10)}>灰度 10%</Button>
          <Button onClick={() => gray(50)}>灰度 50%</Button>
          <Button onClick={() => gray(100)}>全量发布</Button>
          <Button danger onClick={rollback}>回滚</Button>
          <Button loading={diffLoading} onClick={openDiff}>查看增量</Button>
          <Button loading={rolling} onClick={autoRollback}>自动回滚(误报&gt;0.5%)</Button>
        </Space>
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable />}
      </div>

      <Space style={{ marginBottom: 16 }} wrap>
        <Segmented
          value={edition}
          onChange={(v) => setEdition(v as 'BEDROCK' | 'JAVA')}
          options={['BEDROCK', 'JAVA']}
        />
        <Segmented
          value={state}
          onChange={(v) => setState(String(v))}
          options={[
            { label: '全部', value: 'ALL' },
            { label: '已发布', value: 'PUBLISHED' },
            { label: '灰度', value: 'GRAY' },
            { label: '草稿', value: 'DRAFT' },
          ]}
        />
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
          locale={{ emptyText: '当前筛选下暂无特征码（草稿需经灰度发布后生效）' }}
        />
      </Card>

      <Text type="secondary" style={{ display: 'block', marginTop: 8, fontSize: 12 }}>
        说明：新增特征先入草稿（DRAFT），需经灰度发布逐步放量，确认无异常后再全量发布；支持一键回滚。
      </Text>

      <Modal
        title={`增量 diff（${edition}，afterVersion=0）`}
        open={diffOpen}
        onCancel={() => setDiffOpen(false)}
        footer={<Button onClick={() => setDiffOpen(false)}>关闭</Button>}
      >
        {diff && (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Text>本次变更条目数：<Text strong>{diff.count}</Text></Text>
            <div>
              <Text type="secondary" style={{ fontSize: 12 }}>digest（SHA-256）：</Text>
              <Text code style={{ wordBreak: 'break-all', fontSize: 12 }}>{diff.digest}</Text>
            </div>
            <div>
              <Text type="secondary" style={{ fontSize: 12 }}>signature（HMAC-SHA256）：</Text>
              <Text code style={{ wordBreak: 'break-all', fontSize: 12 }}>{diff.signature ?? '未签名'}</Text>
            </div>
          </Space>
        )}
      </Modal>
    </div>
  )
}