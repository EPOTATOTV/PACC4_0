package com.potatotv.prl;

import com.potatotv.prl.analysis.PrlAnalyzer;
import com.potatotv.prl.bytecode.PrlBytecode;
import com.potatotv.prl.compiler.CompileResult;
import com.potatotv.prl.compiler.PrlCompiler;
import com.potatotv.prl.engine.RuleManager;
import com.potatotv.prl.sandbox.PrlHostContext;
import com.potatotv.prl.stdlib.PrlStdlib;
import com.potatotv.prl.vm.PrlVm;

import java.util.List;
import java.util.Map;

/**
 * PRL 的入口 API（设计文档 §2.16 工程结构里的 {@code Prl.java}）。
 *
 * <p>只是把内部的门面按「宿主最常用的四件事」摆出来：编译一条规则、跑一次规则、起一个规则引擎、
 * 做一次静态分析。PACC 侧要接的入口是 {@link RuleManager}（热加载、版本、发布，
 * 见 §2.12/§2.13），这里给的是脱离管理端、单机验证规则时的那条短路径。</p>
 *
 * <p>不做实例化：这个类没有状态，有的方法每次调用现建一个 VM/编译器。要复用状态（宿主上下文、
 * 观察者）就直接构造 {@link RuleManager} 或 {@link PrlVm}。</p>
 */
public final class Prl {

    /**
     * 引擎版本，与 {@code pom.xml} 的 {@code <version>} 对齐。
     *
     * <p>写死成常量而不是读 pom：字节码格式版本（{@code PrlBytecode.FORMAT_VERSION}）才是跨版本兼容的
     * 判据，这个字符串只是给人看的。用它做兼容性判断会出错，那是 {@code FORMAT_VERSION} 的事。</p>
     */
    public static final String VERSION = "1.0.0";

    private Prl() {
    }

    /** 编译源码，出错抛 {@link PrlException}（消息里带行号列号）。 */
    public static PrlBytecode compile(String source) {
        return new PrlCompiler().compile(source);
    }

    /** 编译源码，错误以诊断形式返回 —— 管理端编辑器实时检查用这条。 */
    public static CompileResult compileChecked(String source) {
        return new PrlCompiler().compileChecked(source);
    }

    /** 编译并执行一条规则，返回 {@code emit_alert} 的产物（没有告警时返回 {@code null}）。 */
    public static Object run(String source, Map<String, Object> input) {
        return run(source, PrlHostContext.EMPTY, input);
    }

    public static Object run(String source, PrlHostContext host, Map<String, Object> input) {
        PrlHostContext effective = host == null ? PrlHostContext.EMPTY : host;
        return new PrlVm(effective).execute(new PrlCompiler(effective).compile(source), input);
    }

    /** 起一个规则引擎（§2.12.2），装载、热加载、版本发布都挂在它上面。 */
    public static RuleManager engine() {
        return new RuleManager();
    }

    public static RuleManager engine(PrlHostContext host) {
        return new RuleManager(host);
    }

    /** 取一个静态分析器（§2.14）。 */
    public static PrlAnalyzer analyzer() {
        return new PrlAnalyzer();
    }

    public static PrlAnalyzer analyzer(PrlHostContext host) {
        return new PrlAnalyzer(host);
    }

    /** 标准库函数名，字典序（§2.4，验收项 R11 的 50+ 函数）。 */
    public static List<String> stdlibFunctions() {
        return PrlStdlib.functionNames();
    }
}