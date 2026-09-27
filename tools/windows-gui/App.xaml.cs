using System.Windows;

namespace PaccManager;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        // 只读一次配置取各安全模块的模式；配置读取整体包 try/catch 绝不致启动崩溃
        Services.ConfigManager? cfg = null;
        try
        {
            cfg = new Services.ConfigManager();
            cfg.Load();
        }
        catch
        {
            // 配置读取失败：各模块按默认 warn 自行兜底
            cfg = null;
        }

        // 反调试守卫：低分静默、越阈值才按模式告警/退出
        try { Services.DebugGuard.DetectAndDisrupt(cfg?.Get("pacc.security.antidebug")); }
        catch { /* 守卫自身异常不影响启动 */ }

        // 反 Hook 检测：默认只记录 + 告警，绝不阻断正常用户
        try { Services.HookDetector.DetectAndReport(cfg?.Get("pacc.security.antihook")); }
        catch { /* 检测失败降级为不告警 */ }

        // 进程保护：提权 / 收紧 DACL / 缓解策略，失败静默降级
        try { Services.ProcessProtector.Apply(cfg?.Get("pacc.security.process-protect")); }
        catch { /* 保护动作失败不影响启动 */ }

        // 崩溃守护：未处理异常落盘，便于事后定位
        try
        {
            Services.ProcessProtector.InstallCrashGuard();
            DispatcherUnhandledException += OnDispatcherUnhandledException;
        }
        catch { /* 挂钩失败不影响启动 */ }

        base.OnStartup(e);
    }

    /// <summary>
    /// 界面线程未处理异常：先落盘再放行。
    /// 刻意不设 Handled = true——吞掉异常会让窗口停在一个半坏的状态却毫无提示，
    /// 用户反而以为操作成功了。照常崩溃 + 留下日志比静默续跑更安全。
    /// </summary>
    private void OnDispatcherUnhandledException(object sender,
        System.Windows.Threading.DispatcherUnhandledExceptionEventArgs e)
    {
        try { Services.ProcessProtector.LogCrash(e.Exception, "DispatcherUnhandledException"); }
        catch { /* 日志失败忽略 */ }
    }
}