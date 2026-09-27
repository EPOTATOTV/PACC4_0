package com.potatotv.prl.ast;

/** 触发条件子句：{@code when: <布尔表达式>}。 */
public record WhenClause(Expression condition, int line, int col) implements AstNode {
}