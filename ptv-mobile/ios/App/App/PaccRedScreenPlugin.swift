import Capacitor
import UIKit
import UserNotifications

/// 移动端红屏警告（iOS 实现）。
/// 对应设计文档 §6.2.2 iOS：全屏 ViewController + 本地通知 + 引导式访问限制退出。
/// 方法签名与 Android 端 PaccRedScreenPlugin 对齐（show/dismiss）。
@objc(PaccRedScreenPlugin)
public class PaccRedScreenPlugin: CAPPlugin {

    // 红屏使用独立 UIWindow 全屏覆盖，处理 modal 层级受限时仍能置顶
    private var redWindow: UIWindow?

    @objc func show(_ call: CAPPluginCall) {
        let level = min(max(call.getInt("level", 2), 1), 4)
        let reason = call.getString("reason") ?? "检测到异常行为，已暂停活动"

        // 主线程创建红屏窗口
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.presentRedScreen(level: level, reason: reason)
            // 附加本地通知（红屏事件告警）
            PaccRedScreenPlugin.postLocalNotification(level: level, reason: reason)
            call.resolve()
        }
    }

    @objc func dismiss(_ call: CAPPluginCall) {
        DispatchQueue.main.async { [weak self] in
            self?.dismissRedScreen()
            call.resolve()
        }
    }

    // MARK: - 红屏窗口

    private func presentRedScreen(level: Int, reason: String) {
        guard let scene = UIApplication.shared.connectedScenes
                .compactMap({ $0 as? UIWindowScene })
                .first(where: { $0.activationState == .foregroundActive }) else {
            return
        }

        setupRedScreenWindowOnScene(scene, level: level, reason: reason)
    }

    private func setupRedScreenWindowOnScene(_ scene: UIWindowScene, level: Int, reason: String) {
        let window = UIWindow(windowScene: scene)
        window.windowLevel = .alert + 1
        window.rootViewController = PaccRedScreenViewController(level: level, reason: reason)
        window.makeKeyAndVisible()
        redWindow = window
    }

    private func dismissRedScreen() {
        redWindow?.isHidden = true
        redWindow = nil
    }

    // MARK: - 本地通知

    private static func postLocalNotification(level: Int, reason: String) {
        let content = UNMutableNotificationContent()
        content.title = "PACC 风险预警"
        content.body = "\(reason)（级别 \(level)）"
        content.sound = .default
        let request = UNNotificationRequest(
            identifier: "pacc-redscreen-\(Int(Date().timeIntervalSince1970))",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request, withCompletionHandler: nil)
    }
}

/// 红屏全屏 ViewController：红色背景 + 层级文案 + 禁用返回。
/// 引导式访问（Guided Access）需系统层面开启，App 内仅做提示，符合 iOS 权限边界。
final class PaccRedScreenViewController: UIViewController {

    private let level: Int
    private let reason: String

    init(level: Int, reason: String) {
        self.level = level
        self.reason = reason
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = #colorLiteral(red: 0.72, green: 0.05, blue: 0.05, alpha: 1)

        let stack = UIStackView()
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 24
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stack.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 32),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -32)
        ])

        let icon = UILabel()
        icon.text = "⚠️"
        icon.font = .systemFont(ofSize: 64)
        stack.addArrangedSubview(icon)

        let title = UILabel()
        title.text = "风险警告"
        title.font = .boldSystemFont(ofSize: 28)
        title.textColor = .white
        title.textAlignment = .center
        stack.addArrangedSubview(title)

        let body = UILabel()
        body.text = reason
        body.font = .systemFont(ofSize: 17)
        body.textColor = .white
        body.textAlignment = .center
        body.numberOfLines = 0
        stack.addArrangedSubview(body)

        let badge = UILabel()
        badge.text = "级别 \(level) · 请联系管理员"
        badge.font = .systemFont(ofSize: 14)
        badge.textColor = UIColor.white.withAlphaComponent(0.8)
        stack.addArrangedSubview(badge)
    }
}