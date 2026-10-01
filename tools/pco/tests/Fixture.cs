using System;

namespace Pco.Tests.Fixture;

/// <summary>被 keep 规则整体保留：类型名、命名空间、全部成员名都不允许变（对应 WPF 的 App / MainWindow）。</summary>
public sealed class Kept
{
    private int _hits;

    public string KeptProperty { get; set; } = "kept";

    public event EventHandler? KeptEvent;

    public string Describe()
    {
        KeptEvent?.Invoke(this, EventArgs.Empty);
        return KeptProperty + ++_hits;
    }
}

/// <summary>命名空间被上面的 keep 钉住，但类型名自身与成员名应当被改掉。</summary>
public class Renamed
{
    private int _counter;

    public virtual int Score() => _counter;

    public string Bump() => Endpoint() + ++_counter;

    private static string Endpoint() => "https://api.potatotv.asia/v1/report";

    /// <summary>嵌套类跟着外层一起改名。</summary>
    public class Nested
    {
        public int Value() => 42;
    }
}

/// <summary>覆写按名字绑定（虚表），<c>Score</c> 必须保名，否则程序集加载即抛 TypeLoadException。</summary>
public sealed class RenamedChild : Renamed
{
    public override int Score() => base.Score() + 1;
}

/// <summary>枚举成员是 Literal 字段，名字要与落盘数据对得上，必须保名。</summary>
public enum Mode
{
    Off = 0,
    Warn = 1,
    Enforce = 2,
}

/// <summary>P/Invoke：方法名与入口名共用同一个字符串，两者都不能动。</summary>
internal static class NativeProbe
{
    [System.Runtime.InteropServices.DllImport("kernel32.dll", EntryPoint = "GetCurrentProcessId")]
    internal static extern uint CurrentProcessId();

    [System.Runtime.InteropServices.DllImport("kernel32.dll")]
    internal static extern IntPtr GetModuleHandleW(string name);
}