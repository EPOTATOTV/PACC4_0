package com.potatotv.pacc.service.apm;

import com.potatotv.pacc.domain.ApmMetricHourly;
import com.potatotv.pacc.repository.ApmMetricHourlyRepository;
import com.potatotv.pacc.repository.ApmMetricRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * v5.4 §2 APM 管理端只读视图：概览卡片 / 指标趋势 / 平台矩阵 / 版本回归 / 异常 / 告警 / 指标目录。
 *
 * <p>本类不写库，只把小时聚合表（趋势类）与原始表（活跃口径）拼装成前端可直接渲染的结构。
 * 字段名即前端契约，不要改名。</p>
 *
 * <p>口径统一说明：</p>
 * <ul>
 *   <li>时间窗按整点对齐 {@code [to-hours, to)}，{@code to} 为当前整点的下一小时，
 *       与小时聚合的桶边界一致，避免“差一小时”的错位；</li>
 *   <li>跨桶聚合采用「先取每桶均值再等权平均」，不按样本数加权：加权会让一个大客户或一台
 *       长时间在线的机器主导整条曲线，掩盖长尾问题；</li>
 *   <li>{@code online_clients} 与 {@code available_platforms/versions} 来自原始表
 *       （小时表没有 pteid 维度），因此这两个口径不随 platform/clientVer 筛选变化，
 *       它们的职责是「告诉管理端下拉里有哪些可选值」；其余卡片与趋势会应用筛选；</li>
 *   <li>{@code hours} 夹到 [1, 720]，{@code limit} 夹到 [1, 200]。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ApmQueryService {

    /** 时间窗上限（30 天）。 */
    public static final int MAX_HOURS = 720;
    /** 平台矩阵未指定指标时的默认指标集。 */
    static final List<String> DEFAULT_MATRIX_METRICS = List.of(
            "sys_cpu_process", "sys_mem_process", "game_fps", "detect_engine_latency");
    /** 版本回归的观察窗天数（该接口没有 hours 入参，固定回看 30 天）。 */
    static final int REGRESSION_LOOKBACK_DAYS = 30;

    private final ApmMetricRepository metrics;
    private final ApmMetricHourlyRepository hourly;
    private final ApmAnomalyService anomalyService;
    private final ApmAlertService alertService;

    /** 帧率基线（用于反推 FPS 影响百分比）。 */
    @Value("${pacc.apm.fps-baseline:60}")
    private double fpsBaseline = 60;

    /** 版本回归判定阈值（%）超过即视为回归。 */
    @Value("${pacc.apm.regression-threshold-percent:20}")
    private double regressionThresholdPercent = 20;

    // ------------------------------ §2.1 概览 ------------------------------

    /** 概览卡片 + 指标目录 + 可选筛选值。 */
    public Map<String, Object> overview(int hours, String platform, String clientVer) {
        int h = clampHours(hours);
        String plat = nz(platform);
        String ver = nz(clientVer);
        Instant now = Instant.now();
        Instant hourlyTo = hourlyWindowEnd(now);
        Instant hourlyFrom = hourlyTo.minus(h, ChronoUnit.HOURS);
        Instant rawFrom = now.minus(h, ChronoUnit.HOURS);

        Double avgCpu = meanAvg("sys_cpu_process", hourlyFrom, hourlyTo, plat, ver);
        Double avgMem = meanAvg("sys_mem_process", hourlyFrom, hourlyTo, plat, ver);
        Double avgFps = meanAvg("game_fps", hourlyFrom, hourlyTo, plat, ver);
        Double fpRate = meanAvg("detect_false_positive_rate", hourlyFrom, hourlyTo, plat, ver);
        // FPS 影响：以基线帧率反推相对损失，低于基线才算影响，夹在 0 以上
        double fpsImpact = (avgFps == null || fpsBaseline <= 0)
                ? 0.0 : Math.max(0.0, 100 * (fpsBaseline - avgFps) / fpsBaseline);

        List<String> versions = new ArrayList<>(new TreeSet<>(metrics.findDistinctClientVersions(rawFrom)));
        List<String> platforms = new ArrayList<>(new TreeSet<>(metrics.findDistinctPlatforms(rawFrom)));

        Map<String, Object> cards = new LinkedHashMap<>();
        cards.put("online_clients", metrics.countDistinctPteidBetween(rawFrom, now));
        cards.put("avg_cpu_percent", round2(avgCpu == null ? 0 : avgCpu));
        cards.put("avg_mem_mb", round2(avgMem == null ? 0 : avgMem));
        cards.put("avg_fps_impact_percent", round2(fpsImpact));
        cards.put("false_positive_rate", round4(fpRate == null ? 0 : fpRate / 100));
        cards.put("sample_count", hourly.sumSampleCountBetween(hourlyFrom, hourlyTo));
        cards.put("client_versions", versions.size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hours", h);
        out.put("generated_at", iso(now));
        out.put("cards", cards);
        out.put("catalog", catalogItems());
        out.put("available_platforms", platforms);
        out.put("available_versions", versions);
        return out;
    }

    // ------------------------------ §2.3 指标趋势 ------------------------------

    /** 单指标趋势（逐小时 avg/p50/p95/p99/max/count）。 */
    public Map<String, Object> trend(String metric, int hours, String platform, String clientVer) {
        int h = clampHours(hours);
        ApmCatalog.MetricDef def = ApmCatalog.byName(metric).orElse(null);
        Instant to = hourlyWindowEnd(Instant.now());
        Instant from = to.minus(h, ChronoUnit.HOURS);

        List<Map<String, Object>> points = new ArrayList<>();
        for (ApmMetricHourly row : hourlyRows(metric, from, to, nz(platform), nz(clientVer))) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("t", iso(row.getMetricHour()));
            p.put("avg", round2(row.getAvgValue()));
            p.put("p50", round2(row.getP50Value()));
            p.put("p95", round2(row.getP95Value()));
            p.put("p99", round2(row.getP99Value()));
            p.put("max", round2(row.getMaxValue()));
            p.put("count", row.getSampleCount());
            points.add(p);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", nz(metric));
        out.put("hours", h);
        out.put("unit", def == null ? "" : def.unit());
        out.put("label", def == null ? "" : def.label());
        out.put("points", points);
        return out;
    }

    // ------------------------------ §2.4 平台矩阵 ------------------------------

    /** 平台 × 指标矩阵（单元格为该平台上该指标在窗口内的 p95 均值）。 */
    public Map<String, Object> platformMatrix(int hours, List<String> metricNames) {
        int h = clampHours(hours);
        List<String> names = new ArrayList<>();
        if (metricNames != null) {
            for (String raw : metricNames) {
                if (raw == null || raw.isBlank()) continue;
                String name = raw.trim();
                if (ApmCatalog.byName(name).isPresent() && !names.contains(name)) names.add(name);
            }
        }
        if (names.isEmpty()) names.addAll(DEFAULT_MATRIX_METRICS);

        Instant to = hourlyWindowEnd(Instant.now());
        Instant from = to.minus(h, ChronoUnit.HOURS);
        List<Map<String, Object>> metricViews = new ArrayList<>();
        List<Map<String, Object>> cells = new ArrayList<>();
        Set<String> platforms = new TreeSet<>();

        for (String name : names) {
            ApmCatalog.MetricDef def = ApmCatalog.byName(name).orElse(null);
            Map<String, Object> mv = new LinkedHashMap<>();
            mv.put("name", name);
            mv.put("label", def == null ? "" : def.label());
            mv.put("unit", def == null ? "" : def.unit());
            metricViews.add(mv);

            Map<String, List<Double>> byPlatform = new LinkedHashMap<>();
            for (ApmMetricHourly row : hourlyRows(name, from, to, "", "")) {
                String key = nz(row.getPlatform());
                byPlatform.computeIfAbsent(key, k -> new ArrayList<>()).add(row.getP95Value());
                platforms.add(key);
            }
            for (Map.Entry<String, List<Double>> e : byPlatform.entrySet()) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("platform", e.getKey());
                cell.put("metric", name);
                cell.put("p95", round2(e.getValue().stream().mapToDouble(Double::doubleValue).average().orElse(0)));
                cells.add(cell);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metrics", metricViews);
        out.put("platforms", new ArrayList<>(platforms));
        out.put("cells", cells);
        return out;
    }

    // ------------------------------ §2.5 版本回归 ------------------------------

    /** 版本回归：基准是最早出现的那个版本，后续版本 p95 相对基准涨超阈值即判回归。 */
    public Map<String, Object> versionRegression(String metric, String platform) {
        ApmCatalog.MetricDef def = ApmCatalog.byName(metric).orElse(null);
        Instant to = hourlyWindowEnd(Instant.now());
        Instant from = to.minus(REGRESSION_LOOKBACK_DAYS * 24L, ChronoUnit.HOURS);
        List<ApmMetricHourly> rows = hourly.findHistoryByMetric(metric, nz(platform), from);

        Map<String, List<ApmMetricHourly>> byVersion = new TreeMap<>();
        for (ApmMetricHourly row : rows) {
            byVersion.computeIfAbsent(nz(row.getClientVer()), k -> new ArrayList<>()).add(row);
        }
        // 按「该版本最早出现过的小时」排序：这才是真正的版本先后，字符串排序会把 5.10 排到 5.4 前
        List<Map.Entry<String, List<ApmMetricHourly>>> ordered = new ArrayList<>(byVersion.entrySet());
        ordered.sort(Comparator.comparing((Map.Entry<String, List<ApmMetricHourly>> en) ->
                firstHour(en.getValue())));

        String baselineVer = ordered.isEmpty() ? "" : ordered.get(0).getKey();
        double baselineP95 = ordered.isEmpty() ? 0 : meanP95(ordered.get(0).getValue());

        List<Map<String, Object>> versions = new ArrayList<>(ordered.size());
        for (Map.Entry<String, List<ApmMetricHourly>> en : ordered) {
            double p95 = meanP95(en.getValue());
            boolean baseline = en.getKey().equals(baselineVer);
            double delta = baseline || baselineP95 <= 0 ? 0 : (p95 - baselineP95) / baselineP95 * 100;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("client_ver", en.getKey());
            m.put("p95", round2(p95));
            m.put("sample_count", sumSamples(en.getValue()));
            m.put("baseline", baseline);
            m.put("delta_percent", round2(delta));
            m.put("regressed", !baseline && delta > regressionThresholdPercent);
            versions.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", nz(metric));
        out.put("unit", def == null ? "" : def.unit());
        out.put("label", def == null ? "" : def.label());
        out.put("regression_threshold_percent", round2(regressionThresholdPercent));
        out.put("versions", versions);
        return out;
    }

    // ------------------------------ §2.5 异常 / §2.6 告警 / 目录 ------------------------------

    /** 基线偏离异常列表。 */
    public Map<String, Object> anomalies(String platform, String metric, int hours) {
        int h = clampHours(hours);
        String plat = nz(platform);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ApmAnomalyService.Anomaly a : anomalyService.detect(plat, metric, h)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("hour", iso(a.hour()));
            m.put("value", a.value());
            m.put("threshold", a.threshold());
            m.put("mean", a.mean());
            m.put("stddev", a.stddev());
            m.put("sigma", a.sigma());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", nz(metric));
        out.put("platform", plat);
        out.put("items", items);
        return out;
    }

    /** 告警列表（视图由 {@link ApmAlertService#list} 统一构建，避免两处字段名漂移）。 */
    public Map<String, Object> alertView(String status, int limit) {
        return alertService.list(status, limit);
    }

    /** 指标目录。 */
    public Map<String, Object> catalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", catalogItems());
        return out;
    }

    private static List<Map<String, Object>> catalogItems() {
        List<Map<String, Object>> items = new ArrayList<>(ApmCatalog.all().size());
        for (ApmCatalog.MetricDef def : ApmCatalog.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", def.name());
            m.put("label", def.label());
            m.put("unit", def.unit());
            m.put("group", def.group());
            items.add(m);
        }
        return items;
    }

    // ------------------------------ 内部工具 ------------------------------

    /** 趋势类查询按「是否带筛选」选择显式重载（不用可空参数，见仓储类注释）。 */
    private List<ApmMetricHourly> hourlyRows(String metric, Instant from, Instant to,
                                             String platform, String clientVer) {
        boolean hasPlatform = platform != null && !platform.isBlank();
        boolean hasVersion = clientVer != null && !clientVer.isBlank();
        if (hasPlatform && hasVersion) {
            return hourly.findByMetricNameAndPlatformAndClientVerAndMetricHourBetweenOrderByMetricHourAsc(
                    metric, platform, clientVer, from, to);
        }
        if (hasPlatform) {
            return hourly.findByMetricNameAndPlatformAndMetricHourBetweenOrderByMetricHourAsc(
                    metric, platform, from, to);
        }
        return hourly.findByMetricNameAndMetricHourBetweenOrderByMetricHourAsc(metric, from, to);
    }

    /** 窗口内某指标的桶均值（无数据返回 null，调用方决定落成 0 还是空）。 */
    private Double meanAvg(String metric, Instant from, Instant to, String platform, String clientVer) {
        List<ApmMetricHourly> rows = hourlyRows(metric, from, to, platform, clientVer);
        if (rows.isEmpty()) return null;
        double sum = 0;
        for (ApmMetricHourly row : rows) sum += row.getAvgValue();
        return sum / rows.size();
    }

    private static double meanP95(List<ApmMetricHourly> rows) {
        if (rows.isEmpty()) return 0;
        double sum = 0;
        for (ApmMetricHourly row : rows) sum += row.getP95Value();
        return sum / rows.size();
    }

    private static long sumSamples(List<ApmMetricHourly> rows) {
        long sum = 0;
        for (ApmMetricHourly row : rows) sum += row.getSampleCount();
        return sum;
    }

    private static Instant firstHour(List<ApmMetricHourly> rows) {
        Instant first = null;
        for (ApmMetricHourly row : rows) {
            Instant at = row.getMetricHour();
            if (at != null && (first == null || at.isBefore(first))) first = at;
        }
        return first == null ? Instant.EPOCH : first;
    }

    /** 当前整点的下一小时，作为窗口右开边界。 */
    private static Instant hourlyWindowEnd(Instant now) {
        return now.truncatedTo(ChronoUnit.HOURS).plus(1, ChronoUnit.HOURS);
    }

    private static int clampHours(int hours) {
        int h = hours <= 0 ? 24 : hours;
        return Math.max(1, Math.min(MAX_HOURS, h));
    }

    private static String iso(Instant at) {
        return at == null ? "" : at.toString();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}