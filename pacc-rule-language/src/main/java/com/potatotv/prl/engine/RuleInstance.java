package com.potatotv.prl.engine;

import com.potatotv.prl.bytecode.PrlBytecode;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 装载到引擎里的一条规则的运行时状态（设计文档 §2.12.2 的 {@code RuleInstance}）。
 *
 * <p>{@link #bytecode()}、{@link #name()}、{@link #loadedAtMs()} 是不可变的；其余状态都可能在
 * 执行线程与热加载线程之间共享，所以 {@code enabled}、{@code lastError}、{@code lastExecutedMs}
 * 用 {@code volatile}，计数器用原子类。规则执行本身是并发的（{@code PrlVm} 每次执行新建
 * {@code Execution}），这条状态不能成为共用的可变点。</p>
 *
 * <p>冷却按「规则」而不是「玩家」计（{@code cooldown} 写在规则头部 §2.2.1，是规则自己的属性）。
 * {@link #cooldownOk(Map)} 保留入参是为了对齐 §2.12.2 的签名，当前实现不按玩家分桶 —— 文档没有
 * 规定用哪个输入字段标识玩家，凭空挑一个（{@code pteid}？{@code player_id}？）会让宿主换个字段名就静默失效。</p>
 */
public final class RuleInstance {

    private final String name;
    private final PrlBytecode bytecode;
    private final long loadedAtMs;
    private final long cooldownMillis;

    private volatile boolean enabled;
    private volatile String lastError;
    private volatile long lastExecutedMs = Long.MIN_VALUE;

    private final AtomicInteger failureCount = new AtomicInteger();
    private final AtomicLong executionCount = new AtomicLong();

    public RuleInstance(String name, PrlBytecode bytecode, long loadedAtMs) {
        this(name, bytecode, loadedAtMs, bytecode == null || bytecode.rules().isEmpty()
                ? 0L
                : bytecode.rules().get(0).cooldownMillis());
    }

    public RuleInstance(String name, PrlBytecode bytecode, long loadedAtMs, long cooldownMillis) {
        this.name = name;
        this.bytecode = bytecode;
        this.loadedAtMs = loadedAtMs;
        this.cooldownMillis = Math.max(cooldownMillis, 0L);
        this.enabled = bytecode == null || bytecode.rules().isEmpty() || bytecode.rule(name) == null
                || bytecode.rule(name).enabled();
    }

    public String name() {
        return name;
    }

    public PrlBytecode bytecode() {
        return bytecode;
    }

    public long loadedAtMs() {
        return loadedAtMs;
    }

    public long cooldownMillis() {
        return cooldownMillis;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void enable() {
        enabled = true;
        failureCount.set(0);
        lastError = null;
    }

    public void disable() {
        enabled = false;
    }

    public int failureCount() {
        return failureCount.get();
    }

    public long executionCount() {
        return executionCount.get();
    }

    /** 最近一次失败的描述；从未失败或已重新启用时为 {@code null}。 */
    public String lastError() {
        return lastError;
    }

    /** 记录一次失败，返回累计失败次数。 */
    public int recordFailure(Throwable error) {
        lastError = error == null ? null : String.valueOf(error.getMessage());
        return failureCount.incrementAndGet();
    }

    /** 冷却是否已过。{@code cooldown} 为 0 时永远为 true。 */
    public boolean cooldownOk(Map<String, Object> input) {
        return cooldownOk(System.currentTimeMillis());
    }

    public boolean cooldownOk(long nowMs) {
        if (cooldownMillis <= 0L) {
            return true;
        }
        return lastExecutedMs == Long.MIN_VALUE || nowMs - lastExecutedMs >= cooldownMillis;
    }

    /** 记一次已执行：推进冷却窗口、累加执行次数。 */
    public void markExecuted(long nowMs) {
        lastExecutedMs = nowMs;
        executionCount.incrementAndGet();
    }

    @Override
    public String toString() {
        return "RuleInstance[" + name + (enabled ? "" : " disabled")
                + " failures=" + failureCount.get() + " executions=" + executionCount.get() + "]";
    }
}