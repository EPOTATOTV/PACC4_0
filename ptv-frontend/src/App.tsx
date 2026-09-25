import { useEffect, useState } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { Spin } from 'antd'
import { api } from './api/client'
import Layout from './components/Layout'
import PageTransition from './components/PageTransition'
import TopProgressBar from './components/TopProgressBar'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Redscreen from './pages/Redscreen'
import Inspect from './pages/Inspect'
import SignatureLibrary from './pages/SignatureLibrary'
import Accounts from './pages/Accounts'
import Detection41 from './pages/Detection41'
import Countermeasure from './pages/Countermeasure'
import V46Detection from './pages/V46Detection'
import V47ThreatIntel from './pages/V47ThreatIntel'
import Compliance from './pages/Compliance'
import CheatRecords from './pages/CheatRecords'
import Competition from './pages/Competition'
import Tournament from './pages/Tournament'
import AdminLoginLogs from './pages/AdminLoginLogs'
import BiReport from './pages/bi/BiReport'
import OpenApi from './pages/openapi/OpenApi'
import System from './pages/System'
import Admins from './pages/Admins'
import Audit from './pages/Audit'
import Tenant from './pages/Tenant'
import StreamLive from './pages/StreamLive'
import AbExperiment from './pages/AbExperiment'
import OpsCenter from './pages/OpsCenter'
import SupportCenter from './pages/SupportCenter'
import MapPoolManager from './pages/maps/MapPoolManager'
import BpSessions from './pages/maps/BpSessions'
import BpConsole from './pages/maps/BpConsole'
import Releases from './pages/p1/Releases'
import Lists from './pages/p1/Lists'
import Exports from './pages/p1/Exports'
import Effectiveness from './pages/p1/Effectiveness'
import DetectorConfig from './pages/p1/DetectorConfig'
import EffectConfig from './pages/p1/EffectConfig'
import ModelManager from './pages/v52/ModelManager'
import BehaviorProfile from './pages/v52/BehaviorProfile'
import ReputationManager from './pages/v52/ReputationManager'
import ReplayManager from './pages/v52/ReplayManager'
import DeviceFingerprint from './pages/v52/DeviceFingerprint'
import PlayerPortal from './pages/player/PlayerPortal'
import PlayerScreenShare from './pages/player/PlayerScreenShare'

export default function App() {
  // 先声明 Hook（必须无条件、固定顺序），再做路径分支渲染，避免条件调用 Hook
  const [authed, setAuthed] = useState<boolean | null>(null)
  const [role, setRole] = useState<string>('')

  useEffect(() => {
    let alive = true
    api.adminSession
      .me()
      .then((d) => { if (alive) { setAuthed(true); setRole(d.role) } })
      .catch(() => alive && setAuthed(false))
    return () => {
      alive = false
    }
  }, [])

  // 玩家自助门户独立于管理端鉴权，路径以 /portal 开头即进入
  if (window.location.pathname.startsWith('/portal')) {
    return <PlayerPortal />
  }

  // 远程查端屏幕共享页：桌面壳 WebView 以 /screen-share 打开，独立于管理端鉴权
  if (window.location.pathname.startsWith('/screen-share')) {
    return <PlayerScreenShare />
  }

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

  // 「文档与协议」分组（含内部技术资料）仅超级管理员可访问；非超管直接访问时重定向回数据大盘
  const isSuper = role === 'super-admin'
  const gatedDocs = ['/detection41', '/countermeasure', '/v46', '/v47', '/openapi', '/bi']
  if (!isSuper && gatedDocs.some((p) => window.location.pathname === p || window.location.pathname.startsWith(p + '/'))) {
    return <Navigate to="/" replace />
  }

  return (
    <Layout role={role}>
      <TopProgressBar />
      <PageTransition>
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/redscreen" element={<Redscreen />} />
          <Route path="/inspect" element={<Inspect />} />
          <Route path="/signatures" element={<SignatureLibrary />} />
          <Route path="/accounts" element={<Accounts />} />
          <Route path="/records" element={<CheatRecords />} />
          <Route path="/competition" element={<Competition />} />
          <Route path="/tournament" element={<Tournament />} />
          <Route path="/maps" element={<MapPoolManager />} />
          <Route path="/maps/bp" element={<BpSessions />} />
          <Route path="/maps/bp/:bpId" element={<BpConsole />} />
          <Route path="/stream-live" element={<StreamLive />} />
          <Route path="/detection41" element={<Detection41 />} />
          <Route path="/countermeasure" element={<Countermeasure />} />
          <Route path="/v46" element={<V46Detection />} />
          <Route path="/v47" element={<V47ThreatIntel />} />
          <Route path="/compliance" element={<Compliance />} />
          <Route path="/login-logs" element={<AdminLoginLogs />} />
          <Route path="/audit" element={<Audit />} />
          <Route path="/tenant" element={<Tenant />} />
          <Route path="/admins" element={<Admins />} />
          <Route path="/system" element={<System />} />
          <Route path="/bi" element={<BiReport />} />
          <Route path="/openapi" element={<OpenApi />} />
          <Route path="/ab" element={<AbExperiment />} />
          <Route path="/ops" element={<OpsCenter />} />
          <Route path="/support" element={<SupportCenter />} />
          <Route path="/releases" element={<Releases />} />
          <Route path="/lists" element={<Lists />} />
          <Route path="/export" element={<Exports />} />
          <Route path="/effectiveness" element={<Effectiveness />} />
          <Route path="/config" element={<DetectorConfig />} />
          <Route path="/motion" element={<EffectConfig />} />
          <Route path="/v52/model" element={<ModelManager />} />
          <Route path="/v52/profile" element={<BehaviorProfile />} />
          <Route path="/v52/reputation" element={<ReputationManager />} />
          <Route path="/v52/replay" element={<ReplayManager />} />
          <Route path="/v52/devices" element={<DeviceFingerprint />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </PageTransition>
    </Layout>
  )
}