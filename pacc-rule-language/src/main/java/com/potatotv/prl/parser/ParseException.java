package com.potatotv.prl.parser;

import com.potatotv.prl.PrlException;

/** 语法错误：不符合文档 §2.2 语法的输入。 */
public class ParseException extends PrlException {

    private static final long serialVersionUID = 1L;

    public ParseException(String message, int line, int col) {
        super("语法错误: " + message, line, col);
    }
}