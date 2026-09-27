import { Suspense, lazy, useEffect, useState } from 'react'
import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { Spin } from 'antd'
import { api } from './api/client'
import Layout from './components/Layout'
import PageTransition from './components/PageTransition'
import TopProgressBar from './components/TopProgressBar'

// 页面组件一律按需加载：全站 80+ 页面若静态引入会打进同一个 chunk，
// 首屏要把大屏、报表、图表这些多数人用不到的实现一起下载。
const Login = lazy(() => import('./pages/Login'))
const Dashboard = lazy(() => import('./pages/Dashboard'))
const Redscreen = lazy(() => import('./pages/Redscreen'))
const Inspect = lazy(() => import('./pages/Inspect'))
const SignatureLibrary = lazy(() => import('./pages/SignatureLibrary'))
const Accounts = lazy(() => import('./pages/Accounts'))
const Detection41 = lazy(() => import('./pages/Detection41'))
const Countermeasure = lazy(() => import('./pages/Countermeasure'))
const V46Detection = lazy(() => import('./pages/V46Detection'))
const V47ThreatIntel = lazy(() => import('./pages/V47ThreatIntel'))
const Compliance = lazy(() => import('./pages/Compliance'))
const CheatRecords = lazy(() => import('./pages/CheatRecords'))
const Competition = lazy(() => import('./pages/Competition'))
const Tournament = lazy(() => import('./pages/Tournament'))
const AdminLoginLogs = lazy(() => import('./pages/AdminLoginLogs'))
const BiReport = lazy(() => import('./pages/bi/BiReport'))
const OpenApi = lazy(() => import('./pages/openapi/OpenApi'))
const System = lazy(() => import('./pages/System'))
const Admins = lazy(() => import('./pages/Admins'))
const Audit = lazy(() => import('./pages/Audit'))
const Tenant = lazy(() => import('./pages/Tenant'))
const StreamLive = lazy(() => import('./pages/StreamLive'))
const AbExperiment = lazy(() => import('./pages/AbExperiment'))
const OpsCenter = lazy(() => import('./pages/OpsCenter'))
const SupportCenter = lazy(() => import('./pages/SupportCenter'))
const MapPoolManager = lazy(() => import('./pages/maps/MapPoolManager'))
const BpSessions = lazy(() => import('./pages/maps/BpSessions'))
const BpConsole = lazy(() => import('./pages/maps/BpConsole'))
const Releases = lazy(() => import('./pages/p1/Releases'))
const RuleEditor = lazy(() => import('./pages/p1/RuleEditor'))
const Lists = lazy(() => import('./pages/p1/Lists'))
const Exports = lazy(() => import('./pages/p1/Exports'))
const Effectiveness = lazy(() => import('./pages/p1/Effectiveness'))
const DetectorConfig = lazy(() => import('./pages/p1/DetectorConfig'))
const EffectConfig = lazy(() => import('./pages/p1/EffectConfig'))
const ModelManager = lazy(() => import('./pages/v52/ModelManager'))
const BehaviorProfile = lazy(() => import('./pages/v52/BehaviorProfile'))
const ReputationManager = lazy(() => import('./pages/v52/ReputationManager'))
const ReplayManager = lazy(() => import('./pages/v52/ReplayManager'))
const DeviceFingerprint = lazy(() => import('./pages/v52/DeviceFingerprint'))
const ApmMonitor = lazy(() => import('./pages/v54/ApmMonitor'))
const SecurityAudit = lazy(() => import('./pages/v54/SecurityAudit'))
const KeyManagement = lazy(() => import('./pages/v54/KeyManagement'))
const DfScreen = lazy(() => import('./pages/df/DfScreen'))
const DfAlertNoise = lazy(() => import('./pages/df/AlertNoise'))
const DfAutomation = lazy(() => import('./pages/df/Automation'))
const DfPluginRuntime = lazy(() => import('./pages/df/PluginRuntime'))
const DfTenantQuota = lazy(() => import('./pages/df/TenantQuota'))
const PlayerPortal = lazy(() => import('./pages/player/PlayerPortal'))
const PlayerScreenShare = lazy(() => import('./pages/player/PlayerScreenShare'))

/** 按需加载页面时的占位，避免切换路由时白屏 */
function PageLoading() {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '40vh' }}>
      <Spin />
    </div>
  )
}

export default function App() {
  // 先声明 Hook（必须无条件、固定顺序），再做路径分支渲染，避免条件调用 Hook
  const { pathname } = useLocation()
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

  // 「文档与协议」分组（含内部技术资料）仅超级管理员可访问；非超管直接访问时重定向回数据大盘
  const gatedDocs = ['/detection41', '/countermeasure', '/v46', '/v47', '/openapi', '/bi']
  const docGated = role !== 'super-admin' && gatedDocs.some((p) => pathname === p || pathname.startsWith(p + '/'))

  function renderRoute() {
    // 玩家自助门户独立于管理端鉴权，路径以 /portal 开头即进入
    if (pathname.startsWith('/portal')) {
      return <PlayerPortal />
    }

    // 远程查端屏幕共享页：桌面壳 WebView 以 /screen-share 打开，独立于管理端鉴权
    if (pathname.startsWith('/screen-share')) {
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

    // DF §4.3.1 数据可视化大屏：全屏墙显，脱离 Layout（无侧栏/面包屑），仅保留登录态校验
    if (pathname.startsWith('/df/screen')) {
      return <DfScreen />
    }

    if (docGated) {
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
            <Route path="/prl-editor" element={<RuleEditor />} />
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
            <Route path="/v54/apm" element={<ApmMonitor />} />
            <Route path="/v54/security" element={<SecurityAudit />} />
            <Route path="/v54/keys" element={<KeyManagement />} />
            <Route path="/df/alerts-noise" element={<DfAlertNoise />} />
            <Route path="/df/automation" element={<DfAutomation />} />
            <Route path="/df/plugins" element={<DfPluginRuntime />} />
            <Route path="/df/tenant-quota" element={<DfTenantQuota />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </PageTransition>
      </Layout>
    )
  }

  return <Suspense fallback={<PageLoading />}>{renderRoute()}</Suspense>
}
