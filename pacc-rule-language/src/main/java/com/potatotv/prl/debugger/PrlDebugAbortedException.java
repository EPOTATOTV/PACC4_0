package com.potatotv.prl.debugger;

import com.potatotv.prl.PrlException;

/**
 * 调试会话被中止（{@link PrlDebugger#abort()}、{@link PrlDebugger#close()}，或调试线程被中断）。
 *
 * <p>继承 {@link PrlException} 而不是 {@code PrlExecutionException}：VM 的
 * {@code dispatch} 对 {@code PrlException} 是原样放行的，只对其它 {@link RuntimeException} 做
 * 「未预期异常」包装。中止是调试器的正常出口，不该被包装成「规则执行抛出未预期异常」——
 * 那会让管理端把「用户点了停止」显示成规则出错。</p>
 */
public final class PrlDebugAbortedException extends PrlException {

    private static final long serialVersionUID = 1L;

    public PrlDebugAbortedException(String message) {
        super(message);
    }
}