import { Suspense, lazy, useEffect, useState } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { Spin } from 'antd'
import { api } from '../../api/client'
import PlayerLayout from './PlayerLayout'

// 门户内页同样按需加载，未登录分支不必下载登录后的全部页面
const PlayerLogin = lazy(() => import('./PlayerLogin'))
const PlayerRegister = lazy(() => import('./PlayerRegister'))
const PlayerForget = lazy(() => import('./PlayerForget'))
const PlayerOverview = lazy(() => import('./PlayerOverview'))
const PlayerRecords = lazy(() => import('./PlayerRecords'))
const PlayerAppeals = lazy(() => import('./PlayerAppeals'))
const PlayerTickets = lazy(() => import('./PlayerTickets'))
const PlayerDevices = lazy(() => import('./PlayerDevices'))
const PlayerTournament = lazy(() => import('./PlayerTournament'))
const PlayerSettings = lazy(() => import('./PlayerSettings'))
const PlayerDiagnostics = lazy(() => import('./PlayerDiagnostics'))
const PlayerProtection = lazy(() => import('./PlayerProtection'))
const PlayerMonitor = lazy(() => import('./PlayerMonitor'))
const PlayerNotifications = lazy(() => import('./PlayerNotifications'))
const PlayerRedScreenDetail = lazy(() => import('./PlayerRedScreenDetail'))
const PlayerSecurity = lazy(() => import('./PlayerSecurity'))
const PlayerAppealDetail = lazy(() => import('./PlayerAppealDetail'))
const PlayerTicketDetail = lazy(() => import('./PlayerTicketDetail'))
const PlayerStreamLive = lazy(() => import('./PlayerStreamLive'))
const PlayerMapPools = lazy(() => import('./PlayerMapPools'))
const PlayerMapBp = lazy(() => import('./PlayerMapBp'))
const PlayerReputation = lazy(() => import('./PlayerReputation'))
const PlayerHelp = lazy(() => import('./PlayerHelp'))
const PlayerDownload = lazy(() => import('./PlayerDownload'))
const PlayerAbout = lazy(() => import('./PlayerAbout'))

/** 按需加载页面时的占位，避免切换路由时白屏 */
function PageLoading() {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', minHeight: '40vh' }}>
      <Spin />
    </div>
  )
}

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

  return (
    <Suspense fallback={<PageLoading />}>
      {authed ? (
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
      )}
    </Suspense>
  )
}