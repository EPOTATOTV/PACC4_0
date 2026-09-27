package com.potatotv.prl.runtime;

import com.potatotv.prl.PrlException;

/**
 * 沙箱拒绝执行（设计文档 §2.11.1 的 L3 API 白名单）。
 *
 * <p>出现这个异常说明规则触碰了白名单之外的能力：未注册的函数、越界的内存申请等。
 * 编译期已经按白名单拦过一层，这里是运行时的兜底 —— 宿主自己接管的 {@code callFunction}
 * 也会用它来回绝未知函数。</p>
 */
public class PrlSecurityException extends PrlException {

    private static final long serialVersionUID = 1L;

    public PrlSecurityException(String message) {
        super(message);
    }
}