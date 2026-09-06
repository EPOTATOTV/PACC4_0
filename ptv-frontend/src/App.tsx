import { useEffect, useState } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { Spin } from 'antd'
import { api } from './api/client'
import Layout from './components/Layout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Redscreen from './pages/Redscreen'
import Inspect from './pages/Inspect'
import SignatureLibrary from './pages/SignatureLibrary'
import Accounts from './pages/Accounts'
import Detection41 from './pages/Detection41'
import Countermeasure from './pages/Countermeasure'
import V46Detection from './pages/V46Detection'
import Compliance from './pages/Compliance'
import CheatRecords from './pages/CheatRecords'
import Competition from './pages/Competition'
import Tournament from './pages/Tournament'
import AdminLoginLogs from './pages/AdminLoginLogs'
import System from './pages/System'
import Admins from './pages/Admins'
import Audit from './pages/Audit'
import AbExperiment from './pages/AbExperiment'
import OpsCenter from './pages/OpsCenter'
import SupportCenter from './pages/SupportCenter'
import PlayerPortal from './pages/player/PlayerPortal'
import PlayerScreenShare from './pages/player/PlayerScreenShare'

export default function App() {
  // 玩家自助门户独立于管理端鉴权，路径以 /portal 开头即进入
  if (window.location.pathname.startsWith('/portal')) {
    return <PlayerPortal />
  }

  // 远程查端屏幕共享页：桌面壳 WebView 以 /screen-share 打开，独立于管理端鉴权
  if (window.location.pathname.startsWith('/screen-share')) {
    return <PlayerScreenShare />
  }

  const [authed, setAuthed] = useState<boolean | null>(null)

  useEffect(() => {
    let alive = true
    api.adminSession
      .me()
      .then(() => alive && setAuthed(true))
      .catch(() => alive && setAuthed(false))
    return () => {
      alive = false
    }
  }, [])

  if (authed === null) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '100vh' }}>
        <Spin />
      </div>
    )
  }

  if (!authed) {
    return (
      <Routes>
        <Route path="*" element={<Login />} />
      </Routes>
    )
  }

  return (
    <Layout>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/redscreen" element={<Redscreen />} />
        <Route path="/inspect" element={<Inspect />} />
        <Route path="/signatures" element={<SignatureLibrary />} />
        <Route path="/accounts" element={<Accounts />} />
        <Route path="/records" element={<CheatRecords />} />
        <Route path="/competition" element={<Competition />} />
        <Route path="/tournament" element={<Tournament />} />
        <Route path="/detection41" element={<Detection41 />} />
        <Route path="/countermeasure" element={<Countermeasure />} />
        <Route path="/v46" element={<V46Detection />} />
        <Route path="/compliance" element={<Compliance />} />
        <Route path="/login-logs" element={<AdminLoginLogs />} />
        <Route path="/audit" element={<Audit />} />
        <Route path="/admins" element={<Admins />} />
        <Route path="/system" element={<System />} />
        <Route path="/ab" element={<AbExperiment />} />
        <Route path="/ops" element={<OpsCenter />} />
        <Route path="/support" element={<SupportCenter />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Layout>
  )
}