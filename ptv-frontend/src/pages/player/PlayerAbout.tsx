import { Card, Col, Row, Tag } from 'antd'

const OPEN_SOURCE = [
  { name: 'Spring Boot', version: '3.2.x', license: 'Apache-2.0' },
  { name: 'React', version: '18.x', license: 'MIT' },
  { name: 'ECMAScript / ECharts', version: '5.x', license: 'Apache-2.0' },
  { name: 'Bouncy Castle', version: '1.78.x', license: 'MIT' },
  { name: 'org.luaj (LuaJ)', version: '3.0.1', license: 'MIT' },
]

export default function PlayerAbout() {
  return (
    <div>
      <h3 style={{ margin: '0 0 16px', fontSize: 20 }}>关于 PACC</h3>
      <Row gutter={[16, 16]}>
        <Col xs={24} md={10}>
          <Card>
            <div style={{ fontSize: 26, fontWeight: 800, letterSpacing: 1 }}>PACC</div>
            <div style={{ color: 'var(--muted)', marginTop: 4 }}>Potatotv Anti-Cheat Center · v5.0.0</div>
            <div style={{ marginTop: 12, lineHeight: 1.8, color: '#c7ccd6' }}>
              PACC 是一套面向电竞赛事的反作弊管控平台，覆盖实时保护、检测监控、远程查端、作弊申诉与赛事风控，
              为赛事运营与参赛玩家提供可信的对局环境。客户端可下载独立安装包，管理端通过浏览器访问。
            </div>
            <div style={{ marginTop: 12 }}>
              <Tag>2026</Tag><Tag color="blue">POTATOTV</Tag>
            </div>
          </Card>
        </Col>
        <Col xs={24} md={14}>
          <Card title="开源与第三方组件">
            {OPEN_SOURCE.map((o) => (
              <div key={o.name} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '8px 0', borderBottom: '1px solid var(--border)' }}>
                <span>{o.name} <span style={{ color: 'var(--muted)' }}>v{o.version}</span></span>
                <Tag>{o.license}</Tag>
              </div>
            ))}
          </Card>
        </Col>
      </Row>
      <Card size="small" style={{ marginTop: 16 }}>
        <div style={{ fontSize: 13, color: 'var(--muted)', lineHeight: 1.7 }}>
          使用本产品即表示同意遵守赛事反作弊规范与用户协议。遭受疑似误报时请通过「申诉」提交证据，我们将在承诺时限内复核。
          检测引擎仅在运行时进行行为分析，不会上传你的隐私数据。请通过官方渠道下载，校验安装包 SHA256 以防范供应链风险。
        </div>
      </Card>
    </div>
  )
}