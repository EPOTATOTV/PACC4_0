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
        <Route path="/portal/tournament" element={<PlayerTournament />} />
        <Route path="/portal/records" element={<PlayerRecords />} />
        <Route path="/portal/devices" element={<PlayerDevices />} />
        <Route path="/portal/appeals" element={<PlayerAppeals />} />
        <Route path="/portal/tickets" element={<PlayerTickets />} />
        <Route path="/portal/settings" element={<PlayerSettings />} />
        <Route path="/portal/diagnostics" element={<PlayerDiagnostics />} />
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