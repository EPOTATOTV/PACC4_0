package com.potatotv.prl.profiler;

import com.potatotv.prl.runtime.PrlExecutionObserver;
import com.potatotv.prl.stdlib.PrlStdlib;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * PRL 性能分析器（设计文档 §2.15.2）。
 *
 * <p>挂在 {@link PrlExecutionObserver} 上采集三样东西：每次规则的执行时间、每个函数的
 * <strong>自身</strong>耗时（不含子调用，否则外层函数永远排第一）、以及内存峰值（由调用方喂进来）。</p>
 *
 * <p>分位数用<strong>对数刻度直方图</strong>而不是留全量样本：一条规则跑几百万次，留住样本要么
 * 打爆堆，要么做蓄水池采样变成随机数（同一份代码跑两次给出不同的 P99）。直方图固定 512 个桶、
 * 每倍频程 8 个桶，内存是常数，P95/P99 的相对误差不超过 12.5%，而且可复现。</p>
 *
 * <p>函数耗时按线程归属：{@code onRuleStart} 在 {@link ThreadLocal} 上压一条规则名，
 * 这期间的所有函数调用都记到这条规则名下。宿主在多个线程上并发跑规则时，各线程各算各的。</p>
 *
 * <p>采集到的耗时是墙钟时间，包含线程调度与 GC 的抖动，用来看「哪个函数占大头」够用，
 * 不要当成微基准。</p>
 */
public final class PrlProfiler implements PrlExecutionObserver {

    /** 每倍频程 8 个桶 → 分位数相对误差上限 1/8。 */
    private static final int BUCKETS_PER_OCTAVE = 8;

    /** 64 个倍频程 × 8 = 512 个桶，覆盖到 1.8e19 纳秒（够任何一次规则执行）。 */
    private static final int BUCKET_COUNT = 64 * BUCKETS_PER_OCTAVE;

    private final Map<String, RuleProfile> profiles = new ConcurrentHashMap<>();

    /** 当前线程正在执行哪条规则（支持嵌套，用栈而不是单个字段）。 */
    private final ThreadLocal<Deque<String>> ruleStack = ThreadLocal.withInitial(ArrayDeque::new);

    /** 当前线程的函数调用栈，用来算「自身耗时」。 */
    private final ThreadLocal<Deque<CallFrame>> callStack = ThreadLocal.withInitial(ArrayDeque::new);

    // ------------------------------------------------------------------ 登记

    public void register(String ruleName, String version) {
        register(ruleName, version, 0L);
    }

    /**
     * 登记一条规则。
     *
     * @param version            版本号，写进报告标题
     * @param estimatedHeapBytes 静态估算的内存占用（{@code RuleMetrics#estimatedHeapBytes}），
     *                           作为内存峰值的基线；实测值比它大时以实测值计
     */
    public void register(String ruleName, String version, long estimatedHeapBytes) {
        RuleProfile profile = profile(ruleName);
        profile.version = version;
        if (estimatedHeapBytes > 0) {
            profile.noteMemory(estimatedHeapBytes);
        }
    }

    /** 喂一次实测内存占用（例如宿主观测到的规则执行期间堆增长），取历史最大值。 */
    public void markMemory(String ruleName, long bytes) {
        profile(ruleName).noteMemory(bytes);
    }

    public void reset() {
        profiles.clear();
    }

    /** 已采集到的规则名，字典序。 */
    public List<String> rules() {
        List<String> names = new ArrayList<>(profiles.keySet());
        names.sort(Comparator.naturalOrder());
        return names;
    }

    /** 某条规则执行了多少次；没登记过返回 0。 */
    public long executions(String ruleName) {
        RuleProfile profile = profiles.get(ruleName);
        return profile == null ? 0L : profile.executions.get();
    }

    // ------------------------------------------------------------------ 报告

