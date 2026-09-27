package com.potatotv.prl.debugger;

import java.util.Map;

/**
 * 日志点留下的一条记录（设计文档 §2.15.1 的「日志点：不中断，只记录变量值」）。
 *
 * @param line         触发时的源码行号
 * @param functionName 触发时的函数名
 * @param variables    触发那一刻的变量快照
 */
public record LogRecord(int line, String functionName, Map<String, Object> variables) {

    @Override
    public String toString() {
        return "[" + line + "] " + functionName + " " + variables;
    }
}