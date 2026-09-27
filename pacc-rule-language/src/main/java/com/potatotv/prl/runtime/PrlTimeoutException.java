package com.potatotv.prl.runtime;

import com.potatotv.prl.PrlException;

/**
 * 规则执行超时（设计文档 §2.11.1 的 L2：单条规则 100ms）。
 *
 * <p>与指令数上限（100000 条）是两道独立的闸：指令数防的是「跑得久」，超时防的是「被宿主函数卡住」，
 * 后者在 VM 里靠调用前检查时间点实现。</p>
 */
public class PrlTimeoutException extends PrlExecutionException {

    private static final long serialVersionUID = 1L;

    public PrlTimeoutException(String message) {
        super(message);
    }
}