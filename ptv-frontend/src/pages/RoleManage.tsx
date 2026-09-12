import { useEffect, useState } from 'react'
import { Alert, Button, Card, Checkbox, Col, Drawer, Popconfirm, Row, Space, Table, Tag, Typography, message } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { api } from '../api/client'
import type { AdminRole, RolePermission } from '../types'

const { Title, Text } = Typography

/**
 * 角色与权限管理：角色列表、权限矩阵（模块 × 操作）。
 * 数据来自 /api/admin/roles 系列，后端未就绪显示空态。
 */
export default function RoleManage() {
  const [roles, setRoles] = useState<AdminRole[]>([])
  const [err, setErr] = useState('')
  const [editing, setEditing] = useState<AdminRole | null>(null)
  const [dirty, setDirty] = useState<RolePermission[]>([])

  async function load() {
    try {
      setRoles(await api.roles.list())
      setErr('')
    } catch (e) {
      setErr((e as Error).message)
    }
  }

  useEffect(() => {
    load()
  }, [])

  function openEdit(r: AdminRole) {
    setEditing(r)
    setDirty(JSON.parse(JSON.stringify(r.permissions ?? [])) as RolePermission[])
  }

  function toggleAction(moduleIndex: number, actionKey: string, granted: boolean) {
    setDirty((prev) =>
      prev.map((m, i) =>
        i === moduleIndex
          ? { ...m, actions: m.actions.map((a) => (a.key === actionKey ? { ...a, granted } : a)) }
          : m,
      ),
    )
  }

  async function save() {
    if (!editing) return
    try {
      await api.roles.update(editing.id, { permissions: dirty })
      message.success('权限已保存')
      setEditing(null)
      load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  async function removeRole(id: string) {
    try {
      await api.roles.remove(id)
      load()
    } catch (e) {
      message.error((e as Error).message)
    }
  }

  const columns: TableColumnsType<AdminRole> = [
    { title: '角色', dataIndex: 'name', render: (v: string, r) => <span>{v} {r.builtin && <Tag color="blue">内置</Tag>}</span> },
    { title: '标识', dataIndex: 'key', width: 140, render: (v: string) => <Text code>{v}</Text> },
    { title: '描述', dataIndex: 'description', render: (v?: string) => v || <Text type="secondary">—</Text> },
    { title: '成员', dataIndex: 'memberCount', width: 90 },
    {
      title: '操作',
      width: 160,
      render: (_, r) => (
        <Space>
          <Button size="small" onClick={() => openEdit(r)}>权限</Button>
          {!r.builtin && (
            <Popconfirm title="删除该角色？" onConfirm={() => removeRole(r.id)}>
              <Button size="small" danger>删除</Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ]

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 16, flexWrap: 'wrap' }}>
        <Title level={3} style={{ margin: 0 }}>角色与权限</Title>
        <div style={{ flex: 1 }} />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => message.info('新建角色（接入后端后启用）')}>新建角色</Button>
        {err && <Alert type="error" showIcon message={err} style={{ flexBasis: '100%' }} closable onClose={() => setErr('')} />}
      </div>

      <Card styles={{ body: { padding: 0 } }}>
        <Table<AdminRole> rowKey="id" columns={columns} dataSource={roles} pagination={false} scroll={{ x: 640 }} locale={{ emptyText: '暂无角色' }} />
      </Card>

      <Drawer
        title={editing ? `权限矩阵 · ${editing.name}` : ''}
        open={!!editing}
        onClose={() => setEditing(null)}
        width={600}
        extra={<Button type="primary" onClick={save}>保存权限</Button>}
      >
        {editing && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {dirty.map((m, mi) => (
              <Card key={m.module} size="small" title={m.moduleLabel} styles={{ body: { padding: '8px 16px' } }}>
                <Row gutter={[24, 8]}>
                  {m.actions.map((a) => (
                    <Col key={a.key} xs={12} md={8}>
                      <Checkbox checked={a.granted} onChange={(e) => toggleAction(mi, a.key, e.target.checked)}>
                        {a.label}
                      </Checkbox>
                    </Col>
                  ))}
                </Row>
              </Card>
            ))}
          </div>
        )}
      </Drawer>
    </div>
  )
}