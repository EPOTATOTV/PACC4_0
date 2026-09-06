using System.Runtime.InteropServices;

namespace PaccManager.Services;

/// <summary>
/// 反调试守卫：启动时检测是否有调试器附着，按模式告警/退出（默认仅提示，绝不阻断正常用户）。
/// 模式来源：环境变量 PACC_ANTIDEBUG → 配置键 pacc.security.antidebug → 默认 "warn"。
/// warn=提示并继续；exit=提示后退出；off=完全禁用。
/// </summary>
public static class DebugGuard
{
    [DllImport("kernel32.dll")]
    private static extern bool IsDebuggerPresent();

    private const string EnvVar = "PACC_ANTIDEBUG";
    private const string DefaultMode = "warn";

    /// <summary>
    /// 检测并（按需）处置调试附着。无调试器时恒返回 false，零影响；有调试器时按模式返回 true（提示/退出）。
    /// </summary>
    public static bool DetectAndDisrupt(string? configValue)
    {
        if (!System.Diagnostics.Debugger.IsAttached && !IsDebuggerPresent())
            return false;

        string mode = Environment.GetEnvironmentVariable(EnvVar)
                      ?? configValue
                      ?? DefaultMode;

        if (mode.Equals("off", StringComparison.OrdinalIgnoreCase))
            return false;

        string msg = "检测到调试器（debugger）附着。本程序受保护，请在未调试的环境下运行。";
        if (mode.Equals("exit", StringComparison.OrdinalIgnoreCase))
        {
            System.Windows.MessageBox.Show(msg, "PACC 受保护", System.Windows.MessageBoxButton.OK,
                System.Windows.MessageBoxImage.Warning);
            System.Environment.Exit(-1);
            return true;
        }

        // 默认 warn：仅提示、继续运行
        System.Windows.MessageBox.Show(msg + "\n\n本次运行将继续。", "PACC 受保护",
            System.Windows.MessageBoxButton.OK, System.Windows.MessageBoxImage.Warning);
        return true;
    }
}