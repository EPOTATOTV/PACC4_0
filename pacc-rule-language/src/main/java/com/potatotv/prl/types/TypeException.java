package com.potatotv.prl.types;

import com.potatotv.prl.PrlException;

/**
 * 类型解析/类型检查失败。
 *
 * <p>类型检查器绝大多数问题走 {@code Diagnostic} 收集，不抛异常；这个异常只用于「连类型都拼不出来」
 * 的硬失败，比如 {@code input} 里写了宿主没注册的类型名，此时检查器无处继续，就地抛出并由
 * {@code PrlCompiler} 转成编译错误。</p>
 */
public class TypeException extends PrlException {

    private static final long serialVersionUID = 1L;

    public TypeException(String message) {
        super(message);
    }

    public TypeException(String message, int line, int col) {
        super(message, line, col);
    }
}