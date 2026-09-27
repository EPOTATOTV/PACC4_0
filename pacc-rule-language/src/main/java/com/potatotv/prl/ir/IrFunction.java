package com.potatotv.prl.ir;

import java.util.List;

/**
 * 一个 IR 函数：规则本体（{@code entry} 为真），或者一条 lambda 编译出来的嵌套函数（设计文档 §2.7.1）。
 *
 * <p>{@code params} 与 {@code captures} 都是槽位名，运行时分别绑定到形参值和闭包捕获值。
 * 捕获值也走槽位而不是单独取用指令，是为了让函数体内的变量引用只有 {@code LOAD} 一种形态 ——
 * lambda 里引用捕获变量和引用自己的形参在字节码上完全一样。</p>
 *
 * @param entry    是否为规则入口（一个规则文件里的每条规则各有一个入口）
 * @param tempCount 临时值编号的高水位，决定字节码寄存器文件的大小
 */
public record IrFunction(String name,
                         List<String> params,
                         List<String> captures,
                         List<IrInstr> code,
                         int tempCount,
                         boolean entry) {

    public IrFunction {
        params = params == null ? List.of() : List.copyOf(params);
        captures = captures == null ? List.of() : List.copyOf(captures);
        code = code == null ? List.of() : List.copyOf(code);
    }

    /** 函数签名，IR dump 与错误信息用。 */
    public String signature() {
        return name + "(" + String.join(", ", params) + ")";
    }
}