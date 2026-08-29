package com.potatotv.paccclient.inspect;

/**
 * 远程查端代理（管理员取证侧）。
 * <p>说明：真实实现基于 WebRTC（DataChannel 屏幕 / 内存 / 日志取证片段）+ mTLS 通道，
 * 由 PTV 服务器下发会话令牌，玩家端仅在红屏锁定时允许建立。此处为握手协议骨架。</p>
 */
public final class InspectAgent {

    /** PTV 下发的待处理会话令牌。 */
    private volatile String pendingSession = null;

    public void onInspectRequest(String sessionId) {
        this.pendingSession = sessionId;
        System.out.println("[PTV-Client] 收到查端请求，会话=" + sessionId
                + "（等待管理员开始后建立受控取证通道）");
    }

    /** 建立取证数据通道（mock：仅返回握手状态）。 */
    public boolean establish(String sessionId) {
        if (pendingSession != null && pendingSession.equals(sessionId)) {
            System.out.println("[PTV-Client] 查端取证通道已建立 (WebRTC + mTLS 骨架)");
            return true;
        }
        return false;
    }

    public void close() {
        System.out.println("[PTV-Client] 查端取证通道已关闭");
    }
}