import Capacitor
import Foundation

/// 推送插件（iOS APNs）。
/// 对应设计文档 §6.2.6：仅把 APNs deviceToken 中转给前端，由前端上报后端。
/// 方法签名与 Android PaccPushPlugin 对齐（registerToken/getToken）。
@objc(PaccPushPlugin)
public class PaccPushPlugin: CAPPlugin {

    private static var lastToken: String = ""

    /// 前端从原生 push 事件拿到 token 后，会回调到本方法做本地暂存。
    @objc func registerToken(_ call: CAPPluginCall) {
        guard let token = call.getString("token"), !token.isEmpty else {
            call.reject("缺少 APNs token")
            return
        }
        PaccPushPlugin.lastToken = token
        call.resolve(["registered": true, "token": token])
    }

    @objc func getToken(_ call: CAPPluginCall) {
        call.resolve(["token": PaccPushPlugin.lastToken])
    }

    /// 供 AppDelegate 注入 APNs token。
    static func capture(token: String) {
        lastToken = token
    }
}