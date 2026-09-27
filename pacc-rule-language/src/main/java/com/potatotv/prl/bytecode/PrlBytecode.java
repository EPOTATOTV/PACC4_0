package com.potatotv.prl.bytecode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一份完整的 {@code .prlc} 编译产物（设计文档 §2.8.1）。
 *
 * <p>{@code strings} 是文件里的字符串表。运行期它承担两件事：反汇编输出可读，
 * 以及 {@code CALL}/{@code CLOSURE} 的目标名解析 —— 这两条指令的操作数是字符串表下标
 * （见 {@link BytecodeCompiler}），VM 要先取名字再查 {@link #indexOf(String)} 才能落到函数表上。</p>
 */
public final class PrlBytecode {

    /** 当前字节码格式版本，写在文件头 Version 字段（§2.8.1）。 */
    public static final int FORMAT_VERSION = 1;

    private final List<BytecodeFunction> functions;
    private final List<RuleEntry> rules;
    private final List<String> strings;
    private final Map<String, Integer> functionIndex;

    public PrlBytecode(List<BytecodeFunction> functions, List<RuleEntry> rules, List<String> strings) {
        this.functions = List.copyOf(functions);
        this.rules = List.copyOf(rules);
        this.strings = List.copyOf(strings);
        Map<String, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < this.functions.size(); i++) {
            index.putIfAbsent(this.functions.get(i).name(), i);
        }
        this.functionIndex = Map.copyOf(index);
    }

    public List<BytecodeFunction> functions() {
        return functions;
    }

    public List<RuleEntry> rules() {
        return rules;
    }

    public List<String> strings() {
        return strings;
    }

    /** 字符串表里下标 {@code index} 的文本；越界返回 {@code null}。 */
    public String string(int index) {
        return index >= 0 && index < strings.size() ? strings.get(index) : null;
    }

    /** 按名字找函数下标，找不到返回 -1（表示目标不在本程序的函数表里，交给宿主）。 */
    public int indexOf(String name) {
        Integer index = functionIndex.get(name);
        return index == null ? -1 : index;
    }

    public BytecodeFunction function(int index) {
        return functions.get(index);
    }

    public BytecodeFunction function(String name) {
        for (BytecodeFunction function : functions) {
            if (function.name().equals(name)) {
                return function;
            }
        }
        return null;
    }

    public RuleEntry rule(String name) {
        for (RuleEntry rule : rules) {
            if (rule.name().equals(name)) {
                return rule;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }
}