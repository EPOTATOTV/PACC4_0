package com.potatotv.prl.runtime;

/**
 * 规则里可以传递和调用的函数值（设计文档 §2.3.2 的 {@code FUNC} 类型）。
 *
 * <p>VM 侧的实现是捕获了自由变量的 lambda；测试与宿主也可以直接给一个 Java 匿名类，
 * 因此标准库只依赖这个接口，不依赖 VM。</p>
 */
public interface PrlCallable {

    /**
     * 调用。
     *
     * @param args 实参，长度与 {@link #arity()} 一致
     * @return 返回值；{@code void} 函数返回 {@code null}
     */
    Object call(Object[] args);

    /** 形参个数。 */
    int arity();

    /** 调用栈与错误信息里显示的名字。 */
    default String functionName() {
        return "lambda";
    }
}