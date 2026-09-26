package com.potatotv.prl;

/**
 * PRL 所有受检语义错误的基类。
 *
 * <p>不继承 {@link Exception} 而继承 {@link RuntimeException}：宿主（PACC）在热路径上执行规则，
 * 逐层声明检查异常只会让调用方写一堆无意义的 try/catch。规则本身的问题一律通过
 * {@code RuleManager} 的错误收集机制上报，不靠异常传播。</p>
 */
public class PrlException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 1 基行号，0 表示位置未知。 */
    private final int line;

    /** 1 基列号，0 表示位置未知。 */
    private final int col;

    public PrlException(String message) {
        this(message, 0, 0, null);
    }

    public PrlException(String message, Throwable cause) {
        this(message, 0, 0, cause);
    }

    public PrlException(String message, int line, int col) {
        this(message, line, col, null);
    }

    public PrlException(String message, int line, int col, Throwable cause) {
        super(line > 0 ? "[" + line + ":" + col + "] " + message : message, cause);
        this.line = line;
        this.col = col;
    }

    public int line() {
        return line;
    }

    public int col() {
        return col;
    }
}