    public ProfilerReport report(String ruleName) {
        RuleProfile profile = profiles.get(ruleName);
        if (profile == null) {
            return new ProfilerReport(ruleName, null, 0, 0, 0, 0, 0, 0, List.of(), List.of());
        }
        long count = profile.executions.get();
        long average = count == 0 ? 0 : profile.sumNanos.get() / count;
        long p95 = profile.percentile(0.95);
        long p99 = profile.percentile(0.99);
        List<FunctionHotspot> hotspots = profile.hotspots();
        return new ProfilerReport(ruleName, profile.version, count, average, p95, p99,
                profile.maxNanos.get(), profile.memoryPeakBytes.get(), hotspots,
                suggest(profile, hotspots, average, p99));
    }

    /** 全部规则各来一份报告。 */
    public List<ProfilerReport> reports() {
        List<ProfilerReport> reports = new ArrayList<>();
        for (String name : rules()) {
            reports.add(report(name));
        }
        return reports;
    }

    private static List<String> suggest(RuleProfile profile, List<FunctionHotspot> hotspots,
                                        long average, long p99) {
        List<String> suggestions = new ArrayList<>();
        if (!hotspots.isEmpty()) {
            FunctionHotspot top = hotspots.get(0);
            int share = (int) Math.round(top.share() * 100);
            if ("rate".equals(top.name())) {
                suggestions.add("rate 占用 " + share + "% 时间，同一时间窗被反复调用时可以改成滑动窗口增量累计"
                        + "（§2.4.2），别每次重新扫一遍事件列表");
            } else if (PrlStdlib.supports(top.name())) {
                suggestions.add("标准库函数 " + top.name() + " 占用 " + share + "% 时间，"
                        + "检查它是否在循环体内被重复调用，能提到循环外的就提出来");
            } else {
                suggestions.add(top.name() + " 占用 " + share + "% 时间，同一份输入上被重复调用时"
                        + "可以把结果提成一个 let 复用（引擎不做结果缓存）");
            }
        }
        if (profile.executions.get() > 0 && average > 0 && p99 > average * 5) {
            suggestions.add("P99 是平均值的 " + (p99 / average) + " 倍，输入规模差异较大，"
                    + "注意集合处理是否随输入线性增长（集合元素上限 10000，§2.11.1 L2）");
        }
        long memory = profile.memoryPeakBytes.get();
        if (memory > 64 * 1024) {
            suggestions.add("内存峰值 " + ProfilerReport.formatBytes(memory)
                    + "，输入集合偏大，建议在规则里先截断再统计");
        }
        return suggestions;
    }

    // ------------------------------------------------------------------ 观察者回调

    @Override
    public void onRuleStart(String ruleName) {
        ruleStack.get().push(ruleName);
    }

    @Override
    public void onRuleEnd(String ruleName, long elapsedNanos) {
        Deque<String> stack = ruleStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        RuleProfile profile = profiles.get(ruleName);
        if (profile != null) {
            profile.noteExecution(elapsedNanos);
        }
    }

    @Override
    public void onEnterFunction(String functionName) {
        callStack.get().push(new CallFrame(functionName));
    }

    @Override
    public void onExitFunction(String functionName, long elapsedNanos) {
        Deque<CallFrame> stack = callStack.get();
        if (stack.isEmpty()) {
            return;
        }
        CallFrame frame = stack.pop();
        // 自身耗时 = 整段耗时 - 期间各次「直接子调用」的耗时之和；子调用可能重叠不到，减法也可能
        // 因为时钟精度出现负数，夹到 0 而不是让热点列表出现一个负值。
        long self = Math.max(elapsedNanos - frame.childNanos, 0);
        RuleProfile profile = currentProfile();
        if (profile != null) {
            profile.noteFunction(frame.name, self);
        }
        // 子调用的耗时同时算进父函数的区间，这样父函数的「自身耗时」才真的是它自己花的。
        if (!stack.isEmpty()) {
            stack.peek().childNanos += elapsedNanos;
        }
    }

    /**
     * 把函数耗时归到当前规则名下。没处在某个 {@code onRuleStart/onRuleEnd} 之间时（直接调
     * {@code PrlVm.execute} 而中间没有规则上下文）不记账，避免并发执行时把耗时算到别人头上。
     */
    private RuleProfile currentProfile() {
        String ruleName = ruleStack.get().peek();
        return ruleName == null ? null : profiles.get(ruleName);
    }

