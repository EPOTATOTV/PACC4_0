package com.potatotv.prl.ast;

/**
 * 所有 AST 节点的根（设计文档 §2.6.2）。
 *
 * <p>层级与文档一致：{@code AstNode} 直接派生文件、规则、元数据、输入声明、局部声明、when 子句、
 * 语句与表达式；语句与表达式的具体节点以嵌套 record 的形式收在 {@link Statement}、
 * {@link Expression} 里，避免为每个字面量类型单开一个文件。</p>
 *
 * <p>位置信息（1 基行列）挂在每个节点上：静态分析与调试器都要靠它把问题指回源码。</p>
 */
public sealed interface AstNode
        permits RuleFile, Rule, RuleMetadata, InputDecl, LetDecl, WhenClause, Statement, Expression {

    int line();

    int col();
}