package com.potatotv.prl.types;

import java.util.List;
import java.util.StringJoiner;

/**
 * 一个可调用函数的签名（设计文档 §2.4 的函数表）。
 *
 * <p>参数带名字，因为 §2.2.1、§2.4.7 的写法是具名实参：
 * {@code emit_alert(type = "KILL_AURA", confidence = 0.92, evidence = {...})}。位置实参同样支持，
 * 两种写法在 {@code TypeChecker} 里统一按「先占位、再按名字对齐」处理。</p>
 *
 * <p>这里只描述签名，不装实现：实现要么在宿主（§2.11.2 的 {@code callFunction}），要么在本模块的
 * 默认标准库 {@code com.potatotv.prl.stdlib}。</p>
 */
public record HostFunction(String name, List<Param> params, PrlType returnType, String description) {

    public HostFunction {
        params = params == null ? List.of() : List.copyOf(params);
    }

    /** 单个形参。 */
    public record Param(String name, PrlType type) {
    }

    public static Param param(String name, PrlType type) {
        return new Param(name, type);
    }

    public static HostFunction of(String name, PrlType returnType, String description, Param... params) {
        return new HostFunction(name, List.of(params), returnType, description);
    }

    public int arity() {
        return params.size();
    }

    public PrlType paramType(int index) {
        return params.get(index).type();
    }

    /** 形参名下标；不存在返回 -1。 */
    public int indexOfParam(String paramName) {
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i).name().equals(paramName)) {
                return i;
            }
        }
        return -1;
    }

    /** {@code emit_alert(string, float, map[any, any]) -> DetectionResult} 形式的签名文本。 */
    public String signature() {
        StringJoiner joiner = new StringJoiner(", ", name + "(", ")");
        for (Param p : params) {
            joiner.add(p.type().display());
        }
        return joiner + " -> " + (returnType == null ? "void" : returnType.display());
    }

    /** 带形参名的签名文本，报错时比光有类型更好定位。 */
    public String signatureWithNames() {
        StringJoiner joiner = new StringJoiner(", ", name + "(", ")");
        for (Param p : params) {
            joiner.add(p.name() + ": " + p.type().display());
        }
        return joiner + " -> " + (returnType == null ? "void" : returnType.display());
    }
}