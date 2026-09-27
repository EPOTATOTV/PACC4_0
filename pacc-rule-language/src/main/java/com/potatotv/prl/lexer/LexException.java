package com.potatotv.prl.lexer;

import com.potatotv.prl.PrlException;

/** 词法错误：非法字符、未闭合字符串、非法数字字面量等。 */
public class LexException extends PrlException {

    private static final long serialVersionUID = 1L;

    public LexException(String message, int line, int col) {
        super("词法错误: " + message, line, col);
    }
}