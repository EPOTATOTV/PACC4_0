import { Card, Col, Row, Tag } from 'antd'
import { DownloadOutlined } from '@ant-design/icons'

interface ReleaseCard {
  platform: string
  arch: string
  version: string
  note: string
  url: string
  sha256Note: string
}

// 客户端下载（正式对外发布在 deploy/dl-web/files/）。仅展示版本信息与跳转入口。
const RELEASES: ReleaseCard[] = [
  { platform: 'Windows', arch: 'x64', version: '5.4.0', note: '推荐 · 覆盖主流 PC', url: '/files/pacc-setup-5.4.0-x64.exe', sha256Note: 'SHA256 校验随包提供' },
  { platform: 'macOS', arch: 'Apple Silicon', version: '5.4.0', note: 'macOS 13+', url: '/files/pacc-5.4.0-arm64.dmg', sha256Note: 'SHA256 校验随包提供' },
  { platform: 'Android', arch: 'arm64', version: '5.4.0', note: 'Android 8.0+', url: '/files/pacc-5.4.0-android.apk', sha256Note: 'SHA256 校验随包提供' },
  { platform: 'iOS', arch: '通用', version: '5.4.0', note: 'App Store 上架版', url: '/files/pacc-5.4.0.ipa', sha256Note: 'SHA256 校验随包提供' },
  { platform: 'Linux', arch: 'x64', version: '5.4.0', note: 'Ubuntu 20.04+', url: '/files/pacc-5.4.0-linux.tar.gz', sha256Note: 'SHA256 校验随包提供' },
]

export default function PlayerDownload() {
  return (
    <div>
      <h3 style={{ margin: '0 0 16px', fontSize: 20 }}>下载与更新中心</h3>
      <Row gutter={[16, 16]}>
        {RELEASES.map((r) => (
          <Col xs={24} md={12} lg={8} key={r.platform}>
            <Card
              hoverable
              onClick={() => window.open(r.url, '_blank')}
              styles={{ body: { display: 'flex', flexDirection: 'column', gap: 8 } }}
              style={{ cursor: 'pointer' }}
            >
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: 16, fontWeight: 600 }}>{r.platform}</span>
                <Tag color="red">v{r.version}</Tag>
              </div>
              <div style={{ color: 'var(--muted)', fontSize: 13 }}>{r.arch} · {r.note}</div>
              <div style={{ fontSize: 12, color: 'var(--muted)' }}>{r.sha256Note}</div>
              <div style={{ marginTop: 4, color: '#ff6b5e' }}><DownloadOutlined /> 点击下载</div>
            </Card>
          </Col>
        ))}
      </Row>
    </div>
  )
}