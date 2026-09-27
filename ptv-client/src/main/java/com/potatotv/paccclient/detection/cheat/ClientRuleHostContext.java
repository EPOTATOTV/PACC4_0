package com.potatotv.paccclient.detection.cheat;

import com.potatotv.prl.runtime.PrlSecurityException;
import com.potatotv.prl.runtime.PrlValues;
import com.potatotv.prl.sandbox.PrlHostContext;
import com.potatotv.prl.stdlib.PrlStdlib;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 玩家端规则宿主上下文（设计文档 §3.2.2 的客户端 HostContext）。
 *
 * <p>规则能拿到的只有两样东西：特征向量（{@code input} 块里的 {@code features} 映射）与分析上下文
 * 的标量（{@code temporal_anomaly} / {@code click_highly_likely}）。这两样都是端侧本机采集的，
 * 符合「检测数据只来自玩家设备本机」这条约束 —— 规则不可能从这里伸手去够服务器或文件系统。</p>
 *
 * <p>白名单必须把标准库一并列进来，理由与后端宿主相同：{@code TypeChecker} 把
 * {@code getAvailableFunctions()} 当成允许调用的函数全集，而 {@code PrlVm} 运行期先查宿主白名单、
 * 再查标准库。声明了标准库名字之后标准库调用会先落到这里，所以 {@link #callFunction} 要自己转交。</p>
 *
 * <p>显式转换（{@code to_float}/{@code to_int}/{@code to_string}）只在签名表里、标准库没有实现，
 * 由宿主补上。</p>
 */
public final class ClientRuleHostContext implements PrlHostContext {

    /** 宿主自己接管、标准库不提供的函数。 */
    private static final Set<String> HOST_FUNCTIONS = Set.of("to_float", "to_int", "to_string");

    /** 规则可调用的函数全集 = 标准库 + 宿主接管。 */
    private static final Set<String> AVAILABLE_FUNCTIONS = availableFunctions();

    private final PrlStdlib stdlib = new PrlStdlib(this);

    private static Set<String> availableFunctions() {
        Set<String> names = new LinkedHashSet<>(PrlStdlib.functionNames());
        names.addAll(HOST_FUNCTIONS);
        return Set.copyOf(names);
    }

    @Override
    public Set<String> getAvailableFunctions() {
        return AVAILABLE_FUNCTIONS;
    }

    @Override
    public Object callFunction(String name, Object[] args) {
        switch (name) {
            case "to_float":
                return toFloat(args[0]);
            case "to_int":
                return toLong(args[0]);
            case "to_string":
                return PrlValues.display(args[0]);
            default:
                break;
        }
        if (PrlStdlib.supports(name)) {
            return stdlib.call(name, args);
        }
        throw new PrlSecurityException("玩家端宿主没有注册函数 '" + name + "'");
    }

    /** 规则里的 {@code log(...)} 走 stderr，与玩家端其余日志同一个去向。 */
    @Override
    public void log(String level, String message) {
        System.err.println("[PTV-PRL] [" + (level == null ? "info" : level) + "] " + message);
    }

    private static double toFloat(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new PrlSecurityException("to_float 期望数字，实际为 " + PrlValues.describe(value));
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new PrlSecurityException("to_int 期望数字，实际为 " + PrlValues.describe(value));
    }
}