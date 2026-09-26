package com.potatotv.prl.runtime;

import com.potatotv.prl.PrlException;

/**
 * 规则运行期错误：类型对但值不对（除零、空集合取首元素、索引越界等）。
 *
 * <p>这类错误会被 {@code RuleManager} 记到规则头上并按失败次数自动禁用（§2.11.1 L4），
 * 不会影响宿主进程。</p>
 */
public class PrlExecutionException extends PrlException {

    private static final long serialVersionUID = 1L;

    public PrlExecutionException(String message) {
        super(message);
    }

    public PrlExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}