package com.potatotv.prl.runtime;

/**
 * 执行观察者（设计文档 §2.15.1 调试器、§2.15.2 Profiling 的接入点）。
 *
 * <p>五个回调覆盖了两类工具的全部需要：调试器只要 {@link #onInstruction} 来比对断点与单步，
 * Profiler 要 {@link #onRuleStart}/{@link #onRuleEnd} 拿一次执行的墙钟时间、
 * {@link #onEnterFunction}/{@link #onExitFunction} 拿函数级耗时。合成一个接口是为了
 * 让 {@code PrlVm} 只多一个可空字段，而不是两个 —— 挂钩子本身在热路径上是有成本的，
 * 挂在几处就要在几处付这个成本。</p>
 *
 * <p>全部是 default 空实现：只关心断点的实现不必写耗时统计的空方法。</p>
 */
public interface PrlExecutionObserver {

    /** 不观察任何东西。VM 里用它做「没挂观察者」的判断，避免到处判 null。 */
    PrlExecutionObserver NONE = new PrlExecutionObserver() {
    };

    /**
     * 每个<strong>源码行标记</strong>处调用一次，此时该行的求值还没开始。
     *
     * <p>粒度是语句而不是字节码指令：一条 {@code let} 会展开成十几条指令，按指令停会让编辑器里
     * 「单步」看起来停在同一个地方的十几个位置上。断点与单步都只需要行粒度，所以钩子挂在
     * {@code LINE} 指令上（§2.15.1）。</p>
     *
     * <p>实现必须是<strong>廉价</strong>的：断点比对只是一次集合查找，单步判断只是读一个枚举。
     * 需要变量快照时才去调 {@link PrlFrameView#variables()}，别在这里无条件取。</p>
     */
    default void onInstruction(PrlFrameView frame) {
    }

    /** 一次规则执行开始。{@code ruleName} 是规则名（不是入口函数名）。 */
    default void onRuleStart(String ruleName) {
    }

    /**
     * 一次规则执行结束。
     *
     * @param elapsedNanos 从 {@link #onRuleStart} 到这里的墙钟耗时；抛异常退出也会回调，
     *                     所以 Profiler 的计数不会漏掉失败的执行
     */
    default void onRuleEnd(String ruleName, long elapsedNanos) {
    }

    /** 进入一个函数调用（宿主函数、标准库函数、程序内函数、lambda 都走这里）。 */
    default void onEnterFunction(String functionName) {
    }

    /**
     * 离开一个函数调用。
     *
     * @param elapsedNanos 从 {@link #onEnterFunction} 到这里的墙钟耗时；抛异常退出也会回调，
     *                     所以 Profiler 的计数不会漏掉失败调用
     */
    default void onExitFunction(String functionName, long elapsedNanos) {
    }
}