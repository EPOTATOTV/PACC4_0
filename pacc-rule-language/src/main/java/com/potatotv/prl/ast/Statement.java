package com.potatotv.prl.ast;

import java.util.List;

/**
 * 语句（设计文档 §2.2.5 控制流）。
 *
 * <p>{@link LetDecl} 同时是语句与顶层声明，因此在这里被重复 permit 一次；{@code else if} 链
 * 不单设节点，直接表现为 {@link IfStmt#elseBody()} 里只有一条 {@link IfStmt}。</p>
 */
public sealed interface Statement extends AstNode
        permits LetDecl, Statement.AssignStmt, Statement.IfStmt,
                Statement.ForStmt, Statement.WhileStmt, Statement.ReturnStmt, Statement.ExprStmt {

    /** 赋值运算符。 */
    enum AssignOp {
        ASSIGN("="),
        PLUS("+="),
        MINUS("-="),
        STAR("*="),
        SLASH("/=");

        private final String symbol;

        AssignOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /**
     * 赋值语句，如 {@code i += 1}、{@code severity = critical}。
     *
     * <p>{@code target} 允许标识符、成员访问、下标访问三种形态，其余形态由类型检查器拒绝。</p>
     */
    record AssignStmt(Expression target, AssignOp op, Expression value, int line, int col) implements Statement {
    }

    /** {@code if <cond>: ... else: ... end}。 */
    record IfStmt(Expression condition, List<Statement> thenBody, List<Statement> elseBody, int line, int col)
            implements Statement {

        public IfStmt {
            thenBody = thenBody == null ? List.of() : List.copyOf(thenBody);
            elseBody = elseBody == null ? List.of() : List.copyOf(elseBody);
        }
    }

    /** {@code for item in list: ... end}。 */
    record ForStmt(String variable, Expression iterable, List<Statement> body, int line, int col)
            implements Statement {

        public ForStmt {
            body = body == null ? List.of() : List.copyOf(body);
        }
    }

    /** {@code while <cond>: ... end}，运行时受指令数上限保护（§2.9.1）。 */
    record WhileStmt(Expression condition, List<Statement> body, int line, int col) implements Statement {

        public WhileStmt {
            body = body == null ? List.of() : List.copyOf(body);
        }
    }

    /** {@code return [expr]}。 */
    record ReturnStmt(Expression value, int line, int col) implements Statement {
    }

    /** 以表达式为语句，主要用于动作函数调用（{@code emit_alert(...)}）。 */
    record ExprStmt(Expression expression, int line, int col) implements Statement {
    }
}