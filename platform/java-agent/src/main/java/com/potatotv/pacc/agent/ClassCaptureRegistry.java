package com.potatotv.pacc.agent;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时字节码捕获登记表。
 *
 * <p>由于探针不引入 ASM，无法改写方法体，{@code ClassFileTransformer} 改为「只读取证」：
 * 在类装载（含 {@code retransformClasses} 重转换）时把目标类的运行时字节码原样登记进来，
 * 供 {@link BytecodeIntegrityChecker} 做与类路径原件的完整性比对、
 * {@link ClassLoaderAuditor} 做装载审计。</p>
 *
 * <p><b>内存约束</b>：只登记「10 个监控目标类」与「命中作弊客户端标记的类」，并设有条目上限
 * {@link #CAPACITY}，避免在游戏进程内堆积无关类字节。超出上限后新捕获被丢弃（不影响已有条目）。</p>
 *
 * <p>线程安全：基于 {@link ConcurrentHashMap}，可被转换器线程与采样线程并发访问。</p>
 */
final class ClassCaptureRegistry {

    /** 最大登记条目数，防止内存膨胀。 */
    private static final int CAPACITY = 4096;

    private final Map<String, Capture> captures = new ConcurrentHashMap<>();

    /**
     * 登记一个类的运行时字节码。已登记过的类会被覆盖，以保留最近一次（重转换后）的字节码。
     *
     * @param className 斜杠形式类名（{@code a/b/C}）
     * @param reason    捕获原因（目标 id 列表或 {@code cheat_marker}）
     * @param bytes     运行时字节码（原始 classfileBuffer）
     */
    void record(String className, String reason, byte[] bytes) {
        if (className == null || bytes == null || className.isBlank()) return;
        if (captures.containsKey(className)) {
            captures.put(className, new Capture(className, reason, bytes, System.currentTimeMillis()));
            return;
        }
        if (captures.size() >= CAPACITY) return;
        captures.putIfAbsent(className, new Capture(className, reason, bytes, System.currentTimeMillis()));
    }

    /** 查询某类的运行时捕获；未捕获返回空。 */
    Optional<Capture> get(String className) {
        if (className == null) return Optional.empty();
        return Optional.ofNullable(captures.get(className));
    }

    /** 当前登记条目数。 */
    int size() {
        return captures.size();
    }

    /**
     * 单条运行时字节码捕获记录。
     *
     * @param className 斜杠形式类名
     * @param reason    捕获原因
     * @param bytes     运行时字节码
     * @param timestamp 捕获时间（毫秒）
     */
    record Capture(String className, String reason, byte[] bytes, long timestamp) {
    }
}