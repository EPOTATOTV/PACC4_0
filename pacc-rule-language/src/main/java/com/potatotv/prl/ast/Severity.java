package com.potatotv.prl.ast;

import java.util.Locale;

/** 规则严重级（设计文档 §2.2.2「严重级」）。 */
public enum Severity {

    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public static Severity fromKeyword(String keyword) {
        return valueOf(keyword.toUpperCase(Locale.ROOT));
    }
}