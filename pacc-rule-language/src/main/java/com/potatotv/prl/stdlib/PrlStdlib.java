package com.potatotv.prl.stdlib;

import com.potatotv.prl.engine.DetectionResult;
import com.potatotv.prl.runtime.PrlCallable;
import com.potatotv.prl.runtime.PrlExecutionException;
import com.potatotv.prl.runtime.PrlSecurityException;
import com.potatotv.prl.runtime.PrlValues;
import com.potatotv.prl.sandbox.PrlHostContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * PRL 标准库（设计文档 §2.4），VM 在宿主白名单 miss 之后调用的兜底层。
 *
 * <p>整层刻意保持「无状态 + 无第三方依赖」：所有函数都是纯计算，唯一的例外是 {@link #random}
 * 的实例级 {@link Random} 与 §2.4.7 的动作函数（副作用通过 {@link PrlHostContext} 的 default 方法
 * 交还给宿主）。{@code random} 必须确定性可复现（§2.4.5），所以 VM 每条规则新建一个实例、
 * 用规则名派生种子；{@link #withSeed(long)} 就是为这个用法准备的。</p>
 *
 * <p>几处与文档字面不同的取舍，都写在对应方法的注释里，汇总如下：</p>
 * <ol>
 *   <li>{@code min}/{@code max}/{@code sum} 文档写 {@code (list[float]) -> float}，但 §2.4.1 与
 *       类型表都给了 int 重载；这里按「元素全是 {@link Long} 则返回 {@link Long}，否则 {@link Double}」
 *       决定返回类型，空表按题面要求给 {@code 0.0}/{@code 0L}；</li>
 *   <li>{@code stddev}/{@code variance} 取<strong>总体</strong>口径（除以 n），不是样本口径（n-1）——</li>
 *   <li>{@code analyze_click_pattern} §2.4.6 签名是 {@code list[ClickEvent]}，但 §2.2.1 的示例规则
 *       传的是 {@code list[AttackEvent]}；两处统一按「元素有 {@code time_ms} 就读」处理；</li>
 *   <li>{@code log} 在 §2.4.5（数学对数）与 §2.4.7（写日志）里重名，按第一个实参的运行时类型分派；
 *   </li>
 *   <li>§2.4.6 的检测函数文档只给名字不给公式，全部取可解释的启发式，阈值是命名常量并注明来源。</li>
 * </ol>
 */
public final class PrlStdlib {

    /** §2.11.1 L2 的集合元素上限，滑动窗口结果超过它就拒绝。 */
    private static final int COLLECTION_LIMIT = 10_000;

    private static final double LOG2 = Math.log(2.0);

    /** §2.4.6 自瞄启发式：角速度规整度权重（文档未给数值）。 */
    private static final double AIM_REGULARITY_WEIGHT = 0.6;
    /** §2.4.6 自瞄启发式：精度权重。 */
    private static final double AIM_PRECISION_WEIGHT = 0.4;
    /** §2.4.6 加速启发式上限（格/秒）。原版疾跑约 5.6 格/秒，取 10 作为宽容上限。 */
    private static final double SPEED_HACK_LIMIT = 10.0;
    /** §2.4.6 reach 上限：生存模式原版攻击距离 3.0 格。 */
    private static final double REACH_LIMIT = 3.0;
    /** §2.4.6 连点器启发式：间隔标准差低于这个值（ms）视作「抖动极小」。 */
    private static final double AUTOCLICK_STDDEV_REF = 10.0;
    private static final double AUTOCLICK_CPS_MIN = 8.0;
    private static final double AUTOCLICK_CPS_MAX = 20.0;
    private static final double AUTOCLICK_VARIANCE_WEIGHT = 0.6;
    private static final double AUTOCLICK_CPS_WEIGHT = 0.4;

    private static final Object[] NO_ARGS = new Object[0];

    /** §2.4 的全部函数名（同名重载只记一次）。 */
    private static final Set<String> SUPPORTED = Set.of(
            "average", "median", "stddev", "variance", "min", "max", "sum", "percentile", "count",
            "frequency", "correlation", "zscore", "outliers",
            "rate", "sliding_window", "burst_count", "interval_stats", "trend", "change_rate",
            "filter", "map", "reduce", "sort", "group_by", "first", "last", "take", "skip",
            "contains", "unique", "flatten", "zip",
            "length", "starts_with", "ends_with", "regex_match", "regex_extract", "to_lower",
            "to_upper", "trim", "split", "join", "levenshtein", "similarity",
            "abs", "round", "floor", "ceil", "clamp", "sqrt", "pow", "log", "sin", "cos", "tan",
            "random",
            "analyze_click_pattern", "detect_aim_assist", "detect_speed_hack", "detect_fly",
            "detect_reach", "detect_autoclicker", "is_human_click_pattern", "entropy",
            "cps_variance", "reaction_time",
            "emit_alert", "record_evidence", "trigger_redscreen", "ban_feature",
            // §2.2.1 的示例规则用到、§2.4 的函数表没列，签名表里有就得能调
            "accuracy", "last_n");

    private final PrlHostContext host;
    private final Random random;

    public PrlStdlib() {
        this(PrlHostContext.EMPTY, 0L);
    }

    public PrlStdlib(PrlHostContext host) {
        this(host, 0L);
    }

    public PrlStdlib(PrlHostContext host, long seed) {
        this.host = host == null ? PrlHostContext.EMPTY : host;
        this.random = new Random(seed);
    }

    /** 复制实例、只换随机种子（host 不变），VM 每条规则用它派生确定性种子。 */
    public PrlStdlib withSeed(long seed) {
        return new PrlStdlib(host, seed);
    }

    /** 该函数名是否由标准库兜底。 */
    public static boolean supports(String name) {
        return name != null && SUPPORTED.contains(name);
    }

    /** 标准库函数名，字典序。管理端的自动补全与标准库参考文档都从这里取（§2.16）。 */
    public static List<String> functionNames() {
        List<String> names = new ArrayList<>(SUPPORTED);
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }

    /** 分发表。 */
    public Object call(String name, Object[] args) {
        return switch (name) {
            // ---- §2.4.1 统计 ----
            case "average" -> average(requireArity(name, args, 1));
            case "median" -> medianOfArgs(requireArity(name, args, 1));
            case "stddev" -> stddevOfArgs(requireArity(name, args, 1));
            case "variance" -> varianceOfArgs(requireArity(name, args, 1));
            case "min" -> extreme(requireArity(name, args, 1), true);
            case "max" -> extreme(requireArity(name, args, 1), false);
            case "sum" -> sum(requireArity(name, args, 1));
            case "percentile" -> percentile(requireArity(name, args, 2));
            case "count" -> (long) PrlValues.asList(args[0]).size();
            case "frequency" -> frequency(requireArity(name, args, 1));
            case "correlation" -> correlation(requireArity(name, args, 2));
            case "zscore" -> zscore(requireArity(name, args, 2));
            case "outliers" -> outliers(requireArity(name, args, 2));
            // ---- §2.4.2 时序 ----
            case "rate" -> rate(requireArity(name, args, 2));
            case "sliding_window" -> slidingWindow(requireArity(name, args, 2));
            case "burst_count" -> burstCount(requireArity(name, args, 3));
            case "interval_stats" -> intervalStats(requireArity(name, args, 1));
            case "trend" -> trend(requireArity(name, args, 1));
            case "change_rate" -> changeRate(requireArity(name, args, 1));
            // ---- §2.4.3 集合 ----
            case "filter" -> filter(requireArity(name, args, 2));
            case "map" -> mapValues(requireArity(name, args, 2));
            case "reduce" -> reduce(requireArity(name, args, 3));
            case "sort" -> sortValues(requireArity(name, args, 2));
            case "group_by" -> groupBy(requireArity(name, args, 2));
            case "first" -> first(requireArity(name, args, 1));
            case "last" -> last(requireArity(name, args, 1));
            case "take" -> take(requireArity(name, args, 2));
            case "skip" -> skip(requireArity(name, args, 2));
            case "contains" -> contains(requireArity(name, args, 2));
            case "unique" -> unique(requireArity(name, args, 1));
            case "flatten" -> flatten(requireArity(name, args, 1));
            case "zip" -> zip(requireArity(name, args, 2));
            // ---- §2.4.4 字符串 ----
            case "length" -> (long) PrlValues.length(args[0]);
            case "starts_with" -> PrlValues.asString(args[0]).startsWith(PrlValues.asString(args[1]));
            case "ends_with" -> PrlValues.asString(args[0]).endsWith(PrlValues.asString(args[1]));
            case "regex_match" -> regexMatch(requireArity(name, args, 2));
            case "regex_extract" -> regexExtract(requireArity(name, args, 2));
            case "to_lower" -> PrlValues.asString(args[0]).toLowerCase(Locale.ROOT);
            case "to_upper" -> PrlValues.asString(args[0]).toUpperCase(Locale.ROOT);
            case "trim" -> PrlValues.asString(args[0]).trim();
            case "split" -> split(requireArity(name, args, 2));
            case "join" -> join(requireArity(name, args, 2));
            case "levenshtein" -> (long) levenshteinDistance(
                    PrlValues.asString(args[0]), PrlValues.asString(args[1]));
            case "similarity" -> similarity(requireArity(name, args, 2));
            // ---- §2.4.5 数学 ----
            case "abs" -> abs(args[0]);
            case "round" -> roundValue(requireArity(name, args, 2));
            case "floor" -> (long) Math.floor(PrlValues.asDouble(args[0]));
            case "ceil" -> (long) Math.ceil(PrlValues.asDouble(args[0]));
            case "clamp" -> clamp(requireArity(name, args, 3));
            case "sqrt" -> Math.sqrt(PrlValues.asDouble(args[0]));
            case "pow" -> Math.pow(PrlValues.asDouble(args[0]), PrlValues.asDouble(args[1]));
            case "log" -> logFunction(requireArity(name, args, 2));
            case "sin" -> Math.sin(PrlValues.asDouble(args[0]));
            case "cos" -> Math.cos(PrlValues.asDouble(args[0]));
            case "tan" -> Math.tan(PrlValues.asDouble(args[0]));
            case "random" -> randomInt(requireArity(name, args, 2));
            // ---- §2.4.6 PACC 专属 ----
            case "analyze_click_pattern" -> analyzeClickPattern(requireArity(name, args, 1));
            case "detect_aim_assist" -> detectAimAssist(requireArity(name, args, 1));
            case "detect_speed_hack" -> detectSpeedHack(requireArity(name, args, 1));
            case "detect_fly" -> detectFly(requireArity(name, args, 1));
            case "detect_reach" -> detectReach(requireArity(name, args, 1));
            case "detect_autoclicker" -> detectAutoclicker(requireArity(name, args, 1));
            case "is_human_click_pattern" -> isHumanClickPattern(requireArity(name, args, 1));
            case "entropy" -> entropy(requireArity(name, args, 1));
            case "cps_variance" -> cpsVariance(requireArity(name, args, 1));
            case "reaction_time" -> reactionTime(requireArity(name, args, 1));
            // ---- §2.4.7 动作 ----
            case "emit_alert" -> emitAlert(requireArity(name, args, 3));
            case "record_evidence" -> recordEvidence(requireArity(name, args, 2));
            case "trigger_redscreen" -> triggerRedScreen(requireArity(name, args, 1));
            case "ban_feature" -> banFeature(requireArity(name, args, 2));
            // ---- §2.2.1 示例规则用到、§2.4 未列出的两个 ----
            case "accuracy" -> accuracy(requireArity(name, args, 1));
            case "last_n" -> lastN(requireArity(name, args, 2));
            default -> throw new PrlSecurityException("标准库不提供函数 '" + name + "'");
        };
    }

    private static Object[] requireArity(String name, Object[] args, int expected) {
        if (args == null || args.length != expected) {
            throw new PrlExecutionException("函数 '" + name + "' 需要 " + expected + " 个参数，实际 "
                    + (args == null ? 0 : args.length) + " 个");
        }
        return args;
    }

    // ------------------------------------------------------------------ §2.4.1 统计

    private Object average(Object[] args) {
        double[] values = doubles(args[0]);
        return values.length == 0 ? 0.0 : mean(values);
    }

    private Object medianOfArgs(Object[] args) {
        double[] values = doubles(args[0]);
        return values.length == 0 ? 0.0 : median(values);
    }

    private Object stddevOfArgs(Object[] args) {
        double[] values = doubles(args[0]);
        return values.length == 0 ? 0.0 : stddev(values);
    }

    private Object varianceOfArgs(Object[] args) {
        double[] values = doubles(args[0]);
        return values.length == 0 ? 0.0 : variance(values);
    }

    /**
     * min/max 的返回类型按元素决定：全是 {@link Long} 返回 {@link Long}，否则 {@link Double}。
     * 空表按题面要求返回 {@code 0.0}（签名表给 int 重载也接受它，冷启动时规则常拿空列表试探）。
     */
    private Object extreme(Object[] args, boolean min) {
        List<Object> elements = PrlValues.asList(args[0]);
        if (elements.isEmpty()) {
            return 0.0;
        }
        boolean allInt = allIntegers(elements);
        double best = min ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        for (Object element : elements) {
            double value = PrlValues.asDouble(element);
            best = min ? Math.min(best, value) : Math.max(best, value);
        }
        return allInt ? (Object) (long) best : (Object) best;
    }

    private Object sum(Object[] args) {
        List<Object> elements = PrlValues.asList(args[0]);
        if (elements.isEmpty()) {
            return 0L;
        }
        boolean allInt = allIntegers(elements);
        long intSum = 0L;
        double floatSum = 0.0;
        for (Object element : elements) {
            if (allInt) {
                intSum += PrlValues.asLong(element);
            } else {
                floatSum += PrlValues.asDouble(element);
            }
        }
        return allInt ? (Object) intSum : (Object) floatSum;
    }

    /** 线性插值口径（与 numpy 的默认 'linear' 一致），百分位必须落在 0~100。 */
    private Object percentile(Object[] args) {
        double percent = PrlValues.asDouble(args[1]);
        if (percent < 0 || percent > 100) {
            throw new PrlExecutionException("percentile 的百分位必须在 0~100 之间，实际为 " + percent);
        }
        double[] values = doubles(args[0]);
        if (values.length == 0) {
            return 0.0;
        }
        Arrays.sort(values);
        return percentileOfSorted(values, percent);
    }

    private Object frequency(Object[] args) {
        List<Object> elements = PrlValues.asList(args[0]);
        // 归并按 hashKey 做（宿主可能给 Integer，规则侧一律是 Long），但写出去的键要还原成 PRL 值：
        // 声明类型是 map[T, int]，键里塞 Double 会让规则侧 freq[1] 直接查不到。
        Map<Object, Object> keys = new LinkedHashMap<>();
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Object element : elements) {
            Object identity = PrlValues.hashKey(element);
            Object key = keys.computeIfAbsent(identity,
                    ignored -> PrlValues.isInteger(element) ? PrlValues.asLong(element) : element);
            Object previous = result.get(key);
            result.put(key, previous instanceof Long count ? count + 1L : 1L);
        }
        return result;
    }

    private Object correlation(Object[] args) {
        double[] xs = doubles(args[0]);
        double[] ys = doubles(args[1]);
        int n = Math.min(xs.length, ys.length);
        if (n == 0) {
            return 0.0;
        }
        double meanX = 0.0;
        double meanY = 0.0;
        for (int i = 0; i < n; i++) {
            meanX += xs[i];
            meanY += ys[i];
        }
        meanX /= n;
        meanY /= n;
        double covariance = 0.0;
        double varianceX = 0.0;
        double varianceY = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = xs[i] - meanX;
            double dy = ys[i] - meanY;
            covariance += dx * dy;
            varianceX += dx * dx;
            varianceY += dy * dy;
        }
        // 任一侧零方差时相关系数无定义，给 0.0 而不是 NaN
        if (varianceX == 0.0 || varianceY == 0.0) {
            return 0.0;
        }
        return covariance / Math.sqrt(varianceX * varianceY);
    }

    private Object zscore(Object[] args) {
        double[] values = doubles(args[1]);
        if (values.length == 0) {
            return 0.0;
        }
        double sd = stddev(values);
        if (sd == 0.0) {
            return 0.0;
        }
        return (PrlValues.asDouble(args[0]) - mean(values)) / sd;
    }

    /** IQR 法：Q1/Q3 用线性插值，落在 [Q1 - factor*IQR, Q3 + factor*IQR] 之外的下标即异常。 */
    private Object outliers(Object[] args) {
        double[] original = doubles(args[0]);
        double factor = PrlValues.asDouble(args[1]);
        List<Object> result = new ArrayList<>();
        if (original.length == 0) {
            return result;
        }
        double[] sorted = original.clone();
        Arrays.sort(sorted);
        double q1 = percentileOfSorted(sorted, 25);
        double q3 = percentileOfSorted(sorted, 75);
        double iqr = q3 - q1;
        double lower = q1 - factor * iqr;
        double upper = q3 + factor * iqr;
        for (int i = 0; i < original.length; i++) {
            if (original[i] < lower || original[i] > upper) {
                result.add((long) i);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ §2.4.2 时序

    /**
     * 单位时间事件数。文档 §2.4 只写「单位时间（每秒）」，这里统一归一到每秒：
     * {@code (事件数 - 1) * 1000 / 首尾 time_ms 之差}。用区间数（n-1）而不是 n，是因为 n 个时间点
     * 只划分出 n-1 个区间，两个相差 1s 的事件应当是 1 次/秒。跨度读不到或 <=0 时返回 0.0。
     * window 参数只为对齐 §2.4 签名，速率本身固定按秒归一。
     */
    private Object rate(Object[] args) {
        List<Object> events = PrlValues.asList(args[0]);
        long windowMillis = PrlValues.asLong(args[1]);
        if (windowMillis <= 0) {
            throw new PrlExecutionException("rate 的时间窗口必须为正，实际为 " + windowMillis + "ms");
        }
        if (events.isEmpty()) {
            return 0.0;
        }
        Long first = readLong(host, events.get(0), "time_ms");
        Long last = readLong(host, events.get(events.size() - 1), "time_ms");
        if (first == null || last == null) {
            return 0.0;
        }
        long span = last - first;
        if (span <= 0) {
            return 0.0;
        }
        return (events.size() - 1) * 1000.0 / span;
    }

    /**
     * 以每个事件为起点、宽度为 window 毫秒的窗口子列表。要求事件按 {@code time_ms} 升序
     * （宿主按时间顺序投喂）。结果总元素数受 §2.11.1 L2 的 10000 上限约束，超限直接拒绝。
     */
    private Object slidingWindow(Object[] args) {
        List<Object> events = PrlValues.asList(args[0]);
        long windowMillis = PrlValues.asLong(args[1]);
        if (windowMillis <= 0) {
            throw new PrlExecutionException("sliding_window 的窗口必须为正，实际为 " + windowMillis + "ms");
        }
        List<Object> result = new ArrayList<>();
        if (events.isEmpty()) {
            return result;
        }
        long[] times = requiredTimes(events, "sliding_window");
        long total = 0L;
        for (int i = 0; i < times.length; i++) {
            List<Object> window = new ArrayList<>();
            for (int j = i; j < times.length && times[j] - times[i] < windowMillis; j++) {
                window.add(events.get(j));
            }
            total += window.size();
            if (total > COLLECTION_LIMIT) {
                throw new PrlExecutionException("sliding_window 结果超过 " + COLLECTION_LIMIT
                        + " 元素上限（§2.11.1 L2）");
            }
            result.add(window);
        }
        return result;
    }

    /**
     * 突发次数：任意 window 毫秒内事件数 >= threshold 即为一次突发，时间上重叠的突发合并。
     * 用「窗口右端点」判重叠：新突发起点落在上一突发覆盖区间内就合并。
     */
    private Object burstCount(Object[] args) {
        List<Object> events = PrlValues.asList(args[0]);
        long windowMillis = PrlValues.asLong(args[1]);
        long threshold = PrlValues.asLong(args[2]);
        if (windowMillis <= 0) {
            throw new PrlExecutionException("burst_count 的窗口必须为正，实际为 " + windowMillis + "ms");
        }
        if (threshold <= 0) {
            throw new PrlExecutionException("burst_count 的阈值必须为正，实际为 " + threshold);
        }
        if (events.isEmpty()) {
            return 0L;
        }
        long[] times = requiredTimes(events, "burst_count");
        Arrays.sort(times);
        long bursts = 0L;
        long coveredUntil = Long.MIN_VALUE;
        for (int i = 0; i < times.length; i++) {
            long windowEnd = times[i] + windowMillis;
            int count = 0;
            for (int j = i; j < times.length && times[j] < windowEnd; j++) {
                count++;
            }
            if (count >= threshold) {
                if (bursts == 0 || times[i] >= coveredUntil) {
                    bursts++;
                    coveredUntil = windowEnd;
                } else {
                    coveredUntil = Math.max(coveredUntil, windowEnd);
                }
            }
        }
        return bursts;
    }

    private Object intervalStats(Object[] args) {
        List<Object> events = PrlValues.asList(args[0]);
        if (events.size() < 2) {
            return new StatsValue(0.0, 0.0, 0.0, 0L);
        }
        List<Long> times = readTimes(host, events);
        if (times.size() < 2) {
            return new StatsValue(0.0, 0.0, 0.0, 0L);
        }
        double[] intervals = intervalsOf(times);
        return new StatsValue(mean(intervals), stddev(intervals), median(intervals), intervals.length);
    }

    /** x 取下标 0..n-1，最小二乘斜率。 */
    private Object trend(Object[] args) {
        double[] values = doubles(args[0]);
        int n = values.length;
        if (n < 2) {
            return 0.0;
        }
        double meanX = (n - 1) / 2.0;
        double meanY = mean(values);
        double numerator = 0.0;
        double denominator = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = i - meanX;
            numerator += dx * (values[i] - meanY);
            denominator += dx * dx;
        }
        return denominator == 0.0 ? 0.0 : numerator / denominator;
    }

    private Object changeRate(Object[] args) {
        double[] values = doubles(args[0]);
        if (values.length == 0) {
            return 0.0;
        }
        double first = values[0];
        if (first == 0.0) {
            return 0.0;
        }
        return (values[values.length - 1] - first) / Math.abs(first);
    }

    // ------------------------------------------------------------------ §2.4.3 集合

    private Object filter(Object[] args) {
        List<Object> values = PrlValues.asList(args[0]);
        PrlCallable predicate = PrlValues.asCallable(args[1]);
        List<Object> result = new ArrayList<>();
        for (Object value : values) {
            if (PrlValues.asBool(predicate.call(new Object[]{value}))) {
                result.add(value);
            }
        }
        return result;
    }

    private Object mapValues(Object[] args) {
        List<Object> values = PrlValues.asList(args[0]);
        PrlCallable transform = PrlValues.asCallable(args[1]);
        List<Object> result = new ArrayList<>(values.size());
        for (Object value : values) {
            result.add(transform.call(new Object[]{value}));
        }
        return result;
    }

    private Object reduce(Object[] args) {
        List<Object> values = PrlValues.asList(args[0]);
        Object accumulator = args[1];
        PrlCallable folder = PrlValues.asCallable(args[2]);
        for (Object value : values) {
            accumulator = folder.call(new Object[]{accumulator, value});
        }
        return accumulator;
    }

    /** 第二参不是函数时退化按 {@link PrlValues#compare} 升序；始终返回新列表，不动原表。 */
    private Object sortValues(Object[] args) {
        List<Object> values = PrlValues.asList(args[0]);
        List<Object> copy = new ArrayList<>(values);
        if (args[1] instanceof PrlCallable comparator) {
            copy.sort((left, right) -> (int) PrlValues.asLong(comparator.call(new Object[]{left, right})));
        } else {
            copy.sort(PrlValues::compare);
        }
        return copy;
    }

    private Object groupBy(Object[] args) {
        List<Object> values = PrlValues.asList(args[0]);
        PrlCallable keyFunction = PrlValues.asCallable(args[1]);
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Object value : values) {
            Object key = keyFunction.call(new Object[]{value});
            @SuppressWarnings("unchecked")
            List<Object> bucket = (List<Object>) result.computeIfAbsent(key, ignored -> new ArrayList<>());
            bucket.add(value);
        }
        return result;
    }

    private Object first(Object[] args) {
        List<Object> values = PrlValues.asList(args[0]);
        if (values.isEmpty()) {
            throw new PrlExecutionException("first 需要非空集合");
        }
        return values.get(0);
    }

    private Object last(Object[] args) {
        List<Object> values = PrlValues.asList(args[0]);
        if (values.isEmpty()) {
            throw new PrlExecutionException("last 需要非空集合");
        }
        return values.get(values.size() - 1);
    }

    private Object take(Object[] args) {
        List<Object> values = PrlValues.asList(args[0]);
        long n = PrlValues.asLong(args[1]);
        if (n <= 0) {
            return new ArrayList<>();
        }
        int size = (int) Math.min(n, values.size());
        return new ArrayList<>(values.subList(0, size));
    }

    private Object skip(Object[] args) {
        List<Object> values = PrlValues.asList(args[0]);
        long n = PrlValues.asLong(args[1]);
        if (n <= 0) {
            return new ArrayList<>(values);
        }
        if (n >= values.size()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(values.subList((int) n, values.size()));
    }

    /** 重载：第一参是 string 判子串，是 list 判元素（`==` 语义）。 */
    private Object contains(Object[] args) {
        if (args[0] instanceof String text) {
            return text.contains(PrlValues.asString(args[1]));
        }
        for (Object value : PrlValues.asList(args[0])) {
            if (PrlValues.equalsValue(value, args[1])) {
                return true;
            }
        }
        return false;
    }

    private Object unique(Object[] args) {
        Set<Object> seen = new LinkedHashSet<>();
        List<Object> result = new ArrayList<>();
        for (Object value : PrlValues.asList(args[0])) {
            if (seen.add(PrlValues.hashKey(value))) {
                result.add(value);
            }
        }
        return result;
    }

    private Object flatten(Object[] args) {
        List<Object> result = new ArrayList<>();
        for (Object inner : PrlValues.asList(args[0])) {
            result.addAll(PrlValues.asList(inner));
        }
        return result;
    }

    /** 元素是长度 2 的 list（tuple 在运行时的表示），长度取两者较短。 */
    private Object zip(Object[] args) {
        List<Object> left = PrlValues.asList(args[0]);
        List<Object> right = PrlValues.asList(args[1]);
        int size = Math.min(left.size(), right.size());
        List<Object> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            result.add(PrlValues.list(left.get(i), right.get(i)));
        }
        return result;
    }

    // ------------------------------------------------------------------ §2.4.4 字符串

    private Object regexMatch(Object[] args) {
        Matcher matcher = compile(PrlValues.asString(args[1])).matcher(PrlValues.asString(args[0]));
        return matcher.find();
    }

    /** 有捕获组取第 1 组，无捕获组取整个匹配。 */
    private Object regexExtract(Object[] args) {
        Matcher matcher = compile(PrlValues.asString(args[1])).matcher(PrlValues.asString(args[0]));
        List<Object> result = new ArrayList<>();
        while (matcher.find()) {
            result.add(matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group());
        }
        return result;
    }

    private Pattern compile(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new PrlExecutionException("非法正则表达式：" + pattern, e);
        }
    }

    /** 用 {@link Pattern#quote} 避免分隔符被当正则；空分隔符按单个字符切。 */
    private Object split(Object[] args) {
        String text = PrlValues.asString(args[0]);
        String separator = PrlValues.asString(args[1]);
        List<Object> result = new ArrayList<>();
        if (separator.isEmpty()) {
            for (int i = 0; i < text.length(); i++) {
                result.add(String.valueOf(text.charAt(i)));
            }
            return result;
        }
        for (String part : text.split(Pattern.quote(separator))) {
            result.add(part);
        }
        return result;
    }

    private Object join(Object[] args) {
        List<Object> parts = PrlValues.asList(args[0]);
        String separator = PrlValues.asString(args[1]);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                builder.append(separator);
            }
            builder.append(PrlValues.asString(parts.get(i)));
        }
        return builder.toString();
    }

    private Object similarity(Object[] args) {
        String left = PrlValues.asString(args[0]);
        String right = PrlValues.asString(args[1]);
        int max = Math.max(left.length(), right.length());
        if (max == 0) {
            return 1.0;
        }
        return 1.0 - (double) levenshteinDistance(left, right) / max;
    }

    /** 标准 DP，滚动一维数组。 */
    private static int levenshteinDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    // ------------------------------------------------------------------ §2.4.5 数学

    /** int 进 int 出，float 进 float 出。 */
    private Object abs(Object value) {
        if (PrlValues.isInteger(value)) {
            return Math.abs(PrlValues.asLong(value));
        }
        return Math.abs(PrlValues.asDouble(value));
    }

    private Object roundValue(Object[] args) {
        double value = PrlValues.asDouble(args[0]);
        int digits = (int) PrlValues.asLong(args[1]);
        // 走 BigDecimal 而不是 value*10^digits：避免二进制浮点在 .5 边界上舍错
        return new BigDecimal(Double.toString(value)).setScale(digits, RoundingMode.HALF_UP).doubleValue();
    }

    /** int/float 各自保持类型；lo > hi 视为参数非法。 */
    private Object clamp(Object[] args) {
        if (PrlValues.isInteger(args[0]) && PrlValues.isInteger(args[1]) && PrlValues.isInteger(args[2])) {
            long value = PrlValues.asLong(args[0]);
            long lower = PrlValues.asLong(args[1]);
            long upper = PrlValues.asLong(args[2]);
            if (lower > upper) {
                throw new PrlExecutionException("clamp 的下界不能大于上界：" + lower + " > " + upper);
            }
            return Math.max(lower, Math.min(upper, value));
        }
        double value = PrlValues.asDouble(args[0]);
        double lower = PrlValues.asDouble(args[1]);
        double upper = PrlValues.asDouble(args[2]);
        if (lower > upper) {
            throw new PrlExecutionException("clamp 的下界不能大于上界：" + lower + " > " + upper);
        }
        return Math.max(lower, Math.min(upper, value));
    }

    /** 名字冲突：§2.4.5 的 log(x, base) 与 §2.4.7 的 log(level, message) 同名，按第一个实参分派。 */
    private Object logFunction(Object[] args) {
        if (args[0] instanceof String level) {
            host.log(level, PrlValues.asString(args[1]));
            return null;
        }
        double value = PrlValues.asDouble(args[0]);
        double base = PrlValues.asDouble(args[1]);
        if (value <= 0) {
            throw new PrlExecutionException("log 的真数必须为正，实际为 " + value);
        }
        if (base <= 0 || base == 1.0) {
            throw new PrlExecutionException("log 的底数必须为正且不等于 1，实际为 " + base);
        }
        return Math.log(value) / Math.log(base);
    }

    /** 含两端；用实例级 Random 保证同种子可复现（§2.4.5）。 */
    private Object randomInt(Object[] args) {
        long lower = PrlValues.asLong(args[0]);
        long upper = PrlValues.asLong(args[1]);
        if (lower > upper) {
            throw new PrlExecutionException("random 的下界不能大于上界：" + lower + " > " + upper);
        }
        long range = upper - lower + 1;
        // range <= 0 只在整型溢出（跨越整个 long 区间）时出现，退化为返回下界
        return range <= 0 ? lower : lower + random.nextLong(range);
    }

    // ------------------------------------------------------------------ §2.4.6 PACC 专属

    /**
     * 文档 §2.4.6 签名是 {@code list[ClickEvent]}，§2.2.1 的示例规则却传 {@code list[AttackEvent]}。
     * 两者都有 {@code time_ms}，这里不区分具体类型，只读时间戳；<br>
     * cps 取「平均点击间隔的倒数」（1000 / meanInterval），interval_stddev 是间隔的总体标准差。
     */
    private Object analyzeClickPattern(Object[] args) {
        List<Object> events = PrlValues.asList(args[0]);
        List<Long> times = readTimes(host, events);
        if (times.size() < 2) {
            return new ClickPatternValue(0.0, 0.0);
        }
        double[] intervals = intervalsOf(times);
        double averageInterval = mean(intervals);
        double cps = averageInterval > 0 ? 1000.0 / averageInterval : 0.0;
        return new ClickPatternValue(cps, stddev(intervals));
    }

    /**
     * 自瞄启发式（文档未给公式）：转向增量越规整越可疑，配合 precision 越高越可疑。
     * regularity = 1 - 变异系数(sd/mean)，再与平均 precision 加权。
     */
    private Object detectAimAssist(Object[] args) {
        List<Object> events = PrlValues.asList(args[0]);
        List<Double> magnitudes = new ArrayList<>();
        double precisionSum = 0.0;
        int precisionCount = 0;
        for (Object event : events) {
            Double yaw = readDouble(host, event, "delta_yaw");
            Double pitch = readDouble(host, event, "delta_pitch");
            if (yaw != null && pitch != null) {
                magnitudes.add(Math.hypot(yaw, pitch));
            }
            Double precision = readDouble(host, event, "precision");
            if (precision != null) {
                precisionSum += precision;
                precisionCount++;
            }
        }
        if (magnitudes.isEmpty()) {
            return 0.0;
        }
        double[] values = toArray(magnitudes);
        double averageMagnitude = mean(values);
        double coefficient = averageMagnitude > 0 ? stddev(values) / averageMagnitude : 1.0;
        double regularity = clamp01(1.0 - coefficient);
        double precision = precisionCount > 0 ? clamp01(precisionSum / precisionCount) : 0.0;
        return clamp01(AIM_REGULARITY_WEIGHT * regularity + AIM_PRECISION_WEIGHT * precision);
    }

    /** 加速启发式（文档未给数值）：speed 超过 {@link #SPEED_HACK_LIMIT} 的事件占比。 */
    private Object detectSpeedHack(Object[] args) {
        List<Object> events = PrlValues.asList(args[0]);
        int total = 0;
        int over = 0;
        for (Object event : events) {
            Double speed = readDouble(host, event, "speed");
            if (speed == null) {
                continue;
            }
            total++;
            if (speed > SPEED_HACK_LIMIT) {
                over++;
            }
        }
        return total == 0 ? 0.0 : (double) over / total;
    }

    /** 飞行启发式：is_airborne() 为真的事件占比（跳跃、下落也会计入，仅作线索）。 */
    private Object detectFly(Object[] args) {
        List<Object> events = PrlValues.asList(args[0]);
        int total = 0;
        int airborne = 0;
        for (Object event : events) {
            Boolean value = readBoolMethod(host, event, "is_airborne");
            if (value == null) {
                continue;
            }
            total++;
            if (value) {
                airborne++;
            }
        }
        return total == 0 ? 0.0 : (double) airborne / total;
    }

    /** reach 超过原版 3.0 格的事件占比。 */
    private Object detectReach(Object[] args) {
        List<Object> events = PrlValues.asList(args[0]);
        int total = 0;
        int over = 0;
        for (Object event : events) {
            Double reach = readDouble(host, event, "reach");
            if (reach == null) {
                continue;
            }
            total++;
            if (reach > REACH_LIMIT) {
                over++;
            }
        }
        return total == 0 ? 0.0 : (double) over / total;
    }

    /**
     * 连点器启发式（文档未给数值）：间隔抖动极小 + cps 偏高。
     * lowVariance = 1 - sd/{@link #AUTOCLICK_STDDEV_REF}，highCps 在
     * {@link #AUTOCLICK_CPS_MIN}~{@link #AUTOCLICK_CPS_MAX} 之间线性归一，两者加权。
     */
    private Object detectAutoclicker(Object[] args) {
        List<Object> events = PrlValues.asList(args[0]);
        List<Long> times = readTimes(host, events);
        if (times.size() < 2) {
            return 0.0;
        }
        double[] intervals = intervalsOf(times);
        double averageInterval = mean(intervals);
        double cps = averageInterval > 0 ? 1000.0 / averageInterval : 0.0;
        double lowVariance = clamp01(1.0 - stddev(intervals) / AUTOCLICK_STDDEV_REF);
        double highCps = clamp01((cps - AUTOCLICK_CPS_MIN) / (AUTOCLICK_CPS_MAX - AUTOCLICK_CPS_MIN));
        return clamp01(AUTOCLICK_VARIANCE_WEIGHT * lowVariance + AUTOCLICK_CPS_WEIGHT * highCps);
    }

    /** 与 {@link ClickPatternValue#isHumanLike} 共用阈值，保证两条判据一致。 */
    private Object isHumanClickPattern(Object[] args) {
        Object pattern = args[0];
        Double cps = readDouble(host, pattern, "cps");
        Double intervalStddev = readDouble(host, pattern, "interval_stddev");
        if (cps == null || intervalStddev == null) {
            throw new PrlExecutionException("is_human_click_pattern 需要带 cps/interval_stddev 的 ClickPattern，实际为 "
                    + PrlValues.describe(pattern));
        }
        return ClickPatternValue.isHumanLike(cps, intervalStddev);
    }

    /** 香农熵（bit）：按 {@link PrlValues#hashKey} 计数取概率。 */
    private Object entropy(Object[] args) {
        List<Object> values = PrlValues.asList(args[0]);
        if (values.isEmpty()) {
            return 0.0;
        }
        Map<Object, Integer> counts = new LinkedHashMap<>();
        for (Object value : values) {
            counts.merge(PrlValues.hashKey(value), 1, Integer::sum);
        }
        double total = values.size();
        double entropy = 0.0;
        for (int count : counts.values()) {
            double probability = count / total;
            entropy -= probability * (Math.log(probability) / LOG2);
        }
        return entropy;
    }

    /** 每个相邻区间折算出的瞬时 cps 的总体方差。 */
    private Object cpsVariance(Object[] args) {
        List<Object> events = PrlValues.asList(args[0]);
        List<Long> times = readTimes(host, events);
        if (times.size() < 2) {
            return 0.0;
        }
        List<Double> cpsValues = new ArrayList<>();
        for (double interval : intervalsOf(times)) {
            if (interval > 0) {
                cpsValues.add(1000.0 / interval);
            }
        }
        return cpsValues.isEmpty() ? 0.0 : variance(toArray(cpsValues));
    }

    /** 相邻 time_ms 差值的中位数（文档 §2.4.6 明确「中位数」）。 */
    private Object reactionTime(Object[] args) {
        List<Object> events = PrlValues.asList(args[0]);
        List<Long> times = readTimes(host, events);
        if (times.size() < 2) {
            return 0.0;
        }
        return median(intervalsOf(times));
    }

    // ------------------------------------------------------------------ §2.4.7 动作

    /**
     * 文档写 {@code -> void}，但 §2.12.2 的 RuleManager 依赖返回值是 DetectionResult，
     * 所以这里构造并返回它（ruleName 由引擎侧补，标准库传 null）。
     */
    private Object emitAlert(Object[] args) {
        String type = PrlValues.asString(args[0]);
        double confidence = PrlValues.asDouble(args[1]);
        Object evidenceArg = args[2];
        Map<Object, Object> evidence = evidenceArg == null ? Map.of() : PrlValues.asMap(evidenceArg);
        DetectionResult alert = new DetectionResult(null, type, confidence, evidence, System.currentTimeMillis());
        host.acceptAlert(alert);
        return alert;
    }

    private Object recordEvidence(Object[] args) {
        host.recordEvidence(PrlValues.asString(args[0]), args[1]);
        return null;
    }

    private Object triggerRedScreen(Object[] args) {
        host.triggerRedScreen(PrlValues.asString(args[0]));
        return null;
    }

    /** 禁用的是客户端功能开关，不是封禁账号（见仓库约束）。 */
    private Object banFeature(Object[] args) {
        host.disableFeature(PrlValues.asString(args[0]), PrlValues.asLong(args[1]));
        return null;
    }

    /**
     * §2.2.1 的示例规则用到、§2.4 的函数表没列（文档未给公式）：命中率取「伤害大于 0 的事件占比」。
     * 空列表给 0.0 而不是报错，rules 里 {@code accuracy(events) > 0.95} 在无事件时应当不触发。
     */
    private Object accuracy(Object[] args) {
        List<Object> events = PrlValues.asList(args[0]);
        if (events.isEmpty()) {
            return 0.0;
        }
        int hits = 0;
        for (Object event : events) {
            Double damage = readDouble(host, event, "damage");
            if (damage != null && damage > 0.0) {
                hits++;
            }
        }
        return (double) hits / events.size();
    }

    /** 取末尾 N 个元素；N 不小于长度时返回整个列表，N 不大于 0 时返回空列表。 */
    private Object lastN(Object[] args) {
        List<Object> values = PrlValues.asList(args[0]);
        long count = PrlValues.asLong(args[1]);
        if (count <= 0) {
            return new ArrayList<>();
        }
        int from = (int) Math.max(0L, values.size() - count);
        return new ArrayList<>(values.subList(from, values.size()));
    }

    // ------------------------------------------------------------------ 工具

    private static double[] doubles(Object value) {
        List<Object> elements = PrlValues.asList(value);
        double[] result = new double[elements.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = PrlValues.asDouble(elements.get(i));
        }
        return result;
    }

    private static boolean allIntegers(List<Object> values) {
        for (Object value : values) {
            if (!PrlValues.isInteger(value)) {
                return false;
            }
        }
        return true;
    }

    private static double[] toArray(List<Double> values) {
        double[] result = new double[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static double[] intervalsOf(List<Long> times) {
        double[] result = new double[times.size() - 1];
        for (int i = 0; i < result.length; i++) {
            result[i] = times.get(i + 1) - times.get(i);
        }
        return result;
    }

    /** 总体标准差（除以 n），与 {@link #variance(double[])} 保持一致。 */
    private static double stddev(double[] values) {
        return Math.sqrt(variance(values));
    }

    private static double variance(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double average = mean(values);
        double sum = 0.0;
        for (double value : values) {
            sum += (value - average) * (value - average);
        }
        return sum / values.length;
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1
                ? sorted[middle]
                : (sorted[middle - 1] + sorted[middle]) / 2.0;
    }

    private static double percentileOfSorted(double[] sorted, double percent) {
        double rank = percent / 100.0 * (sorted.length - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted[lower];
        }
        double fraction = rank - lower;
        return sorted[lower] * (1 - fraction) + sorted[upper] * fraction;
    }

    private static double clamp01(double value) {
        return value < 0.0 ? 0.0 : Math.min(value, 1.0);
    }

    /** 事件必须带 time_ms；缺失时按运行时参数错误处理（§2.4.2 的函数都依赖它）。 */
    private long[] requiredTimes(List<Object> events, String function) {
        long[] times = new long[events.size()];
        for (int i = 0; i < times.length; i++) {
            Long time = readLong(host, events.get(i), "time_ms");
            if (time == null) {
                throw new PrlExecutionException(function + " 需要事件带有 time_ms 字段（§2.4.2）");
            }
            times[i] = time;
        }
        return times;
    }

    /** 读取 time_ms，跳过读不到的事件，返回升序时间戳。 */
    private static List<Long> readTimes(PrlHostContext host, List<Object> events) {
        List<Long> times = new ArrayList<>(events.size());
        for (Object event : events) {
            Long time = readLong(host, event, "time_ms");
            if (time != null) {
                times.add(time);
            }
        }
        times.sort(null);
        return times;
    }

    private static Long readLong(PrlHostContext host, Object target, String member) {
        try {
            Object value = host.getMember(target, member);
            return value instanceof Number number ? number.longValue() : null;
        } catch (PrlSecurityException e) {
            return null;
        }
    }

    private static Double readDouble(PrlHostContext host, Object target, String member) {
        try {
            Object value = host.getMember(target, member);
            return value instanceof Number number ? number.doubleValue() : null;
        } catch (PrlSecurityException e) {
            return null;
        }
    }

    private static Boolean readBoolMethod(PrlHostContext host, Object target, String method) {
        try {
            Object value = host.callMethod(target, method, NO_ARGS);
            return value instanceof Boolean bool ? bool : null;
        } catch (PrlSecurityException e) {
            return null;
        }
    }
}