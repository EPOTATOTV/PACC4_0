import Capacitor
import Foundation
import Darwin

/// 设备安全检测插件（iOS）。
/// 对应设计文档 §4.3.2「设备安全检测」越狱检测（文件系统/动态库/沙箱）、
/// 调试器检测（sysctl）与篡改检测（代码签名校验）。
/// 命名对齐 Android 语义，供前端 mobileBridge 统一调用「设备安全」能力。
@objc(PaccSecurity)
public class PaccSecurityPlugin: CAPPlugin {

    @objc func check(_ call: CAPPluginCall) {
        call.resolve([
            "jailbroken": isJailbroken(),
            "debugger": isDebuggerAttached(),
            "simulator": isSimulator(),
            "tampered": isAppTampered()
        ])
    }

    // MARK: - 越狱检测

    private func isJailbroken() -> Bool {
        // 1) 常见越狱工具/路径
        if FileManager.default.fileExists(atPath: "/Applications/Cydia.app")
            || FileManager.default.fileExists(atPath: "/Applications/Sileo.app")
            || FileManager.default.fileExists(atPath: "/Library/MobileSubstrate/MobileSubstrate.dylib")
            || FileManager.default.fileExists(atPath: "/usr/sbin/sshd")
            || FileManager.default.fileExists(atPath: "/usr/bin/ssh")
            || FileManager.default.fileExists(atPath: "/usr/libexec/ssh-keysign")
            || FileManager.default.fileExists(atPath: "/bin/bash")
            || FileManager.default.fileExists(atPath: "/etc/apt") {
            return true
        }

        // 2) 检测 Cydia 包/坏区路径写入
        let testPath = NSSearchPathForDirectoriesInDomains(.applicationSupportDirectory, .userDomainMask, true).first ?? ""
        let jailbreakCheckPath = testPath + "/../../Library/Preferences/Current.plist"
        if FileManager.default.fileExists(atPath: jailbreakCheckPath) {
            do {
                try "test".write(toFile: jailbreakCheckPath, atomically: true, encoding: .utf8)
                return true // 能写系统目录 → 越狱
            } catch {
                // 写失败 → 正常
            }
        }
        return false
    }

    // MARK: - 调试器检测（sysctl P_TRACED）

    private func isDebuggerAttached() -> Bool {
        var info = kinfo_proc()
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        var size = MemoryLayout<kinfo_proc>.size
        let result = sysctl(&mib, u_int(mib.count), &info, &size, nil, 0)
        if result != 0 { return false }
        return (info.kp_proc.p_flag & P_TRACED) != 0
    }

    // MARK: - 模拟器

    private func isSimulator() -> Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    // MARK: - 篡改检测（Bundle 标识符 + 签名校验占位）

    private func isAppTampered() -> Bool {
        // 生产环境应接入服务端下发的签名特征比对；此处做基础校验：
        // 已重打包的包往往修改 Bundle Identifier。基础位检查如下。
        let expected = "asia.potatotv.pacc.mobile"
        guard let bundleId = Bundle.main.bundleIdentifier else { return true }
        return bundleId != expected
    }
}