import { Navigate, Route, Routes } from 'react-router-dom'
import { storedPlayerToken } from '../../api/client'
import PlayerLogin from './PlayerLogin'
import PlayerLayout from './PlayerLayout'
import PlayerOverview from './PlayerOverview'
import PlayerRecords from './PlayerRecords'
import PlayerAppeals from './PlayerAppeals'
import PlayerTickets from './PlayerTickets'
import PlayerDevices from './PlayerDevices'
import PlayerTournament from './PlayerTournament'

/**
 * 玩家自助门户入口：未登录显示登录页，已登录进入带侧边栏的多页布局。
 * 独立于管理端 `X-Admin-Key` 鉴权，仅凭玩家 JWT 访问。
 */
export default function PlayerPortal() {
  return storedPlayerToken() === '' ? (
    <Routes>
      <Route path="*" element={<PlayerLogin />} />
    </Routes>
  ) : (
    <PlayerLayout>
      <Routes>
        <Route path="/portal" element={<PlayerOverview />} />
        <Route path="/portal/tournament" element={<PlayerTournament />} />
        <Route path="/portal/records" element={<PlayerRecords />} />
        <Route path="/portal/devices" element={<PlayerDevices />} />
        <Route path="/portal/appeals" element={<PlayerAppeals />} />
        <Route path="/portal/tickets" element={<PlayerTickets />} />
        <Route path="*" element={<Navigate to="/portal" replace />} />
      </Routes>
    </PlayerLayout>
  )
}