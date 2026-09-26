package com.potatotv.prl.debugger;

import com.potatotv.prl.runtime.PrlFrameView;

import java.util.List;
import java.util.Map;

/**
 * 一次暂停的现场快照（设计文档 §2.15.1 的「变量查看」「调用栈」）。
 *
 * <p>在<strong>暂停的那一刻</strong>复制，而不是把 {@link PrlFrameView} 直接交给控制线程：暂停解除后
 * 帧还会继续被改写，控制线程手里拿着一个会变的视图，读到的变量就成了「哪一刻的都有」。
 * 复制只发生在真正停下来的时候，代价可以接受。</p>
 *
 * @param functionName 当前函数名，规则入口函数形如 {@code rule_<规则名>}
 * @param line         当前源码行号
 * @param depth        调用深度，入口函数为 1
 * @param variables    当前帧的变量槽位副本
 * @param callStack    调用栈，栈顶在前
 */
public record DebugState(String functionName,
                         int line,
                         int depth,
                         Map<String, Object> variables,
                         List<String> callStack) {

    public static DebugState snapshot(PrlFrameView frame) {
        return new DebugState(frame.functionName(), frame.line(), frame.depth(),
                frame.variables(), frame.callStack());
    }

    @Override
    public String toString() {
        return "暂停于 " + functionName + ":" + line + "（深度 " + depth + "）"
                + " 变量 " + variables.keySet() + " 调用栈 " + callStack;
    }
}