package com.potatotv.prl.debugger;

/**
 * 调试器上的一条断点记录（设计文档 §2.15.1），给管理端的调试面板列出来用。
 *
 * <p>只带「这一行是什么」的元信息，不带判定条件本身：条件是调用方给的
 * {@code Predicate<PrlFrameView>}，把它塞进一个 record 会让 {@code equals} 变成对闭包的引用比较，
 * 面板要做的是展示与删除，不需要比较两个断点是否相等。</p>
 *
 * @param line        源码行号
 * @param logpoint    {@code true} 表示日志点（只记录变量值，不中断）
 * @param conditional 是否带条件（无条件断点恒中断）
 */
public record Breakpoint(int line, boolean logpoint, boolean conditional) {

    @Override
    public String toString() {
        String kind = logpoint ? "日志点" : "断点";
        return kind + " @" + line + (conditional ? "（条件）" : "");
    }
}