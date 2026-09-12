import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Row, Col, Space, Switch, Table, Tag, Typography, message } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlayCircleOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import type { Broadcast } from '../types'
import MetricCard from '../components/MetricCard'

const { Title, Text } = Typography

export default function StreamLivePage() {
  const [rows, setRows] = useState<Broadcast[]>([])
  const [loading, setLoading] = useState(false)
  const [err, setErr] = useState('')

  const [form] = Form.useForm()
  const [editing, setEditing] = useState<Broadcast | null>(null)
  const [open, setOpen] = useState(false)

  const load = useCallback(() => {
    setLoading(true)
    api.streamLive.list()
      .then((d) => setRows(d.rows ?? []))
      .catch((e) => setErr(String((e as Error)?.message || e)))
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => { load() }, [load])

  const liveCount = useMemo(() => rows.filter((r) => r.live).length, [rows])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ platform: 'bilibili', live: false, sort: 0 })
    setOpen(true)
  }

  const openEdit = (b: Broadcast) => {
    setEditing(b)
    form.setFieldsValue({
      title: b.title,
      bilibili_live_id: b.bilibili_live_id,
      cover_url: b.cover_url,
      description: b.description,
      platform: b.platform,
      live: b.live,
      sort: b.sort,
    })
    setOpen(true)
  }

  const submit = async () => {
    const v = await form.validateFields()
    try {
      if (editing) {
        await api.streamLive.update(editing.id, v)
        message.success('已更新转播')
      } else {
        await api.streamLive.create(v)
        message.success('已新增转播')
      }
      setOpen(false)
      load()
    } catch (e) {
      setErr(String((e as Error)?.message || e))
    }
  }

  const toggleLive = async (b: Broadcast, live: boolean) => {
    try {
      await api.streamLive.setLive(b.id, live)
      message.success(live ? '已设为直播中' : '已下线')
      load()
    } catch (e) { setErr(String((e as Error)?.message || e)) }
  }

  const remove = async (b: Broadcast) => {
    try {
      await api.streamLive.remove(b.id)
      message.success('已删除转播')
      load()
    } catch (e) { setErr(String((e as Error)?.message || e)) }
  }

  const goStream = (b: Broadcast) => {
    window.open(`https://live.bilibili.com/${b.bilibili_live_id}`, '_blank', 'noopener')
  }

  const cols: TableColumnsType<Broadcast> = [
    { title: '标题', dataIndex: 'title', width: 200 },
    {
      title: '直播间号', dataIndex: 'bilibili_live_id', width: 160,
      render: (v, b) => <a onClick={() => goStream(b)} style={{ fontFamily: 'monospace' }}>live.bilibili.com/{v as string}</a>,
    },
    { title: '平台', dataIndex: 'platform', width: 110, render: (v) => <Tag>{v}</Tag> },
    {
      title: '状态', dataIndex: 'live', width: 120,
      render: (v, b) => (
        <Space size={8}>
          <Switch size="small" checked={!!v} onChange={(c) => toggleLive(b, c)} />
          {v ? <Tag color="success">直播中</Tag> : <Tag>已下线</Tag>}
        </Space>
      ),
    },
    { title: '排序', dataIndex: 'sort', width: 80 },
    {
      title: '操作', width: 200,
      render: (_, b) => (
        <Space size={4}>
          <Button size="small" icon={<PlayCircleOutlined />} onClick={() => goStream(b)}>观看</Button>
          <Button size="small" onClick={() => openEdit(b)}>编辑</Button>
          <Popconfirm title="删除该转播？" onConfirm={() => remove(b)} okText="删除" cancelText="取消">
            <Button size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 14, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>赛事直播转播</Title>
        <Text type="secondary" style={{ fontSize: 12 }}>
          维护 OBS 推流的 B 站直播间；开启"直播中"后即对玩家侧可见
        </Text>
        <div style={{ flex: 1 }} />
        <Button type="primary" onClick={openCreate}>新增转播</Button>
      </div>

      {err && <Alert type="error" showIcon message={err} style={{ marginBottom: 16 }} closable onClose={() => setErr('')} />}

      <Row gutter={[12, 12]} className="pacc-in" style={{ marginBottom: 16 }}>
        <Col xs={12} sm={8} md={6}><MetricCard label="直播中" value={liveCount} accent="var(--kpi-green)" /></Col>
        <Col xs={12} sm={8} md={6}><MetricCard label="转播总数" value={rows.length} accent="var(--kpi-blue)" /></Col>
      </Row>

      <Card title="转播列表" bordered={false}
        extra={<Text type="secondary" style={{ fontSize: 12 }}>点击标题可外跳观看</Text>}>
        <Table<Broadcast> rowKey="id" columns={cols} dataSource={rows} loading={loading} size="middle"
          pagination={false} locale={{ emptyText: '暂无转播，点击右上角新增' }} scroll={{ x: 900 }} />
      </Card>

      <Modal title={editing ? `编辑转播 · ${editing.title}` : '新增转播'} open={open} onOk={submit} onCancel={() => setOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="title" label="直播标题" rules={[{ required: true, message: '请输入直播标题' }]}>
            <Input placeholder="如 PACC 冬季赛决赛》" />
          </Form.Item>
          <Form.Item name="bilibili_live_id" label="B 站直播间号" rules={[{ required: true, message: '请输入直播间号' }]}
            extra="即 live.bilibili.com/ 后的数字">
            <Input placeholder="如 1234567" />
          </Form.Item>
          <Form.Item name="description" label="直播简介">
            <Input.TextArea rows={2} placeholder="可选" />
          </Form.Item>
          <Form.Item name="cover_url" label="封面图地址">
            <Input placeholder="可选，填写图片 URL" />
          </Form.Item>
          <Space size={24} wrap>
            <Form.Item name="platform" label="平台">
              <Input style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="sort" label="排序(小在前)">
              <InputNumber min={0} />
            </Form.Item>
          </Space>
          <Form.Item name="live" label="对外可见(直播中)" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}