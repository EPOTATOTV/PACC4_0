package com.potatotv.prl.runtime;

import java.util.List;
import java.util.Map;

/**
 * 执行中的一帧的只读视图（设计文档 §2.15.1 的「变量查看」「调用栈」）。
 *
 * <p>由 VM 的帧对象直接实现，所以取到的都是<strong>活数据</strong>而不是快照。这个取舍是有意的：
 * 调试器只在真正停下时才去要变量和调用栈，每停一次复制一次 {@code slots} 可以接受；反过来，
 * 每条指令都做一份快照会让「挂着调试器跑规则」慢到没法用。</p>
 *
 * <p>{@link #variables()} 与 {@link #callStack()} 返回的是副本，调用方随便改，不影响 VM。</p>
 *
 * <p>除了 {@link #reportSuspendedNanos(long)}，本接口只读：它不改执行结果。那一处例外是省不掉的 ——
 * 挂起时长只有停下来的一方能量到，而它手上只有这一帧。</p>
 */
public interface PrlFrameView {

    /** 当前函数名；规则入口函数名形如 {@code rule_<规则名>}。 */
    String functionName();

    /** 当前源码行号；字节码里还没有行号标记时为 0。 */
    int line();

    /** 调用深度，入口函数为 1。 */
    int depth();

    /** 当前帧的变量槽位（{@code input} 形参、{@code let} 变量、闭包捕获值）。 */
    Map<String, Object> variables();

    /** 调用栈，栈顶在前，元素是函数名。 */
    List<String> callStack();

    /**
     * 上报一段「挂起」时长，让这段时间从执行预算里扣掉。
     *
     * <p>调试器停在断点上等控制指令时，被调试线程是阻塞的，但那段时间仍然落在
     * {@link com.potatotv.prl.vm.PrlVm} 的墙钟预算里。人在面板上多看几秒不该算成规则跑超时，
     * 所以挂起前后面各读一次时钟，把差值交给这里。</p>
     *
     * <p>默认空实现：不挂起（也就没有调试器）的宿主不必关心。只有真正阻塞过的调用方该上报，
     * 传非正数会被忽略。</p>
     */
    default void reportSuspendedNanos(long nanos) {
    }
}