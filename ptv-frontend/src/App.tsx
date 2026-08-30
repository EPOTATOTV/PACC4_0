import { Navigate, Route, Routes } from 'react-router-dom'
import { storedAdminKey } from './api/client'
import Layout from './components/Layout'
import Login from './pages/Login'
import Dashboard from './pages/Dashboard'
import Redscreen from './pages/Redscreen'
import Inspect from './pages/Inspect'
import SignatureLibrary from './pages/SignatureLibrary'
import Accounts from './pages/Accounts'
import Detection41 from './pages/Detection41'
import Compliance from './pages/Compliance'
import CheatRecords from './pages/CheatRecords'
import Competition from './pages/Competition'
import Tournament from './pages/Tournament'
import AdminLoginLogs from './pages/AdminLoginLogs'
import PlayerPortal from './pages/player/PlayerPortal'

export default function App() {
  // 玩家自助门户独立于管理端鉴权，路径以 /portal 开头即进入
  if (window.location.pathname.startsWith('/portal')) {
    return <PlayerPortal />
  }

  const authed = storedAdminKey() !== ''

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
        <Route path="/compliance" element={<Compliance />} />
        <Route path="/login-logs" element={<AdminLoginLogs />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Layout>
  )
}