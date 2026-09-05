/**
 * B2 远程查端 · WebRTC 信令模块（纯前端，复用现有 /ws/ptv ↔ /ws/admin 信令通道）。
 *
 * 分两个角色，信令全部经 {code SignalTransport} 进出，与具体 WebSocket 解耦：
 *  - 玩家端（Tauri WebView）：getDisplayMedia 采集 → createScreenShare
 *  - 管理端（浏览器控制台）：acceptScreenShare 接收并显示
 *
 * 信令消息载荷沿用查端协议：{ type: 'inspect_offer|inspect_answer|inspect_ice', sessionId, sdp|candidate }
 */

export interface SignalTransport {
  send(payload: { type: string; sessionId: string; [k: string]: unknown }): void
}

export const RTC_CONFIG: RTCConfiguration = { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] }

/** 玩家端：采集本屏并作为主叫发起 WebRTC，信令经 transport 发出。 */
export function createScreenShare(transport: SignalTransport, sessionId: string) {
  let pc: RTCPeerConnection | null = null
  let stream: MediaStream | null = null

  /** 处理管理端回传的信令（answer / ice）。 */
  function onSignal(msg: Record<string, unknown>): void {
    if (!pc) return
    if (msg.type === 'inspect_answer' && typeof msg.sdp === 'string') {
      void pc.setRemoteDescription({ type: 'answer', sdp: msg.sdp }).catch(console.error)
    } else if (msg.type === 'inspect_ice' && msg.candidate) {
      void pc.addIceCandidate(msg.candidate as RTCIceCandidateInit).catch(console.error)
    }
  }

  async function start(): Promise<MediaStream | null> {
    if (pc) return stream
    stream = await navigator.mediaDevices.getDisplayMedia({
      video: true,
      audio: false,
    })
    pc = new RTCPeerConnection(RTC_CONFIG)
    for (const track of stream.getTracks()) pc.addTrack(track, stream)
    pc.onicecandidate = (e) => {
      if (e.candidate) {
        transport.send({ type: 'inspect_ice', sessionId, candidate: e.candidate.toJSON() })
      }
    }
    pc.onnegotiationneeded = async () => {
      if (!pc) return
      try {
        const offer = await pc.createOffer()
        await pc.setLocalDescription(offer)
        transport.send({ type: 'inspect_offer', sessionId, sdp: offer.sdp ?? '' })
      } catch (err) {
        console.error('创建 offer 失败', err)
      }
    }
    return stream
  }

  async function stop(): Promise<void> {
    stream?.getTracks().forEach((t) => t.stop())
    pc?.close()
    pc = null
    stream = null
  }

  return { start, stop, onSignal }
}

/** 管理端：作为被叫接收玩家端的屏幕共享，渲染到 video 元素。 */
export function acceptScreenShare(transport: SignalTransport, sessionId: string, video: HTMLVideoElement) {
  let pc: RTCPeerConnection | null = null

  function ensurePeer(): RTCPeerConnection | null {
    if (pc) return pc
    pc = new RTCPeerConnection(RTC_CONFIG)
    pc.ontrack = (e) => {
      video.srcObject = e.streams[0] ?? null
      void video.play().catch(() => {})
    }
    pc.onicecandidate = (e) => {
      if (e.candidate) {
        transport.send({ type: 'inspect_ice', sessionId, candidate: e.candidate.toJSON() })
      }
    }
    return pc
  }

  async function onSignal(msg: Record<string, unknown>): Promise<void> {
    const pc2 = ensurePeer()
    if (!pc2) return
    if (msg.type === 'inspect_offer' && typeof msg.sdp === 'string') {
      await pc2.setRemoteDescription({ type: 'offer', sdp: msg.sdp })
      const answer = await pc2.createAnswer()
      await pc2.setLocalDescription(answer)
      transport.send({ type: 'inspect_answer', sessionId, sdp: answer.sdp ?? '' })
    } else if (msg.type === 'inspect_ice' && msg.candidate) {
      await pc2.addIceCandidate(msg.candidate as RTCIceCandidateInit).catch(console.error)
    }
  }

  function stop(): void {
    pc?.close()
    pc = null
    video.srcObject = null
  }

  return { onSignal, stop }
}