using System.Windows;

namespace PaccManager;

public partial class App : Application
{
    protected override void OnStartup(StartupEventArgs e)
    {
        // 若以管理员权限启动更名，确保首次运行有足够权限写入配置/日志
        base.OnStartup(e);
    }
}