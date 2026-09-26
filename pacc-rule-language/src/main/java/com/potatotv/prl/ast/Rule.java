package com.potatotv.prl.ast;

import java.util.List;

/**
 * 一条检测规则（设计文档 §2.2.1）。
 *
 * @param name      规则名（唯一）
 * @param metadata  规则头部元数据
 * @param input     输入变量声明，缺省为空（入口只有宿主传入的隐式上下文）
 * @param lets      局部变量声明，按源码顺序
 * @param when      触发条件，{@code null} 表示无条件触发（等价于恒真）
 * @param then      触发动作语句列表
 */
public record Rule(String name,
                   RuleMetadata metadata,
                   InputDecl input,
                   List<LetDecl> lets,
                   WhenClause when,
                   List<Statement> then,
                   int line,
                   int col) implements AstNode {

    public Rule {
        input = input == null ? new InputDecl(List.of(), line, col) : input;
        lets = lets == null ? List.of() : List.copyOf(lets);
        then = then == null ? List.of() : List.copyOf(then);
    }
}