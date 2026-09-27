package com.potatotv.prl.ast;

import java.util.List;

/**
 * 源码里的类型引用，如 {@code int}、{@code list[AttackEvent]}、{@code map[string, float]}。
 *
 * <p>解析阶段只做语法记录，不判断类型是否存在；把 {@code TypeRef} 解析成
 * {@link com.potatotv.prl.types.PrlType} 是类型检查器的事，这样宿主注册了哪些自定义类型
 * 不影响语法分析。</p>
 */
public record TypeRef(String name, List<TypeRef> args, int line, int col) {

    public TypeRef {
        args = args == null ? List.of() : List.copyOf(args);
    }

    public static TypeRef of(String name, int line, int col) {
        return new TypeRef(name, List.of(), line, col);
    }
}