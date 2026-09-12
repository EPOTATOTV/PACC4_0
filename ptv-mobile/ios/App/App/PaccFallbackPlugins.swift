import Capacitor
import Foundation

// 本文件为 iOS 受限/不可用能力的降级实现。
// 设计文档 §4.2.2 中「无障碍监控 / 使用统计 / App 扫描 / USB 检测 / 开机自启」为 Android
// 特有能力，iOS 系统权限不开放对应接口。为保证双端同一套前端代码（mobileBridge.ts）
// 在 iOS 一致运行且不抛「插件不可用」，这里提供同名插件，返回安全的降级结果
// （能力不可用 / 空数据），并明确 supported=false 供前端隐藏对应 UI。

/// 无障碍输入监控（Android 特有）→ iOS 降级。
@objc(PaccAccessibilityMonitorPlugin)
public class PaccAccessibilityMonitorPlugin: CAPPlugin {
    @objc func isEnabled(_ call: CAPPluginCall) {
        call.resolve(["supported": false, "enabled": false])
    }
    @objc func events(_ call: CAPPluginCall) {
        call.resolve(["results": []])
    }
    @objc func clear(_ call: CAPPluginCall) {
        call.resolve()
    }
}

/// 使用统计（Android UsageStatsManager）→ iOS 降级。
@objc(PaccUsageStatsPlugin)
public class PaccUsageStatsPlugin: CAPPlugin {
    @objc func hasPermission(_ call: CAPPluginCall) {
        call.resolve(["supported": false, "granted": false])
    }
    @objc func apps(_ call: CAPPluginCall) {
        call.resolve(["supported": false, "results": []])
    }
}

/// App 扫描黑名单（Android）→ iOS 降级（iOS 本身应用沙盒，无需扫描）。
@objc(PaccAppScannerPlugin)
public class PaccAppScannerPlugin: CAPPlugin {
    @objc func scan(_ call: CAPPluginCall) {
        call.resolve(["supported": false, "results": [], "total": 0, "blacklistCount": 0])
    }
}

/// USB 设备监控（Android）→ iOS 降级（Lightning/USB-C 无 accessory 枚举能力）。
@objc(PaccUsbMonitorPlugin)
public class PaccUsbMonitorPlugin: CAPPlugin {
    @objc func devices(_ call: CAPPluginCall) {
        call.resolve(["supported": false, "devices": []])
    }
}

/// 开机自启 / 省电白名单（Android）→ iOS 降级。
@objc(PaccAutoStartPlugin)
public class PaccAutoStartPlugin: CAPPlugin {
    @objc func isIgnoringBatteryOptimizations(_ call: CAPPluginCall) {
        call.resolve(["supported": false, "ignoring": false])
    }
    @objc func requestIgnoreBatteryOptimizations(_ call: CAPPluginCall) {
        call.reject("iOS 不支持电池优化白名单申请")
    }
}