    private RuleProfile profile(String ruleName) {
        return profiles.computeIfAbsent(ruleName, key -> new RuleProfile());
    }

    // ------------------------------------------------------------------ 内部结构

    /** 一个调用帧：函数名，以及它已「付给」子调用的耗时。 */
    private static final class CallFrame {

        final String name;
        long childNanos;

        CallFrame(String name) {
            this.name = name;
        }
    }

    /** 单条规则的采集状态。 */
    private static final class RuleProfile {

        final AtomicLong executions = new AtomicLong();
        final AtomicLong sumNanos = new AtomicLong();
        final AtomicLong maxNanos = new AtomicLong();
        final AtomicLong memoryPeakBytes = new AtomicLong();
        final AtomicLongArray histogram = new AtomicLongArray(BUCKET_COUNT);
        final Map<String, FunctionStat> functions = new ConcurrentHashMap<>();

        volatile String version;

        void noteExecution(long elapsedNanos) {
            executions.incrementAndGet();
            sumNanos.addAndGet(elapsedNanos);
            maxNanos.accumulateAndGet(elapsedNanos, Math::max);
            histogram.incrementAndGet(bucketOf(elapsedNanos));
        }

        void noteFunction(String functionName, long selfNanos) {
            functions.computeIfAbsent(functionName, FunctionStat::new).note(selfNanos);
        }

        void noteMemory(long bytes) {
            if (bytes > 0) {
                memoryPeakBytes.accumulateAndGet(bytes, Math::max);
            }
        }

        /** 按自身耗时降序的热点列表；占比以「全部函数自身耗时之和」为分母。 */
        List<FunctionHotspot> hotspots() {
            long total = functions.values().stream().mapToLong(stat -> stat.selfNanos.get()).sum();
            List<FunctionHotspot> hotspots = new ArrayList<>(functions.size());
            for (FunctionStat stat : functions.values()) {
                long self = stat.selfNanos.get();
                double share = total == 0 ? 0 : (double) self / total;
                hotspots.add(new FunctionHotspot(stat.name, stat.calls.get(), self, share));
            }
            hotspots.sort(Comparator.comparingLong(FunctionHotspot::selfNanos).reversed()
                    .thenComparing(FunctionHotspot::name));
            return hotspots;
        }

        /**
         * 第 {@code p} 百分位（0~1）。
         *
         * <p>取的是命中桶的<strong>上界</strong>：报出的数字只会偏保守，不会把慢的写成快的。</p>
         */
        long percentile(double p) {
            long count = executions.get();
            if (count == 0) {
                return 0;
            }
            long rank = (long) Math.ceil(p * count);
            long cumulative = 0;
            for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
                cumulative += histogram.get(bucket);
                if (cumulative >= rank) {
                    return upperBound(bucket);
                }
            }
            return maxNanos.get();
        }
    }

    private static final class FunctionStat {

        final String name;
        final AtomicLong calls = new AtomicLong();
        final AtomicLong selfNanos = new AtomicLong();

        FunctionStat(String name) {
            this.name = name;
        }

        void note(long selfNanos) {
            calls.incrementAndGet();
            this.selfNanos.addAndGet(selfNanos);
        }
    }

    // ------------------------------------------------------------------ 直方图

    private static int bucketOf(long nanos) {
        if (nanos <= 0) {
            return 0;
        }
        int exponent = 63 - Long.numberOfLeadingZeros(nanos);
        int mantissa = (int) ((nanos >>> Math.max(exponent - 2, 0)) & (BUCKETS_PER_OCTAVE - 1));
        return Math.min(exponent * BUCKETS_PER_OCTAVE + mantissa, BUCKET_COUNT - 1);
    }

    /** 桶的上界：{@code 2^exp × (9 + mantissa) / 8}，与 {@link #bucketOf} 的划分对齐。 */
    private static long upperBound(int bucket) {
        int exponent = bucket / BUCKETS_PER_OCTAVE;
        int mantissa = bucket % BUCKETS_PER_OCTAVE;
        long base = 1L << exponent;
        return base + (base * (mantissa + 1)) / BUCKETS_PER_OCTAVE;
    }
}