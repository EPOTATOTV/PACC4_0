import Capacitor
import LocalAuthentication
import Security

/// 生物识别 + Keychain 凭证安全存储插件。
/// 对应设计文档 §4.3.2 iOS：生物识别（Face ID / Touch ID）+ Keychain 安全存储
/// （对齐 Android 端 PaccBiometricPlugin 的方法签名，保证前端 mobileBridge 双端一致）。
@objc(PaccBiometricPlugin)
public class PaccBiometricPlugin: CAPPlugin {

    // MARK: - 生物识别可用性

    @objc func isAvailable(_ call: CAPPluginCall) {
        var result: Bool = false
        var errorReason: String? = nil
        let ctx = LAContext()
        if ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil) {
            result = true
        } else if #available(iOS 17.0, *) {
            // 降级：无法用生物识别时，判断是否有任意身份验证（含设备密码）
            result = ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
        }
        call.resolve([
            "available": result,
            "error": errorReason ?? ""
        ])
    }

    // MARK: - 生物识别认证

    @objc func auth(_ call: CAPPluginCall) {
        let reason = call.getString("reason") ?? "请完成生物识别验证"
        let context = LAContext()
        var authError: NSError?

        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &authError) else {
            call.reject("设备不支持身份验证: \(authError?.localizedDescription ?? "unknown")")
            return
        }

        context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { [weak self] success, error in
            DispatchQueue.main.async {
                if success {
                    call.resolve(["success": true])
                } else {
                    call.reject("身份验证失败: \(error?.localizedDescription ?? "cancelled")")
                }
            }
        }
    }

    // MARK: - Keychain 凭证存储（微 文档 §7.3）

    @objc func keychainSet(_ call: CAPPluginCall) {
        guard let key = call.getString("key"), let value = call.getString("value") else {
            call.reject("key/value 不能为空")
            return
        }
        let data = Data(value.utf8)
        let status = addOrUpdate(service: "PTEID-PACC", account: key, data: data)
        if status == errSecSuccess {
            call.resolve()
        } else {
            call.reject("写入 Keychain 失败: \(status)")
        }
    }

    @objc func keychainGet(_ call: CAPPluginCall) {
        guard let key = call.getString("key") else {
            call.reject("key 不能为空")
            return
        }
        guard let data = read(service: "PTEID-PACC", account: key) else {
            call.reject("未找到该凭证")
            return
        }
        call.resolve(["value": String(decoding: data, as: UTF8.self)])
    }

    // MARK: - Keychain 基础操作

    private func addOrUpdate(service: String, account: String, data: Data) -> OSStatus {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        // 先尝试删除旧值再写入，简化 upsert
        SecItemDelete(query as CFDictionary)
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        return SecItemAdd(attributes as CFDictionary, nil)
    }

    private func read(service: String, account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return data
    }
}