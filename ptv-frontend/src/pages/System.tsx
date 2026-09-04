import { useEffect, useState } from 'react'
import { Alert, Card, Col, Descriptions, Row, Spin, Tag, Typography } from 'antd'
import { api } from '../api/client'
import type { SystemConfig, SystemInfo } from '../types'

const { Title, Text } = Typography

function fmtUptime(ms: number) {
  const s = Math.floor(ms / 1000)
  const d = Math.floor(s / 86400)
  const h = Math.floor((s % 86400) / 3600)
  const m = Math.floor((s % 3600) / 60)
  if (d > 0) return `${d}天 ${h}小时 ${m}分`
  if (h > 0) return `${h}小时 ${m}分`
  return `${m}分`
}

export default function System() {
  const [info, setInfo] = useState<SystemInfo | null>(null)
  const [config, setConfig] = useState<SystemConfig | null>(null)
  const [err, setErr] = useState('')

  useEffect(() => {
    api.system.info().then(setInfo).catch((e) => setErr(String(e.message || e)))
    api.system.config().then(setConfig).catch(() => {})
  }, [])

  return (
    <div>
      <Title level={3} style={{ marginTop: 0 }}>系统设置</Title>
      <Text type="secondary">服务运行状态与检测策略快照（只读）。配置调整需由运维在部署侧修改后重启。</Text>

      {err && <Alert type="error" showIcon message={err} style={{ marginTop: 16 }} closable />}

      <Row gutter={16} style={{ marginTop: 20 }} wrap>
        <Col xs={24} lg={12}>
          <Card title="运行状态" variant="borderless" style={{ marginBottom: 16 }}>
            {info ? (
              <Descriptions column={1} size="small" labelStyle={{ width: 120 }}>
                <Descriptions.Item label="应用">{info.app}</Descriptions.Item>
                <Descriptions.Item label="版本">
                  <Tag color="blue">{info.version}</Tag>
                </Descriptions.Item>
                <Descriptions.Item label="Java 版本">{info.java_version}</Descriptions.Item>
                <Descriptions.Item label="Profile">
                  {info.active_profiles.length ? (
                    info.active_profiles.map((p) => <Tag key={p}>{p}</Tag>)
                  ) : (
                    <Tag>default</Tag>
                  )}
                </Descriptions.Item>
                <Descriptions.Item label="运行时长">{fmtUptime(info.uptime_ms)}</Descriptions.Item>
                <Descriptions.Item label="数据库">
                  <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{info.database}</span>
                </Descriptions.Item>
              </Descriptions>
            ) : (
              <Spin />
            )}
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card title="检测策略" variant="borderless" style={{ marginBottom: 16 }}>
            {config ? (
              <Descriptions column={1} size="small" labelStyle={{ width: 140 }}>
                <Descriptions.Item label="红屏阈值">{config.detection.redscreen_threshold}</Descriptions.Item>
                <Descriptions.Item label="三级红屏阈值">{config.detection.severe_threshold}</Descriptions.Item>
                <Descriptions.Item label="可疑下限">{config.detection.suspicious_low}</Descriptions.Item>
                <Descriptions.Item label="同类型冷却">
                  {config.detection.cooldown_minutes} 分钟
                </Descriptions.Item>
                <Descriptions.Item label="Lua 规则引擎">
                  {config.rules.enabled ? <Tag color="green">启用（加分上限 {config.rules.max_bonus}）</Tag> : <Tag>停用</Tag>}
                </Descriptions.Item>
                <Descriptions.Item label="AI 推理融合">
                  {config.ai.enabled ? <Tag color="purple">启用</Tag> : <Tag>关闭</Tag>}
                </Descriptions.Item>
                <Descriptions.Item label="飞书 SSO 登录">
                  {config.feishu.enabled ? <Tag color="geekblue">启用</Tag> : <Tag>关闭</Tag>}
                </Descriptions.Item>
              </Descriptions>
            ) : (
              <Spin />
            )}
          </Card>
        </Col>
      </Row>
    </div>
  )
}