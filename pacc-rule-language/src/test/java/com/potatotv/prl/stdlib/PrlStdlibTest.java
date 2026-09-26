package com.potatotv.prl.stdlib;

import com.potatotv.prl.engine.DetectionResult;
import com.potatotv.prl.runtime.PrlCallable;
import com.potatotv.prl.runtime.PrlExecutionException;
import com.potatotv.prl.runtime.PrlHostObject;
import com.potatotv.prl.runtime.PrlSecurityException;
import com.potatotv.prl.runtime.PrlValues;
import com.potatotv.prl.sandbox.PrlHostContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrlStdlibTest {

    private final PrlStdlib stdlib = new PrlStdlib();

    // ------------------------------------------------------------------ 契约

    @Test
    void supportsEveryDocumentedFunction() {
        String[] names = {
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
                "accuracy", "last_n"};
        for (String name : names) {
            assertTrue(PrlStdlib.supports(name), name);
        }
        assertFalse(PrlStdlib.supports("not_a_function"));
        assertFalse(PrlStdlib.supports(null));
    }

    @Test
    void unknownFunctionIsRejectedAndArityIsChecked() {
        assertThrows(PrlSecurityException.class, () -> stdlib.call("not_a_function", new Object[0]));
        assertThrows(PrlExecutionException.class, () -> stdlib.call("average", new Object[0]));
    }

    // ------------------------------------------------------------------ §2.4.1 统计

    @Test
    void averageMedianStddevVariance() {
        List<Object> values = list(2L, 4L, 4L, 4L, 5L, 5L, 7L, 9L);
        assertEquals(5.0, num(call("average", values)), 1e-9);
        assertEquals(4.5, num(call("median", values)), 1e-9);
        assertEquals(2.0, num(call("stddev", values)), 1e-9);
        assertEquals(4.0, num(call("variance", values)), 1e-9);
    }

    @Test
    void statisticsReturnZeroOnEmptyList() {
        List<Object> empty = new ArrayList<>();
        assertEquals(0.0, num(call("average", empty)), 1e-9);
        assertEquals(0.0, num(call("median", empty)), 1e-9);
        assertEquals(0.0, num(call("stddev", empty)), 1e-9);
        assertEquals(0.0, num(call("variance", empty)), 1e-9);
        assertEquals(0.0, num(call("min", empty)), 1e-9);
        assertEquals(0.0, num(call("max", empty)), 1e-9);
        assertEquals(0.0, num(stdlib.call("percentile", new Object[]{empty, 50.0})), 1e-9);
        assertEquals(0.0, num(stdlib.call("correlation", new Object[]{empty, empty})), 1e-9);
        assertEquals(0.0, num(stdlib.call("zscore", new Object[]{1.0, empty})), 1e-9);
    }

    @Test
    void minMaxSumKeepIntegerType() {
        assertInstanceOf(Long.class, stdlib.call("min", new Object[]{list(3L, 1L, 2L)}));
        assertEquals(1L, ((Number) stdlib.call("min", new Object[]{list(3L, 1L, 2L)})).longValue());
        assertEquals(3L, ((Number) stdlib.call("max", new Object[]{list(3L, 1L, 2L)})).longValue());
        assertEquals(6L, ((Number) stdlib.call("sum", new Object[]{list(1L, 2L, 3L)})).longValue());
        assertInstanceOf(Double.class, stdlib.call("sum", new Object[]{list(1L, 2.5)}));
        assertEquals(3.5, num(stdlib.call("sum", new Object[]{list(1L, 2.5)})), 1e-9);
    }

    @Test
    void percentileInterpolates() {
        assertEquals(2.5, num(stdlib.call("percentile", new Object[]{list(1L, 2L, 3L, 4L), 50.0})), 1e-9);
        assertEquals(4.0, num(stdlib.call("percentile", new Object[]{list(1L, 2L, 3L, 4L), 100.0})), 1e-9);
        assertThrows(PrlExecutionException.class,
                () -> stdlib.call("percentile", new Object[]{list(1L, 2L), 101.0}));
    }

    @Test
    void outliersUseIqr() {
        List<Object> result = PrlValues.asList(
                stdlib.call("outliers", new Object[]{list(1L, 2L, 3L, 4L, 5L, 100L), 1.5}));
        assertEquals(1, result.size());
        assertEquals(5L, ((Number) result.get(0)).longValue());
    }

    @Test
    void correlationAndZscore() {
        assertEquals(1.0, num(stdlib.call("correlation",
                new Object[]{list(1L, 2L, 3L), list(2L, 4L, 6L)})), 1e-9);
        assertEquals(1.224744871, num(stdlib.call("zscore", new Object[]{3L, list(1L, 2L, 3L)})), 1e-6);
    }

    @Test
    void frequencyDedupesByValueAndKeepsPrlKeyType() {
        Map<Object, Object> result = PrlValues.asMap(
                stdlib.call("frequency", new Object[]{list(3L, 1L, 1L, 3L, 2L)}));
        assertEquals(3, result.size());
        assertEquals(2L, ((Number) result.get(3L)).longValue());
        assertEquals(2L, ((Number) result.get(1L)).longValue());
        assertEquals(1L, ((Number) result.get(2L)).longValue());
        List<Object> keys = new ArrayList<>(result.keySet());
        assertEquals(3L, ((Number) keys.get(0)).longValue());
    }

    @Test
    void rateCountsEventsPerSecond() {
        List<Object> events = list(event(0L), event(1000L));
        assertEquals(1.0, num(stdlib.call("rate", new Object[]{events, 1000L})), 1e-9);
        assertEquals(0.0, num(stdlib.call("rate", new Object[]{new ArrayList<>(), 1000L})), 1e-9);
    }

    // ------------------------------------------------------------------ §2.4.2 时序

    @Test
    void slidingWindowProducesSubLists() {
        List<Object> events = list(event(0L), event(500L), event(1500L), event(2000L));
        List<Object> windows = PrlValues.asList(stdlib.call("sliding_window", new Object[]{events, 1000L}));
        assertEquals(4, windows.size());
        assertEquals(2, PrlValues.asList(windows.get(0)).size());
        assertEquals(1, PrlValues.asList(windows.get(3)).size());
    }

    @Test
    void burstCountMergesOverlappingBursts() {
        List<Object> events = list(event(0L), event(100L), event(200L), event(300L),
                event(5000L), event(5100L), event(5200L));
        assertEquals(2L, ((Number) stdlib.call("burst_count", new Object[]{events, 250L, 3L})).longValue());
    }

    @Test
    void intervalStatsExposesMean() {
        Object stats = stdlib.call("interval_stats",
                new Object[]{list(event(0L), event(100L), event(200L), event(400L))});
        assertInstanceOf(StatsValue.class, stats);
        StatsValue value = (StatsValue) stats;
        assertEquals(133.3333333, ((Number) value.getMember("mean")).doubleValue(), 1e-4);
        assertEquals(3L, ((Number) value.getMember("count")).longValue());
        assertThrows(PrlSecurityException.class, () -> value.getMember("nope"));
    }

    @Test
    void trendAndChangeRate() {
        assertEquals(1.0, num(stdlib.call("trend", new Object[]{list(1L, 2L, 3L, 4L)})), 1e-9);
        assertEquals(0.5, num(stdlib.call("change_rate", new Object[]{list(100L, 150L)})), 1e-9);
        assertEquals(0.0, num(stdlib.call("change_rate", new Object[]{list(0L, 5L)})), 1e-9);
    }

    // ------------------------------------------------------------------ §2.4.3 集合

    @Test
    void collectionOperations() {
        List<Object> values = list(1L, 2L, 3L, 4L);
        List<Object> filtered = PrlValues.asList(
                stdlib.call("filter", new Object[]{values, fn(1, a -> ((Number) a[0]).longValue() > 2)}));
        assertEquals(2, filtered.size());

        List<Object> mapped = PrlValues.asList(
                stdlib.call("map", new Object[]{values, fn(1, a -> ((Number) a[0]).longValue() * 2)}));
        assertEquals(2L, ((Number) mapped.get(0)).longValue());

        assertEquals(10L, ((Number) stdlib.call("reduce",
                new Object[]{values, 0L, fn(2, a -> ((Number) a[0]).longValue() + ((Number) a[1]).longValue())}))
                .longValue());

        List<Object> ascending = PrlValues.asList(stdlib.call("sort", new Object[]{list(3L, 1L, 2L), null}));
        assertEquals(1L, ((Number) ascending.get(0)).longValue());

        List<Object> descending = PrlValues.asList(stdlib.call("sort",
                new Object[]{list(3L, 1L, 2L), fn(2, a -> -Long.compare(
                        ((Number) a[0]).longValue(), ((Number) a[1]).longValue()))}));
        assertEquals(3L, ((Number) descending.get(0)).longValue());

        Map<Object, Object> grouped = PrlValues.asMap(stdlib.call("group_by",
                new Object[]{values, fn(1, a -> ((Number) a[0]).longValue() % 2)}));
        assertEquals(2, PrlValues.asList(grouped.get(1L)).size());
        assertEquals(2, PrlValues.asList(grouped.get(0L)).size());
    }

    @Test
    void firstLastTakeSkipContainsUniqueFlattenZip() {
        assertEquals(1L, ((Number) stdlib.call("first", new Object[]{list(1L, 2L)})).longValue());
        assertEquals(2L, ((Number) stdlib.call("last", new Object[]{list(1L, 2L)})).longValue());
        assertThrows(PrlExecutionException.class, () -> stdlib.call("first", new Object[]{new ArrayList<>()}));
        assertThrows(PrlExecutionException.class, () -> stdlib.call("last", new Object[]{new ArrayList<>()}));

        assertEquals(2, PrlValues.asList(stdlib.call("take", new Object[]{list(1L, 2L, 3L), 2L})).size());
        assertEquals(0, PrlValues.asList(stdlib.call("take", new Object[]{list(1L, 2L, 3L), 0L})).size());
        assertEquals(1, PrlValues.asList(stdlib.call("skip", new Object[]{list(1L, 2L, 3L), 2L})).size());

        assertEquals(true, stdlib.call("contains", new Object[]{list(1L, 2L), 2L}));
        assertEquals(false, stdlib.call("contains", new Object[]{list(1L, 2L), 9L}));
        assertEquals(true, stdlib.call("contains", new Object[]{"hello", "ell"}));

        List<Object> unique = PrlValues.asList(stdlib.call("unique", new Object[]{list(1L, 1L, 2L, 3L, 2L)}));
        assertEquals(3, unique.size());

        List<Object> flattened = PrlValues.asList(stdlib.call("flatten",
                new Object[]{list(list(1L, 2L), list(3L))}));
        assertEquals(3, flattened.size());

        List<Object> zipped = PrlValues.asList(stdlib.call("zip",
                new Object[]{list(1L, 2L, 3L), list("a", "b")}));
        assertEquals(2, zipped.size());
        assertEquals(2, PrlValues.asList(zipped.get(0)).size());
    }

    // ------------------------------------------------------------------ §2.4.4 字符串

    @Test
    void stringFunctions() {
        assertEquals(5L, ((Number) stdlib.call("length", new Object[]{"hello"})).longValue());
        assertEquals(2L, ((Number) stdlib.call("length", new Object[]{list(1L, 2L)})).longValue());
        assertTrue((Boolean) stdlib.call("starts_with", new Object[]{"hello", "he"}));
        assertTrue((Boolean) stdlib.call("ends_with", new Object[]{"hello", "lo"}));
        assertTrue((Boolean) stdlib.call("regex_match", new Object[]{"abc123", "\\d+"}));
        assertThrows(PrlExecutionException.class,
                () -> stdlib.call("regex_match", new Object[]{"abc", "[unclosed"}));

        List<Object> extracted = PrlValues.asList(
                stdlib.call("regex_extract", new Object[]{"a1b2", "(\\d)"}));
        assertEquals(2, extracted.size());
        assertEquals("1", extracted.get(0));

        assertEquals("hello", stdlib.call("to_lower", new Object[]{"HeLLo"}));
        assertEquals("HELLO", stdlib.call("to_upper", new Object[]{"hello"}));
        assertEquals("x", stdlib.call("trim", new Object[]{"  x  "}));

        assertEquals(3, PrlValues.asList(stdlib.call("split", new Object[]{"a,b,c", ","})).size());
        assertEquals(3, PrlValues.asList(stdlib.call("split", new Object[]{"abc", ""})).size());
        assertEquals("a-b", stdlib.call("join", new Object[]{list("a", "b"), "-"}));

        assertEquals(3L, ((Number) stdlib.call("levenshtein", new Object[]{"kitten", "sitting"})).longValue());
        assertEquals(1.0, num(stdlib.call("similarity", new Object[]{"abc", "abc"})), 1e-9);
        assertEquals(1.0, num(stdlib.call("similarity", new Object[]{"", ""})), 1e-9);
    }

    // ------------------------------------------------------------------ §2.4.5 数学

    @Test
    void mathFunctions() {
        assertInstanceOf(Long.class, stdlib.call("abs", new Object[]{-5L}));
        assertEquals(5L, ((Number) stdlib.call("abs", new Object[]{-5L})).longValue());
        assertInstanceOf(Double.class, stdlib.call("abs", new Object[]{-5.5}));
        assertEquals(5.5, num(stdlib.call("abs", new Object[]{-5.5})), 1e-9);

        assertEquals(3.14, num(stdlib.call("round", new Object[]{3.14159, 2L})), 1e-9);
        assertEquals(2L, ((Number) stdlib.call("floor", new Object[]{2.9})).longValue());
        assertEquals(3L, ((Number) stdlib.call("ceil", new Object[]{2.1})).longValue());
        assertEquals(3L, ((Number) stdlib.call("clamp", new Object[]{5L, 1L, 3L})).longValue());
        assertEquals(3.0, num(stdlib.call("clamp", new Object[]{5.0, 1.0, 3.0})), 1e-9);
        assertThrows(PrlExecutionException.class,
                () -> stdlib.call("clamp", new Object[]{5L, 3L, 1L}));

        assertEquals(3.0, num(stdlib.call("sqrt", new Object[]{9.0})), 1e-9);
        assertEquals(8.0, num(stdlib.call("pow", new Object[]{2.0, 3.0})), 1e-9);
        assertEquals(3.0, num(stdlib.call("log", new Object[]{8.0, 2.0})), 1e-9);
        assertThrows(PrlExecutionException.class, () -> stdlib.call("log", new Object[]{-1.0, 2.0}));
        assertThrows(PrlExecutionException.class, () -> stdlib.call("log", new Object[]{8.0, 1.0}));
        assertEquals(0.0, num(stdlib.call("sin", new Object[]{0.0})), 1e-9);
    }

    @Test
    void logDispatchesToActionBranchForStringLevel() {
        assertNull(stdlib.call("log", new Object[]{"warn", "x"}));
    }

    @Test
    void randomIsDeterministicPerSeed() {
        PrlStdlib first = new PrlStdlib(PrlHostContext.EMPTY, 42L);
        PrlStdlib second = new PrlStdlib(PrlHostContext.EMPTY, 42L);
        List<Long> firstSequence = new ArrayList<>();
        List<Long> secondSequence = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            long value = ((Number) first.call("random", new Object[]{1L, 6L})).longValue();
            assertTrue(value >= 1 && value <= 6);
            firstSequence.add(value);
            secondSequence.add(((Number) second.call("random", new Object[]{1L, 6L})).longValue());
        }
        assertEquals(firstSequence, secondSequence);

        PrlStdlib different = new PrlStdlib(PrlHostContext.EMPTY, 7L);
        assertNotEquals(firstSequence, sequenceOf(different, 20));
        assertNotEquals(first.withSeed(1L), first.withSeed(2L));
    }

    // ------------------------------------------------------------------ §2.4.6 PACC 专属

    @Test
    void analyzeClickPatternReadsCps() {
        List<Object> clicks = list(event(0L), event(100L), event(200L), event(300L));
        Object pattern = stdlib.call("analyze_click_pattern", new Object[]{clicks});
        assertInstanceOf(ClickPatternValue.class, pattern);
        ClickPatternValue value = (ClickPatternValue) pattern;
        assertEquals(10.0, ((Number) value.getMember("cps")).doubleValue(), 1e-9);
        assertEquals(0.0, ((Number) value.getMember("interval_stddev")).doubleValue(), 1e-9);
        assertEquals(false, value.callMethod("is_human_like", new Object[0]));
        assertTrue(((String) value.callMethod("to_json", new Object[0])).contains("\"cps\""));
        assertThrows(PrlSecurityException.class, () -> value.getMember("unknown"));
        assertThrows(PrlSecurityException.class, () -> value.callMethod("unknown", new Object[0]));

        // §2.2.1 传的是 AttackEvent 形状的事件，也必须能处理
        List<Object> attacks = list(event(0L, "reach", 2.0), event(100L, "reach", 2.0));
        assertInstanceOf(ClickPatternValue.class, stdlib.call("analyze_click_pattern", new Object[]{attacks}));
    }

    @Test
    void detectionHeuristics() {
        List<Object> moves = list(event(0L, "speed", 5.0), event(100L, "speed", 12.0));
        assertEquals(0.5, num(stdlib.call("detect_speed_hack", new Object[]{moves})), 1e-9);

        FakeEvent flying = event(0L, "is_airborne", fn(0, a -> true));
        FakeEvent grounded = event(100L, "is_airborne", fn(0, a -> false));
        assertEquals(0.5, num(stdlib.call("detect_fly", new Object[]{list(flying, grounded)})), 1e-9);

        List<Object> attacks = list(event(0L, "reach", 2.5), event(100L, "reach", 3.5));
        assertEquals(0.5, num(stdlib.call("detect_reach", new Object[]{attacks})), 1e-9);

        List<Object> aim = list(event(0L, "delta_yaw", 0.1, "delta_pitch", 0.0, "precision", 1.0),
                event(100L, "delta_yaw", 0.1, "delta_pitch", 0.0, "precision", 1.0));
        assertEquals(1.0, num(stdlib.call("detect_aim_assist", new Object[]{aim})), 1e-9);

        assertEquals(1.0, num(stdlib.call("entropy", new Object[]{list(1L, 1L, 2L, 2L)})), 1e-9);
        List<Object> clicks = list(event(0L), event(100L), event(200L), event(400L));
        assertTrue(num(stdlib.call("cps_variance", new Object[]{clicks})) > 0.0);
        assertEquals(100.0, num(stdlib.call("reaction_time", new Object[]{clicks})), 1e-9);
        List<Object> steadyClicks = list(event(0L), event(100L), event(200L), event(300L));
        assertTrue(num(stdlib.call("detect_autoclicker", new Object[]{steadyClicks})) > 0.0);
    }

    @Test
    void isHumanClickPatternUsesSharedThresholds() {
        ClickPatternValue human = new ClickPatternValue(8.0, 30.0);
        ClickPatternValue bot = new ClickPatternValue(18.0, 1.0);
        assertEquals(true, stdlib.call("is_human_click_pattern", new Object[]{human}));
        assertEquals(false, stdlib.call("is_human_click_pattern", new Object[]{bot}));
    }

    // ------------------------------------------------------------------ §2.4.7 动作

    @Test
    void emitAlertReturnsResultAndReachesHost() {
        RecordingHost host = new RecordingHost();
        PrlStdlib withHost = new PrlStdlib(host);
        Map<Object, Object> evidence = new LinkedHashMap<>();
        evidence.put("attack_rate", 9.0);

        Object result = withHost.call("emit_alert", new Object[]{"KILL_AURA", 0.92, evidence});
        assertInstanceOf(DetectionResult.class, result);
        DetectionResult alert = (DetectionResult) result;
        assertEquals("KILL_AURA", alert.type());
        assertEquals(0.92, alert.confidence(), 1e-9);
        assertNull(alert.ruleName());
        assertEquals(alert, host.acceptedAlert);
        assertTrue(alert.timestampMs() > 0);

        assertNull(withHost.call("record_evidence", new Object[]{"k", 1L}));
        assertEquals("k", host.lastEvidenceKey);
        assertNull(withHost.call("trigger_redscreen", new Object[]{"reason"}));
        assertEquals("reason", host.lastRedScreen);
        assertNull(withHost.call("ban_feature", new Object[]{"aimbot", 5000L}));
        assertEquals("aimbot", host.lastDisabledFeature);
        assertEquals(5000L, host.lastDisabledDuration);
        withHost.call("log", new Object[]{"info", "hello"});
        assertEquals("hello", host.lastLog);
    }

    // ------------------------------------------------------------------ 工具

    private Object call(String name, Object... args) {
        return stdlib.call(name, args);
    }

    private List<Long> sequenceOf(PrlStdlib library, int count) {
        List<Long> sequence = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sequence.add(((Number) library.call("random", new Object[]{1L, 6L})).longValue());
        }
        return sequence;
    }

    private static double num(Object value) {
        return ((Number) value).doubleValue();
    }

    private static List<Object> list(Object... elements) {
        return PrlValues.list(elements);
    }

    private static PrlCallable fn(int arity, Function<Object[], Object> body) {
        return new PrlCallable() {
            @Override
            public Object call(Object[] args) {
                return body.apply(args);
            }

            @Override
            public int arity() {
                return arity;
            }
        };
    }

    /** 假的宿主事件：字段用 map 存，方法用 callable 存，缺失时抛 PrlSecurityException。 */
    private static FakeEvent event(Object timeMs, Object... extraKeyValues) {
        FakeEvent result = new FakeEvent().member("time_ms", timeMs);
        for (int i = 0; i + 1 < extraKeyValues.length; i += 2) {
            Object value = extraKeyValues[i + 1];
            if (value instanceof PrlCallable callable) {
                result.method((String) extraKeyValues[i], callable);
            } else {
                result.member((String) extraKeyValues[i], value);
            }
        }
        return result;
    }

    private static final class FakeEvent implements PrlHostObject {
        private final Map<String, Object> members = new HashMap<>();
        private final Map<String, PrlCallable> methods = new HashMap<>();

        FakeEvent member(String name, Object value) {
            members.put(name, value);
            return this;
        }

        FakeEvent method(String name, PrlCallable value) {
            methods.put(name, value);
            return this;
        }

        @Override
        public Object getMember(String member) {
            if (members.containsKey(member)) {
                return members.get(member);
            }
            throw new PrlSecurityException("假事件没有成员 '" + member + "'");
        }

        @Override
        public Object callMethod(String method, Object[] args) {
            PrlCallable callable = methods.get(method);
            if (callable == null) {
                throw new PrlSecurityException("假事件没有方法 '" + method + "'");
            }
            return callable.call(args);
        }
    }

    /** 记录副作用的宿主，用于验证 §2.4.7 真的调到了 host 上。 */
    private static final class RecordingHost implements PrlHostContext {
        private DetectionResult acceptedAlert;
        private String lastEvidenceKey;
        private String lastRedScreen;
        private String lastDisabledFeature;
        private long lastDisabledDuration;
        private String lastLog;

        @Override
        public Object callFunction(String name, Object[] args) {
            throw new PrlSecurityException("未注册函数 '" + name + "'");
        }

        @Override
        public java.util.Set<String> getAvailableFunctions() {
            return java.util.Set.of();
        }

        @Override
        public void acceptAlert(DetectionResult alert) {
            this.acceptedAlert = alert;
        }

        @Override
        public void recordEvidence(String key, Object value) {
            this.lastEvidenceKey = key;
        }

        @Override
        public void triggerRedScreen(String reason) {
            this.lastRedScreen = reason;
        }

        @Override
        public void disableFeature(String feature, long durationMillis) {
            this.lastDisabledFeature = feature;
            this.lastDisabledDuration = durationMillis;
        }

        @Override
        public void log(String level, String message) {
            this.lastLog = message;
        }
    }
}