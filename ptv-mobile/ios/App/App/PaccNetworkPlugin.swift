import Capacitor
import Network
import Foundation

/// 网络监控插件（iOS）。
/// 对应设计文档 §4.2.2 NetworkMonitor（iOS 用 Network.framework 读取网络状态，
/// 不做本地 VPN 建立）。通过 NWPathMonitor 判断连接类型与是否处于 VPN/代理隧道。
/// 方法签名与 Android PaccNetworkPlugin.status 对齐。
@objc(PaccNetwork)
public class PaccNetworkPlugin: CAPPlugin {

    private let monitor = NWPathMonitor()

    public override func load() {
        super.load()
        monitor.start(queue: DispatchQueue.global(qos: .background))
    }

    @objc func status(_ call: CAPPluginCall) {
        let path = monitor.currentPath
        var isVPN = false
        var isWiFi = false
        var isCellular = false

        path.availableInterfaces.forEach { interface in
            switch interface.type {
            case .wifi:
                isWiFi = true
            case .cellular:
                isCellular = true
            default:
                // 隧道/VPN 接口通常落在 .other
                if path.satisfiesRequirement(.init(interfaceType: interface.type, isExpensive: false))
                    && isLikelyVPN(interface) {
                    isVPN = true
                }
            }
        }

        call.resolve([
            "available": path.status == .satisfied,
            "vpnActive": isVPN,
            "wifi": isWiFi,
            "cellular": isCellular,
            "metered": path.isExpensive
        ])
    }

    /// 通过接口名启发式识别 VPN 隧道（utun/tap/tun）。
    private func isLikelyVPN(_ interface: NWInterface) -> Bool {
        let lower = interface.name.lowercased()
        return lower.hasPrefix("utun") || lower.hasPrefix("tap") || lower.hasPrefix("tun")
    }
}