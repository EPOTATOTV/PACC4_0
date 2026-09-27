package com.potatotv.pacc.rule;

import com.potatotv.prl.runtime.PrlSecurityException;
import com.potatotv.prl.runtime.PrlValues;
import com.potatotv.prl.sandbox.PrlHostContext;
import com.potatotv.prl.stdlib.PrlStdlib;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 后端规则宿主上下文（设计文档 §3.1.2 的 PrlHostContext）。
 *
 * <p>干两件事：声明后端规则可调用的函数全集，接管标准库没有实现的那几个显式转换。</p>
 *
 * <p><b>为什么白名单要把标准库一并列进去。</b>{@code TypeChecker} 把
 * {@code getAvailableFunctions()} 当作「允许调用的函数全集」，表外的调用在编译期就被拒；
 * 而 {@code PrlVm} 运行期是先查宿主白名单、再查标准库。两条合起来意味着：一旦宿主声明了
 * 自己的函数，标准库名字也必须声明，否则规则里出现 {@code contains(...)} 这种调用会直接编译不过；
 * 声明了之后这些调用又会先落到 {@link #callFunction}，所以这里必须自己转交给一份
 * {@link PrlStdlib}。少任何一半，规则都会在「编译期通过、运行期被拒」或者反过来的地方断掉。</p>
 *
 * <p><b>为什么要接管 {@code to_float}/{@code to_int}/{@code to_string}。</b>它们只登记在
 * {@code PrlSignatures} 的签名表里（供类型检查用），标准库没有对应实现，也没在 IR 阶段降级成
 * 指令。宿主不补，规则里一用就抛 {@link PrlSecurityException}。</p>
 */
public class RuleHostContext implements PrlHostContext {

    private static final Logger log = LoggerFactory.getLogger(RuleHostContext.class);

    /** 宿主自己接管、标准库不提供的函数。 */
    private static final Set<String> HOST_FUNCTIONS = Set.of("to_float", "to_int", "to_string");

    /** 规则可调用的函数全集 = 标准库 + 宿主接管。见类注释。 */
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
        throw new PrlSecurityException("后端宿主没有注册函数 '" + name + "'");
    }

    /** 规则里的 {@code log(...)} 落到服务日志，别直接写标准输出。 */
    @Override
    public void log(String level, String message) {
        switch (level == null ? "" : level.toLowerCase(java.util.Locale.ROOT)) {
            case "error" -> log.error("[PRL] {}", message);
            case "warn", "warning" -> log.warn("[PRL] {}", message);
            case "debug" -> log.debug("[PRL] {}", message);
            default -> log.info("[PRL] {}", message);
        }
    }

    /**
     * 数值转换。PRL 没有隐式数值转换，规则里 int 与 float 混算必须显式转。
     *
     * @throws PrlSecurityException 入参不是数字；规则写错了应当立刻暴露，而不是给个 0 混过去
     */
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