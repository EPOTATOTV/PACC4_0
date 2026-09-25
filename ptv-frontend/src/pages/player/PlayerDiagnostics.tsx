import { useEffect, useState } from 'react'
import { Alert, Button, Card, Descriptions, Space, Spin, Typography } from 'antd'
import { DownloadOutlined } from '@ant-design/icons'
import { api } from '../../api/client'
import type { PlayerSummary } from '../../types'

const { Title, Text } = Typography

export default function PlayerDiagnostics() {
  const [pteid, setPteid] = useState('')
  const [summary, setSummary] = useState<PlayerSummary | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    api.player.me().then((m) => setPteid(m.pteid)).catch((e) => setErr(String(e.message || e)))
    api.player.summary().then(setSummary).catch(() => {})
  }, [])

  function exportJson() {
    const payload = {
      app: 'PACC 玩家端',
      version: 'v5.4.0',
      exported_at: new Date().toISOString(),
      user_agent: navigator.userAgent,
      pteid,
      summary,
      note: '桌面客户端诊断信息（CPU/内存/检测模块状态）可在本机客户端日志目录查看：%ProgramData%\\PACC\\logs\\',
    }
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `pacc-diagnostics-${pteid || 'anonymous'}.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>诊断工具</Title>
        <div style={{ flex: 1 }} />
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable />}
      </div>

      <Card title="账号健康概览" variant="borderless" style={{ marginBottom: 16 }}>
        {summary ? (
          <Descriptions column={2} size="small" labelStyle={{ width: 160 }}>
            <Descriptions.Item label="PTEID"><span style={{ fontFamily: 'monospace' }}>{pteid}</span></Descriptions.Item>
            <Descriptions.Item label="检测记录">{summary.record_count} 条</Descriptions.Item>
            <Descriptions.Item label="已撤销记录">{summary.revoked_count} 条</Descriptions.Item>
            <Descriptions.Item label="待处理申诉">{summary.pending_appeals} 条</Descriptions.Item>
            <Descriptions.Item label="未关闭工单">{summary.open_tickets} 条</Descriptions.Item>
          </Descriptions>
        ) : (
          <Spin />
        )}
      </Card>

      <Card title="采集诊断信息" variant="borderless">
        <Space direction="vertical">
          <Button type="primary" icon={<DownloadOutlined />} onClick={exportJson}>一键导出诊断 JSON</Button>
          <Text type="secondary" style={{ fontSize: 12 }}>
            本地完整诊断（CPU / 内存 / 检测模块 / 最近日志）由桌面客户端壳收集，位于
            <span style={{ fontFamily: 'monospace' }}> %ProgramData%\PACC\logs\</span>，可附诊断文件提交反馈。
          </Text>
        </Space>
      </Card>
    </div>
  )
}