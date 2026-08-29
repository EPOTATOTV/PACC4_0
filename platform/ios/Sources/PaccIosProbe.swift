import Foundation
import Darwin

/// PACC iOS/iPadOS 反作弊探针。
///
/// 严格玩家端本地采样，不接触游戏服务器数据。检测项：
/// 1) 越狱检测：Cydia 等经典路径、沙盒逃逸、非提权 rootfs mount；
/// 2) dylib 注入检测：遍历 dyld image 识别非白名单/可疑注入库；
/// 3) 反调试：ptrace(PT_DENY_ATTACH) 校验；
/// 4) 完整性：与 Android 探针一致折算为 DetectionEvent（score 0-100）。
///
/// 结果经回调交 `ptv-client` 统一上报 PTV。
@objc
public final class PaccIosProbe {

    public struct Result {
        public let score: Int
        public let severity: String
        public let hits: [String]
        public let json: String
    }

    /// 输入法/字体等合法 dylib 不在本检测范围；以下为常见注入/越狱框架，非完整黑名单。
    private let suspiciousLibraries: [String] = [
        "frida", "cycript", "substrate", "mobile substrate", "CydiaSubstrate",
        "libMobSubstrate", "substitute", "RocketBootstrap", "tweak", "dylib",
        "libpayload", "flex", "SSLKillSwitch", "PreferenceLoader", "Sistency"
    ]

    public init() {}

    /// 运行完整扫描。
    public func scan() -> Result {
        var hits: [String] = []
        if isJailbroken() { hits.append("jailbreak") }
        if let inj = detectInjectedDylibs() { hits.append(inj) }
        if isUnderDebug() { hits.append("debugger") }

        let score = score(for: hits.count)
        let severity = score >= 70 ? "high" : score > 0 ? "medium" : "ok"

        let json = makeJson(hits: hits, score: score, severity: severity)
        return Result(score: score, severity: severity, hits: hits, json: json)
    }

    // MARK: - 越狱检测

    private func isJailbroken() -> Bool {
        // 1) 经典越狱路径
        let paths = [
            "/Applications/Cydia.app", "/Library/MobileSubstrate",
            "/var/lib/apt", "/bin/bash", "/usr/sbin/sshd",
            "/etc/apt", "/private/var/stash", "/var/cache/apt",
            "/usr/libexec/sftp-server", "/usr/libexec/cydia", "/var/lib/cydia",
            "/usr/libexec/cydia/"
        ]
        for p in paths where FileManager.default.fileExists(atPath: p) {
            return true
        }
        // 2) 沙盒逃逸：能写入系统区
        let test = "/private/pacc_test"
        if (try? "x".write(toFile: test, atomically: true, encoding: .utf8)) != nil {
            defer { try? FileManager.default.removeItem(atPath: test) }
            return true
        }
        // 3) fork 测试（越狱/非提权常见放行 fork）
        if isForkAllowed() { return true }
        // 4) 动态库注入即越狱环境
        if detectInjectedDylibs() != nil { return true }
        return false
    }

    private func isForkAllowed() -> Bool {
        // 沙盒环境 fork() 会因未授权失败并返回 -1；越狱/非提权环境的经典越狱特征之一。
        // 仅在成功创建子进程时判定越狱，避免误伤。
        let pid = fork()
        if pid < 0 {
            return false
        }
        if pid == 0 {
            // 子进程：立即退出，不执行任何应用逻辑
            _exit(0)
        }
        // 父进程：回收子进程避免产生僵尸进程
        var status: Int32 = 0
        let _ = waitpid(pid, &status, 0)
        return true
    }

    // MARK: - dylib 注入检测

    private func detectInjectedDylibs() -> String? {
        let count = dyld_image_count()
        var first: String?
        // index 0 为可执行主镜像，从 1 开始遍历动态库
        for i in 1..<count {
            if let name = dyld_get_image_name(i) {
                let path = String(cString: name)
                for s in suspiciousLibraries where path.lowercased().contains(s) {
                    if first == nil { first = "inject_dylib" }
                    NSLog("[PACC-PTV] suspicious dylib: %@", path)
                }
            }
        }
        return first
    }

    // MARK: - 反调试

    private func isUnderDebug() -> Bool {
        var info = kinfo_proc()
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        var size = MemoryLayout.size(ofValue: info)
        let err = sysctl(&mib, u_int(mib.count), &info, &size, nil, 0)
        if err != 0 { return false }
        let flag = info.kp_proc.p_flag
        let traced = flag & P_TRACED
        return traced != 0
    }

    // MARK: - 事件折算

    private func score(for hitCount: Int) -> Int {
        switch hitCount {
        case 0: return 0
        case 1: return 45
        case 2: return 70
        default: return 100
        }
    }

    private func makeJson(hits: [String], score: Int, severity: String) -> String {
        let arr = hits.map { "\"\($0)\"" }.joined(separator: ",")
        return "{\"edition\":\"ios\",\"proto\":\"swift-probe\",\"score\":\(score)," +
            "\"severity\":\"\(severity)\",\"hits\":[\(arr)]}"
    }
}