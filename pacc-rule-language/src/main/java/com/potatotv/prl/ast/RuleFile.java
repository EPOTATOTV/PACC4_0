package com.potatotv.prl.ast;

import java.util.List;

/** 一个 {@code .prl} 文件（可含多条规则）。 */
public record RuleFile(List<Rule> rules, int line, int col) implements AstNode {

    public RuleFile {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}