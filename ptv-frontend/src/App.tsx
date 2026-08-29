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

export default function App() {
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
        <Route path="/detection41" element={<Detection41 />} />
        <Route path="/compliance" element={<Compliance />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </Layout>
  )
}