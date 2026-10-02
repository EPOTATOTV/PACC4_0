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

/// <summary>
/// 带 try/catch/finally 的类型。字符串加密会改变方法体长度，异常表的边界必须跟着修正，
/// 否则加密后一进 catch 就跳到垃圾偏移上。
/// </summary>
public static class Guarded
{
    public static int Done;

    public static string Run(bool fail)
    {
        try
        {
            if (fail)
            {
                throw new InvalidOperationException("guard-fail-token");
            }
            return "guard-ok-token";
        }
        catch (InvalidOperationException ex)
        {
            return "guard-caught:" + ex.Message;
        }
        finally
        {
            Done++;
        }
    }
}

/// <summary>
/// 适合控制流平坦化的方法：有循环与分支、无异常区间，每个基本块入口都是空栈。
/// </summary>
public static class Flattened
{
    /// <summary>for + continue + break：基本块多、栈深简单。</summary>
    public static int Sum(int[] values, int limit)
    {
        int total = 0;
        for (int i = 0; i < values.Length; i++)
        {
            if (values[i] < 0)
            {
                continue;
            }
            if (values[i] > limit)
            {
                break;
            }
            total += values[i];
        }
        return total;
    }

    /// <summary>while + 累加：没有提前退出，用作第二个可平坦化的样本。</summary>
    public static int Count(int n)
    {
        int hits = 0;
        while (n > 0)
        {
            if (n % 2 == 0)
            {
                hits++;
            }
            n--;
        }
        return hits;
    }

    /// <summary>
    /// 三元表达式把值压在栈上带过分支，汇合块入口栈非空——状态机表达不了，必须被跳过。
    /// </summary>
    public static string Pick(int n) => n > 0 ? "positive" : "non-positive";
}

/// <summary>
/// const 字面量存在 Constant 表、不在 #US 堆，ldstr 那一轮扫不到它。用法在编译期已内联成 ldstr，
/// 运行期没人再读这个值，所以常量值本身也必须一并加密，否则不用反射也能在反编译里看到明文。
/// </summary>
public static class ConstantHolder
{
    public const string Endpoint = "https://dl.potatotv.asia/files/version.json";

    public static string Read() => Endpoint;
}

/// <summary>
/// 模拟 WPF 的 App.OnStartup：反调试注入会在它开头插一条 <c>__AntiDebug.Start()</c>。
/// 原来带 try/catch，顺带验证「前置插指令后异常边界跟着平移」。
/// </summary>
public static class Startup
{
    public static int Ran;

    public static string OnStartup()
    {
        try
        {
            Ran++;
            return "startup-ok";
        }
        catch (Exception)
        {
            return "startup-failed";
        }
    }
}