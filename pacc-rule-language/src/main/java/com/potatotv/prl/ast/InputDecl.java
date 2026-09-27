package com.potatotv.prl.ast;

import java.util.List;

/** 输入变量声明块：{@code input { player: PlayerContext ... }}。 */
public record InputDecl(List<Field> fields, int line, int col) implements AstNode {

    public InputDecl {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /** 单个输入变量。 */
    public record Field(String name, TypeRef type, int line, int col) {
    }
}