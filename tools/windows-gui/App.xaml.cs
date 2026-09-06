using System.Windows;

namespace PaccManager;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        // 反调试守卫：只读一次配置取模式，包 try/catch 绝不致启动崩溃
        try
        {
            var cfg = new Services.ConfigManager();
            string? mode = cfg.Load() ? cfg.Get("pacc.security.antidebug") : null;
            Services.DebugGuard.DetectAndDisrupt(mode);
        }
        catch
        {
            // 配置读取失败按默认 warn 由守卫自行兜底
        }
        base.OnStartup(e);
    }
}