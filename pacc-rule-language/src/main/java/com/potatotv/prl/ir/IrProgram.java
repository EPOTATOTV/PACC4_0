package com.potatotv.prl.ir;

import java.util.List;

/**
 * 一个规则文件编译出的 IR 程序：若干函数，其中若干条是规则入口，其余是 lambda。
 *
 * <p>lambda 之所以编译成独立函数而不是内联，是因为 PRL 的 lambda 不能改外层变量（§2.2.4），
 * 嵌套函数 + 捕获值列表足够表达；引入完整词法环境链只会让 VM 每读一个变量都要多跳一层。</p>
 */
public final class IrProgram {

    private final List<IrFunction> functions;

    public IrProgram(List<IrFunction> functions) {
        this.functions = List.copyOf(functions);
    }

    public List<IrFunction> functions() {
        return functions;
    }

    /** 第一个规则入口。 */
    public IrFunction entry() {
        for (IrFunction function : functions) {
            if (function.entry()) {
                return function;
            }
        }
        throw new IllegalStateException("IR 程序里没有规则入口");
    }

    public List<IrFunction> entries() {
        return functions.stream().filter(IrFunction::entry).toList();
    }

    public IrFunction function(String name) {
        for (IrFunction function : functions) {
            if (function.name().equals(name)) {
                return function;
            }
        }
        return null;
    }

    /** 多行 IR 文本，管理端规则编辑器与单测排查用。 */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        for (IrFunction function : functions) {
            sb.append(function.entry() ? "FUNCTION " : "LAMBDA ").append(function.signature());
            if (!function.captures().isEmpty()) {
                sb.append("  CAPTURES ").append(function.captures());
            }
            sb.append("  TEMPS ").append(function.tempCount()).append('\n');
            for (IrInstr instr : function.code()) {
                sb.append("  ").append(instr).append('\n');
            }
        }
        return sb.toString();
    }
}