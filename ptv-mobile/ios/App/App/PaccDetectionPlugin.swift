import Capacitor
import Foundation

/// 移动端检测桥插件（iOS）。
/// 对应设计文档 §4.1 移动端轻量本地探测。iOS 不做内核级检测，仅提供状态与占位事件。
/// 方法签名与 Android PaccDetectionPlugin 对齐（status/start/stop/detections）。
@objc(PaccDetectionPlugin)
public class PaccDetectionPlugin: CAPPlugin {

    private var running = false

    @objc func status(_ call: CAPPluginCall) {
        call.resolve([
            "running": running,
            "platform": "mobile-ios",
            "capabilities": ["full_scan": false, "foreground": true],
            "lastEventType": "",
            "redscreenActive": false
        ])
    }

    @objc func start(_ call: CAPPluginCall) {
        running = true
        call.resolve(["running": true])
    }

    @objc func stop(_ call: CAPPluginCall) {
        running = false
        call.resolve(["running": false])
    }

    @objc func detections(_ call: CAPPluginCall) {
        call.resolve(["results": []])
    }
}