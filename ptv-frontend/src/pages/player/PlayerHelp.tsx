import { useMemo, useState } from 'react'
import { Card, Collapse, Empty, Input, Tag } from 'antd'
import { SearchOutlined } from '@ant-design/icons'

interface FaqItem {
  category: string
  q: string
  a: string
}

const FAQS: FaqItem[] = [
  { category: '保护', q: '如何启用实时保护？', a: '进入「实时保护」页面，点击启用即可。扫描引擎会在后台运行，发现可疑行为会触发红屏告警。' },
  { category: '保护', q: '误报如何处理？', a: '若认为误报，可在「作弊记录」中对相应记录提出申诉，并附上客观证据，客服会在工单内与您核对。' },
  { category: '账号', q: '如何修改登录密码？', a: '在「账号安全」中选择修改密码，需通过邮箱/设备验证。建议使用独立的高强度密码并开启二次验证。' },
  { category: '账号', q: '两步验证（2FA）如何开启？', a: '在「账号安全」中点击开启 2FA，使用身份验证器 App 扫描二维码，并妥善保存一次性恢复码。' },
  { category: '赛事', q: '如何报名赛事？', a: '进入「赛事」页面查看进行中的赛事，点击报名并填写战队信息；赛事风控审核通过后即报名成功。' },
  { category: '赛事', q: '地图 BP（Ban/Pick）是什么？', a: '赛事地图禁用/选择流程：由裁判建立 BP 会话，双方按顺序禁用或选择地图，实时同步到参赛方。' },
  { category: '记录', q: '怎么查看我的作弊记录？', a: '在「作弊记录」页面可查看全部历史记录，支持按类型筛选，并可针对某条记录发起申诉。' },
  { category: '售后', q: '申诉多久能得到答复？', a: '通常 1-3 个工作日。涉及复杂复核的会延后，进度会在申诉详情的对话中实时更新。' },
]

const CATEGORIES = ['全部', '保护', '账号', '赛事', '记录', '售后']

export default function PlayerHelp() {
  const [kw, setKw] = useState('')
  const [cat, setCat] = useState('全部')

  const filtered = useMemo(() => {
    const k = kw.trim().toLowerCase()
    return FAQS.filter((f) => {
      if (cat !== '全部' && f.category !== cat) return false
      if (!k) return true
      return f.q.toLowerCase().includes(k) || f.a.toLowerCase().includes(k)
    })
  }, [kw, cat])

  return (
    <div>
      <h3 style={{ margin: '0 0 16px', fontSize: 20 }}>帮助中心</h3>
      <Card>
        <Input
          size="large"
          allowClear
          prefix={<SearchOutlined style={{ color: 'var(--muted)' }} />}
          placeholder="搜索问题，如：2FA、申诉、报名"
          value={kw}
          onChange={(e) => setKw(e.target.value)}
        />
        <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
          {CATEGORIES.map((c) => (
            <Tag
              key={c}
              color={cat === c ? 'red' : 'default'}
              style={{ cursor: 'pointer', padding: '2px 10px' }}
              onClick={() => setCat(c)}
            >
              {c}
            </Tag>
          ))}
        </div>
      </Card>

      <div style={{ marginTop: 16 }}>
        {filtered.length === 0 ? (
          <Card><Empty description="没有匹配的问题" /></Card>
        ) : (
          <Collapse
            items={filtered.map((f) => ({
              key: f.q,
              label: <span><Tag color="geekblue">{f.category}</Tag>{f.q}</span>,
              children: f.a,
            }))}
          />
        )}
      </div>
    </div>
  )
}