package com.potatotv.prl.bytecode;

import java.util.List;

/**
 * 编译好的一个函数（设计文档 §2.8.1 的 Code Section 条目）。
 *
 * <p>寄存器文件装的全是表达式中间结果：{@code input} 参数与 {@code let} 变量走名字槽位
 * （{@code LOAD}/{@code STORE}），原因见 {@code IrGenerator}。{@code captures} 与 {@code params}
 * 都是槽位名，调用时按名绑定，因此 lambda 体内读捕获值和读形参是同一条 {@code LOAD}。</p>
 *
 * @param name        函数名，CLOSURE 靠它找到目标
 * @param params      形参槽位名，按位置对应实参
 * @param captures    捕获槽位名，按位置对应闭包携带的值
 * @param registerCount 寄存器个数（{@code 1..256}）
 * @param entry       是否为规则入口
 */
public record BytecodeFunction(String name,
                               List<String> params,
                               List<String> captures,
                               byte[] code,
                               int registerCount,
                               boolean entry) {

    public BytecodeFunction {
        params = params == null ? List.of() : List.copyOf(params);
        captures = captures == null ? List.of() : List.copyOf(captures);
        code = code == null ? new byte[0] : code.clone();
    }
}