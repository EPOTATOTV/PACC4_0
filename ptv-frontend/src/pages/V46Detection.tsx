import { useEffect, useState } from 'react'
import { Alert, Button, Card, Col, Drawer, Input, Row, Statistic, Table, Tag, Typography, message } from 'antd'
import { api } from '../api/client'

const { Title, Text } = Typography
const { TextArea } = Input

const tierColor: Record<string, string> = {
  HIGH: 'error',
  MEDIUM: 'warning',
  LOW: 'success',
} as const

function fmtTime(s: string): string {
  if (!s) return '-'
  const d = new Date(s)
  return isNaN(d.getTime()) ? s : d.toLocaleString('zh-CN', { hour12: false })
}

// 兼容实体字段 camelCase 与可能的下划线；防御式取值，避免崩溃
function pick(o: unknown, key: string): unknown {
  if (!o || typeof o !== 'object') return undefined
  const rec = o as Record<string, unknown>
  return rec[key] ?? rec[key.replace(/([a-z])([A-Z])/g, '$1_$2').toLowerCase()]
}

function str(o: unknown): string {
  return o == null ? '' : String(o)
}

export default function V46Detection() {
  const [overview, setOverview] = useState<any | null>(null)
  const [err, setErr] = useState('')
  const [features, setFeatures] = useState('{"feature_killaura_angle_speed":62,"feature_aim_smoothness":0.02,"feature_click_interval_cv":0.03,"feature_semantic_killaura":0.82,"feature_speed_ratio":1.9,"feature_human_likeness":0.12,"feature_trajectory_curvature":0.98,"feature_jitter_entropy":0.4}')
  const [assess, setAssess] = useState<any | null>(null)
  const [threatForm, setThreatForm] = useState('{"md5":"demo-md5-001","sha1":"demo-sha1-001","static_dims":{"java_ghost_client":"com.x.Ghost","java_killaura":"net.y.Kill"}}')
  const [threatOut, setThreatOut] = useState<any | null>(null)
  const [clusters, setClusters] = useState<any | null>(null)
  const [clusterBusy, setClusterBusy] = useState(false)
  const [detail, setDetail] = useState<any | null>(null)

  const load = () => {
    api.v46.overview().then(setOverview).catch((e) => setErr((e as Error).message))
    api.v46
      .threatClusters()
      .then(setClusters)
      .catch(() => setClusters(null))
  }
  useEffect(() => {
    load()
  }, [])

  function runAssess() {
    api.v46
      .assessZeroDay({ pteid: 'demo', edition: 'JAVA', features: JSON.parse(features || '{}') })
      .then(setAssess)
      .catch((e) => message.error((e as Error).message))
  }

  function reviewFinding(id: string, confirmed: boolean) {
    api.v46
      .reviewZeroDay(id, { confirmed, reviewer: 'admin', comment: confirmed ? '确认真样本' : '确认误报' })
      .then(() => {
        message.success('已复核')
        load()
      })
      .catch((e) => message.error((e as Error).message))
  }

  function ingestThreat() {
    api.v46
      .ingestThreat(JSON.parse(threatForm || '{}'))
      .then((r) => {
        setThreatOut(r)
        message.success('威胁情报样本已录入并归族')
        load()
      })
      .catch((e) => message.error((e as Error).message))
  }

  function analyzeThreatSample(id: string) {
    api.v46
      .analyzeThreat(id)
      .then(() => {
        message.success('自动分析完成')
        load()
      })
      .catch((e) => message.error((e as Error).message))
  }

  function runCluster(cfg: { k?: number } = {}) {
    setClusterBusy(true)
    api.v46
      .clusterThreat(cfg.k)
      .then((r) => {
        setClusterBusy(false)
        message.success(`AI 家族聚类完成：${r.analyzed} 个样本归入 ${Object.keys(r.clusters ?? {}).length} 个家族`)
        load()
      })
      .catch((e) => {
        setClusterBusy(false)
        message.error((e as Error).message)
      })
  }

  function promoteThreatSample(id: string) {
    api.v46
      .promoteThreat(id)
      .then((r) => {
        message.success(`已提升为特征码 ${r.name}（${r.state}），可至特征库灰度发布`)
        load()
      })
      .catch((e) => message.error((e as Error).message))
  }

  function reflowFinding(id: string) {
    api.v46
      .reflowZeroDay(id)
      .then((r) => {
        message.success(`已确认真样本并回流入威胁情报样本库` + (r.sample_id ? `（${r.family}）` : ''))
        load()
      })
      .catch((e) => message.error((e as Error).message))
  }

  const zeroDayRecent = (overview?.zero_day?.recent ?? []) as any[]
  const zQueue = (overview?.active_learning?.zero_day_queue ?? []) as any[]
  const threatRecent = (overview?.threat_intel?.recent ?? []) as any[]
  const seeds = (overview?.signature_expansion?.seeds ?? []) as any[]
  const zOpen = overview?.zero_day?.open ?? 0
  const tNew = overview?.threat_intel?.new_count ?? 0
  const seedCount = overview?.signature_expansion?.seed_count ?? 0

  const zColumns = [
    { title: '时间', key: 'createdAt', width: 150, render: (_: unknown, r: any) => fmtTime(str(pick(r, 'createdAt'))) },
    { title: 'PTEID', key: 'pteid', render: (_: unknown, r: any) => str(pick(r, 'pteid')) },
    {
      title: '等级', key: 'tier', width: 90,
      render: (_: unknown, r: any) => {
        const t = str(pick(r, 'confidenceTier'))
        return <Tag color={(tierColor[t] ?? 'default') as string}>{t || '-'}</Tag>
      },
    },
    {
      title: '综合分', key: 'score', width: 80,
      sorter: (a: any, b: any) => Number(pick(a, 'compositeScore') ?? 0) - Number(pick(b, 'compositeScore') ?? 0),
      render: (_: unknown, r: any) => Number(pick(r, 'compositeScore') ?? 0),
    },
    {
      title: '状态', key: 'status', width: 90,
      render: (_: unknown, r: any) => str(pick(r, 'status')),
    },
    {
      title: '复核', key: 'review', width: 220,
      render: (_: unknown, r: any) => {
        const status = str(pick(r, 'status'))
        if (status === 'REVIEWED') return <Text type="secondary">已复核</Text>
        const id = str(pick(r, 'id'))
        return (
          <span style={{ display: 'flex', gap: 6 }}>
            <Button size="small" onClick={() => reviewFinding(id, true)}>真样本</Button>
            <Button size="small" onClick={() => reviewFinding(id, false)}>误报</Button>
            <Button size="small" type="primary" ghost onClick={() => reflowFinding(id)}>确认真样本并回流</Button>
          </span>
        )
      },
    },
  ]

  const tColumns = [
    { title: '时间', key: 'createdAt', width: 150, render: (_: unknown, r: any) => fmtTime(str(pick(r, 'createdAt'))) },
    { title: 'PTEID', key: 'pteid', render: (_: unknown, r: any) => str(pick(r, 'pteid')) },
    { title: '家族', key: 'family', render: (_: unknown, r: any) => <Tag>{str(pick(r, 'family')) || '-'}</Tag> },
    {
      title: 'AI 家族', key: 'familyLabel', width: 150,
      render: (_: unknown, r: any) => {
        const l = str(pick(r, 'familyLabel'))
        return l ? <Tag color="purple">{l}</Tag> : <Text type="secondary">未聚类</Text>
      },
    },
    {
      title: '分析', key: 'analysis', ellipsis: true,
      render: (_: unknown, r: any) => {
        const a = str(pick(r, 'autoAnalysis'))
        if (!a) return <span style={{ fontSize: 12 }}>-</span>
        try {
          const p = JSON.parse(a)
          return (
            <span style={{ fontSize: 12 }}>
              {p.tier && <Tag color={(tierColor[p.tier] ?? 'default') as string}>{p.severity}</Tag>}
              <span style={{ marginLeft: 6 }}>{p.type ?? ''}</span>
            </span>
          )
        } catch {
          return <span style={{ fontSize: 12 }}>{a.slice(0, 40)}</span>
        }
      },
    },
    {
      title: '规则', key: 'rule', ellipsis: true,
      render: (_: unknown, r: any) => <span style={{ fontSize: 12 }}>{str(pick(r, 'generatedRule')) || '-'}</span>,
    },
    { title: '状态', key: 'status', width: 90, render: (_: unknown, r: any) => str(pick(r, 'status')) },
    {
      title: '操作', key: 'actions', width: 240,
      render: (_: unknown, r: any) => (
        <span style={{ display: 'flex', gap: 6 }}>
          <Button size="small" onClick={() => setDetail(r)}>详情</Button>
          <Button size="small" onClick={() => analyzeThreatSample(str(pick(r, 'id')))}>自动分析</Button>
          <Button
            size="small"
            disabled={!str(pick(r, 'generatedRule')) || `${pick(r, 'confirmed')}` !== 'true'}
            onClick={() => promoteThreatSample(str(pick(r, 'id')))}
          >
            晋升特征库
          </Button>
        </span>
      ),
    },
  ]

  const seedColumns = [
    { title: '名称', key: 'name', render: (_: unknown, r: any) => str(pick(r, 'name')) },
    { title: '维度键', key: 'pattern', render: (_: unknown, r: any) => <code>{str(pick(r, 'pattern'))}</code> },
    { title: '风险', key: 'risk', width: 80, render: (_: unknown, r: any) => Number(pick(r, 'riskLevel') ?? 0) },
    { title: '版本', key: 'edition', width: 110, render: (_: unknown, r: any) => str(pick(r, 'edition')) },
  ]

  return (
    <div style={{ maxWidth: 1180 }}>
      <Title level={3} style={{ marginTop: 0 }}>检测深化 · v4.6</Title>
      <Text type="secondary" style={{ display: 'block', marginBottom: 18 }}>
        零日外挂检测（孤立森林 + 线性自编码重构 + 行为基线偏离）与威胁情报 / 主动学习回流
      </Text>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable onClose={() => setErr('')} />}

      <Row gutter={[12, 12]} style={{ marginBottom: 16 }}>
        <Col span={8}><Card><Statistic title="零日发现（待复核）" value={zOpen} /></Card></Col>
        <Col span={8}><Card><Statistic title="威胁情报样本（New）" value={tNew} /></Card></Col>
        <Col span={8}><Card><Statistic title="特征库扩充种子" value={seedCount} /></Card></Col>
      </Row>

      <Card title="零日检测评估" style={{ marginBottom: 16, border: '1px solid #30363d' }}>
        <TextArea
          rows={4} value={features}
          onChange={(e) => setFeatures(e.target.value)}
          placeholder="特征 JSON，键为 feature_x 数值"
          style={{ fontFamily: 'monospace', marginBottom: 10 }}
        />
        <Button type="primary" onClick={runAssess}>执行零日评估</Button>
        {assess && (
          <div style={{ marginTop: 12, fontSize: 13, lineHeight: 1.8 }}>
            <Tag color={(tierColor[assess.confidence_tier] ?? 'default') as string}>{assess.confidence_tier}</Tag>
            <span style={{ marginLeft: 8 }}>综合分 {assess.composite}</span>
            <div style={{ color: '#8b949e' }}>
              iso={assess.iso_score?.toFixed?.(3) ?? 0} · recon={assess.recon_error?.toFixed?.(3) ?? 0} ·
              baseline={assess.baseline_deviation?.toFixed?.(3) ?? 0} · finding={assess.finding_id}
            </div>
          </div>
        )}
      </Card>

      <Card title={`零日发现 / 主动学习队列（${zQueue.length}）`} style={{ marginBottom: 16, border: '1px solid #30363d' }}>
        <Table rowKey={(r) => str(pick(r, 'id'))} columns={zColumns} dataSource={zQueue} size="small" pagination={{ pageSize: 8 }} />
      </Card>

      <Card title="威胁情报" style={{ marginBottom: 16, border: '1px solid #30363d' }}>
        <TextArea
          rows={3} value={threatForm}
          onChange={(e) => setThreatForm(e.target.value)}
          placeholder="录入请求主体 JSON"
          style={{ fontFamily: 'monospace', marginBottom: 10 }}
        />
        <Button onClick={ingestThreat}>录入样本并归族</Button>
        {threatOut && (
          <div style={{ marginTop: 12, fontSize: 13, lineHeight: 1.8 }}>
            <div>sample={threatOut.sample_id} · family=<Tag>{threatOut.family}</Tag></div>
            <div style={{ color: '#8b949e' }}>rule={threatOut.generated_rule}</div>
            {threatOut.matches?.length > 0 && (
              <div>命中特征库：
                {(threatOut.matches as any[]).map((m) => <Tag key={m.name}>{m.name}({m.riskLevel})</Tag>)}
              </div>
            )}
          </div>
        )}
        <Table rowKey={(r) => str(pick(r, 'id'))} columns={tColumns} dataSource={threatRecent} size="small" pagination={{ pageSize: 8 }} style={{ marginTop: 12 }} />
      </Card>

      <Card title="AI 家族聚类（威胁情报样本指纹）" style={{ marginBottom: 16, border: '1px solid #30363d' }}>
        <div style={{ fontSize: 13, marginBottom: 10 }}>
          <Button type="primary" loading={clusterBusy} onClick={() => runCluster()}>执行聚类（k=3）</Button>
          <Button style={{ marginLeft: 8 }} loading={clusterBusy} onClick={() => runCluster({ k: 5 })}>执行聚类（k=5）</Button>
        </div>
        {clusters && (
          <div style={{ fontSize: 13, lineHeight: 1.9 }}>
            {(() => {
              const dist = (clusters.distribution ?? {}) as Record<string, number>
              const entries = Object.entries(dist)
              if (entries.length === 0) return <Text type="secondary">暂无已聚类样本，先录入或执行聚类</Text>
              return (
                <div>
                  {entries.map(([name, count]) => (
                    <div key={name} style={{ marginBottom: 4 }}>
                      <Tag color="purple">{name}</Tag>
                      <span style={{ marginLeft: 6 }}>{count} 个样本</span>
                    </div>
                  ))}
                </div>
              )
            })()}
          </div>
        )}
      </Card>

      <Card title="特征库扩充（基岩 / Java）" style={{ border: '1px solid #30363d' }}>
        <Table rowKey={(r) => str(pick(r, 'name'))} columns={seedColumns} dataSource={seeds} size="small" pagination={{ pageSize: 8 }} />
      </Card>

      {zeroDayRecent.length > 0 && (
        <Text style={{ display: 'block', color: '#8b949e', marginTop: 12, fontSize: 12 }}>
          最近 {zeroDayRecent.length} 条零日发现已记录（置信 LOW 亦参与主动学习队列）
        </Text>
      )}

      <Drawer
        title="威胁情报样本详情"
        width={560}
        open={detail != null}
        onClose={() => setDetail(null)}
      >
        {detail && (() => {
          const dp = pick(detail, 'autoAnalysis')
          let report: any = null
          try { report = dp ? JSON.parse(String(dp)) : null } catch { report = null }
          const confirmed = str(pick(detail, 'confirmed'))
          return (
            <div style={{ fontSize: 13, lineHeight: 2 }}>
              <Row gutter={[8, 4]}>
                <Col span={8}><Text type="secondary">PTEID</Text></Col>
                <Col span={16}>{str(pick(detail, 'pteid')) || '-'}</Col>
                <Col span={8}><Text type="secondary">聚类族</Text></Col>
                <Col span={16}><Tag>{str(pick(detail, 'family')) || '-'}</Tag></Col>
                <Col span={8}><Text type="secondary">AI 家族</Text></Col>
                <Col span={16}>
                  {str(pick(detail, 'familyLabel'))
                    ? <Tag color="purple">{str(pick(detail, 'familyLabel'))}</Tag>
                    : <Text type="secondary">未聚类</Text>}
                </Col>
                <Col span={8}><Text type="secondary">MD5</Text></Col>
                <Col span={16} style={{ wordBreak: 'break-all', fontSize: 12 }}>{str(pick(detail, 'md5')) || '-'}</Col>
                <Col span={8}><Text type="secondary">SHA1</Text></Col>
                <Col span={16} style={{ wordBreak: 'break-all', fontSize: 12 }}>{str(pick(detail, 'sha1')) || '-'}</Col>
                <Col span={8}><Text type="secondary">版本</Text></Col>
                <Col span={16}>{str(pick(detail, 'edition')) || '-'}</Col>
                <Col span={8}><Text type="secondary">状态</Text></Col>
                <Col span={16}>{str(pick(detail, 'status'))}</Col>
                <Col span={8}><Text type="secondary">复核</Text></Col>
                <Col span={16}>{confirmed === 'true' ? <Tag color="success">确认真样本</Tag> : confirmed === 'false' ? <Tag color="default">确认误报</Tag> : <Text type="secondary">待复核</Text>}</Col>
              </Row>

              <div style={{ borderBottom: '1px solid #30363d', marginTop: 14, paddingBottom: 6, fontWeight: 600 }}>
                自动分析报告
              </div>
              {report ? (
                <>
                  <Row gutter={[8, 8]} style={{ marginTop: 8 }}>
                    <Col span={12}>
                      <div>严重度 <Tag color={(tierColor[report.tier] ?? 'default') as string}>{report.severity}</Tag></div>
                    </Col>
                    <Col span={12}><div>类型 <Tag>{report.type ?? '-'}</Tag></div></Col>
                  </Row>
                  <div style={{ marginTop: 8 }}>
                    <Text type="secondary">摘要</Text>
                    <div style={{ color: '#c9d1d9' }}>{report.summary ?? '-'}</div>
                  </div>
                  {report.matched_seeds && Array.isArray(report.matched_seeds) && report.matched_seeds.length > 0 && (
                    <div style={{ marginTop: 8 }}>
                      <Text type="secondary">命中特征库</Text>
                      <div>{(report.matched_seeds as any[]).map((s) => <Tag key={s} style={{ marginBottom: 4 }}>{s}</Tag>)}</div>
                    </div>
                  )}
                  {report.indicators && Array.isArray(report.indicators) && report.indicators.length > 0 && (
                    <div style={{ marginTop: 8 }}>
                      <Text type="secondary">提取指标</Text>
                      <div>{(report.indicators as any[]).map((i) => <Tag key={i} color="processing" style={{ marginBottom: 4 }}>{i}</Tag>)}</div>
                    </div>
                  )}
                  <div style={{ marginTop: 8 }}>
                    <Text type="secondary">建议</Text>
                    <div><Tag color={report.suggestion === 'NORMAL' ? 'success' : 'warning'}>{report.suggestion ?? '-'}</Tag></div>
                  </div>
                </>
              ) : (
                <Text type="secondary" style={{ display: 'block', marginTop: 8 }}>尚未执行自动分析</Text>
              )}

              <div style={{ borderBottom: '1px solid #30363d', marginTop: 14, paddingBottom: 6, fontWeight: 600 }}>
                检测规则（晋升用）
              </div>
              <pre style={{ fontSize: 12, color: '#8b949e', whiteSpace: 'pre-wrap', wordBreak: 'break-all', marginTop: 8 }}>
                {str(pick(detail, 'generatedRule')) || '无'}
              </pre>
            </div>
          )
        })()}
      </Drawer>
    </div>
  )
}