package com.potatotv.prl.ast;

/**
 * 局部变量声明：{@code let attack_rate = rate(attack_events, 1s)} 或 {@code let x: int = 42}。
 *
 * <p>{@code let} 声明的变量可再次赋值（文档 §2.2.5 的 {@code i += 1}、
 * {@code high_damage_count += 1} 依赖这一点）。</p>
 */
public record LetDecl(String name, TypeRef declaredType, Expression initializer, int line, int col)
        implements AstNode, Statement {

    public boolean hasDeclaredType() {
        return declaredType != null;
    }
}