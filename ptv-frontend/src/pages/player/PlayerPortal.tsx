import { useEffect, useState } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { Spin } from 'antd'
import { api } from '../../api/client'
import PlayerLogin from './PlayerLogin'
import PlayerRegister from './PlayerRegister'
import PlayerForget from './PlayerForget'
import PlayerLayout from './PlayerLayout'
import PlayerOverview from './PlayerOverview'
import PlayerRecords from './PlayerRecords'
import PlayerAppeals from './PlayerAppeals'
import PlayerTickets from './PlayerTickets'
import PlayerDevices from './PlayerDevices'
import PlayerTournament from './PlayerTournament'
import PlayerSettings from './PlayerSettings'
import PlayerDiagnostics from './PlayerDiagnostics'
import PlayerProtection from './PlayerProtection'
import PlayerMonitor from './PlayerMonitor'
import PlayerNotifications from './PlayerNotifications'
import PlayerRedScreenDetail from './PlayerRedScreenDetail'
import PlayerSecurity from './PlayerSecurity'
import PlayerAppealDetail from './PlayerAppealDetail'
import PlayerTicketDetail from './PlayerTicketDetail'
import PlayerStreamLive from './PlayerStreamLive'
import PlayerMapPools from './PlayerMapPools'
import PlayerMapBp from './PlayerMapBp'
import PlayerReputation from './PlayerReputation'
import PlayerHelp from './PlayerHelp'
import PlayerDownload from './PlayerDownload'
import PlayerAbout from './PlayerAbout'

/**
 * 玩家自助门户入口：未登录显示登录页，已登录进入带侧边栏的多页布局。
 * 会话令牌在 HttpOnly cookie 中，JS 无法直接读取，故登录态由 /api/player/me 探测得出。
 * 独立于管理端 `X-Admin-Key` 鉴权，仅凭玩家 JWT 访问。
 */
export default function PlayerPortal() {
  const [authed, setAuthed] = useState<boolean | null>(null)

  useEffect(() => {
    let alive = true
    api.player
      .me()
      .then(() => alive && setAuthed(true))
      .catch(() => alive && setAuthed(false))
    return () => {
      alive = false
    }
  }, [])

  if (authed === null) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '60vh' }}>
        <Spin />
      </div>
    )
  }

  return authed ? (
    <PlayerLayout>
      <Routes>
        <Route path="/portal" element={<PlayerOverview />} />
        <Route path="/portal/protection" element={<PlayerProtection />} />
        <Route path="/portal/monitor" element={<PlayerMonitor />} />
        <Route path="/portal/tournament" element={<PlayerTournament />} />
        <Route path="/portal/maps" element={<PlayerMapPools />} />
        <Route path="/portal/maps/bp/:bpId" element={<PlayerMapBp />} />
        <Route path="/portal/stream-live" element={<PlayerStreamLive />} />
        <Route path="/portal/records" element={<PlayerRecords />} />
        <Route path="/portal/redscreen/:id" element={<PlayerRedScreenDetail />} />
        <Route path="/portal/devices" element={<PlayerDevices />} />
        <Route path="/portal/appeals" element={<PlayerAppeals />} />
        <Route path="/portal/appeals/:id" element={<PlayerAppealDetail />} />
        <Route path="/portal/tickets" element={<PlayerTickets />} />
        <Route path="/portal/tickets/:id" element={<PlayerTicketDetail />} />
        <Route path="/portal/notifications" element={<PlayerNotifications />} />
        <Route path="/portal/security" element={<PlayerSecurity />} />
        <Route path="/portal/settings" element={<PlayerSettings />} />
        <Route path="/portal/diagnostics" element={<PlayerDiagnostics />} />
        <Route path="/portal/reputation" element={<PlayerReputation />} />
        <Route path="/portal/help" element={<PlayerHelp />} />
        <Route path="/portal/download" element={<PlayerDownload />} />
        <Route path="/portal/about" element={<PlayerAbout />} />
        <Route path="*" element={<Navigate to="/portal" replace />} />
      </Routes>
    </PlayerLayout>
  ) : (
    <Routes>
      <Route path="/portal/register" element={<PlayerRegister />} />
      <Route path="/portal/forget" element={<PlayerForget />} />
      <Route path="/portal/login" element={<PlayerLogin />} />
      <Route path="*" element={<PlayerLogin />} />
    </Routes>
  )
}