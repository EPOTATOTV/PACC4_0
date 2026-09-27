package com.potatotv.prl.check;

/**
 * 编译期诊断（设计文档 §2.14.1）。
 *
 * <p>类型检查、静态分析、安全审计共用这一个载体：错误（{@link Level#ERROR}）阻断编译，警告
 * （{@link Level#WARNING}）只上报。{@code code} 用于管理端做分类统计与文案本地化。</p>
 */
public record Diagnostic(Level level, String code, String message, int line, int col) {

    /** 诊断级别。与规则本身的 {@code Severity}（low/medium/...）无关，不要混用。 */
    public enum Level {
        ERROR,
        WARNING
    }

    public static Diagnostic error(String code, String message, int line, int col) {
        return new Diagnostic(Level.ERROR, code, message, line, col);
    }

    public static Diagnostic warning(String code, String message, int line, int col) {
        return new Diagnostic(Level.WARNING, code, message, line, col);
    }

    public boolean isError() {
        return level == Level.ERROR;
    }

    @Override
    public String toString() {
        return "[" + line + ":" + col + "] " + level + " " + code + ": " + message;
    }
}