package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.DetectionEvent;
import com.potatotv.pacc.domain.RedscreenAlert;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.AdminLoginLogRepository;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.DetectionEventRepository;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v4.8 数据平台与 BI：预置报表聚合服务。
 * <p>仅对现有业务表做只读聚合，不新增存储；对外提供趋势/分布/误报/信誉/审计等报表，
 * 以及供前端 CSV 导出的结构化数据。所有时间均为服务器本地时区（自然日）。</p>
 */
@Service
@RequiredArgsConstructor
public class BiReportService {

    private final DetectionEventRepository eventRepository;
    private final RedscreenAlertRepository alertRepository;
    private final CheatRecordRepository cheatRecordRepository;
    private final AccountRepository accountRepository;
    private final AppealRepository appealRepository;
    private final AdminLoginLogRepository adminLoginLogRepository;
    private final com.potatotv.pacc.service.OnlineStatusService onlineStatusService;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private static String day(Instant t) {
        return t.atZone(ZONE).toLocalDate().toString();
    }

    /** 检测趋势（按自然日）。 */
    public Map<String, Object> detectionTrend(int days, String startDate, String endDate) {
        Instant start = window(reduceDays(days, startDate));
        Instant end = endOf(endDate);
        Map<String, Long> perDay = emptyDayMap(start, end);
        for (Object[] row : eventRepository.countGroupByDay(start, end.plus(1, ChronoUnit.MINUTES))) {
            perDay.merge(day((Instant) row[0]), ((Number) row[1]).longValue(), Long::sum);
        }
        return Map.of("days", keys(perDay), "counts", values(perDay), "total", perDay.values().stream().mapToLong(Long::longValue).sum());
    }

    /** 红屏趋势（按自然日）。 */
    public Map<String, Object> redscreenTrend(int days, String startDate, String endDate) {
        Instant start = window(reduceDays(days, startDate));
        Instant end = endOf(endDate);
        Map<String, Long> perDay = emptyDayMap(start, end);
        for (Object[] row : alertRepository.countGroupByDay(start, end.plus(1, ChronoUnit.MINUTES))) {
            perDay.merge(day((Instant) row[0]), ((Number) row[1]).longValue(), Long::sum);
        }
        return Map.of("days", keys(perDay), "counts", values(perDay), "total", perDay.values().stream().mapToLong(Long::longValue).sum());
    }

    /** 作弊类型分布（红屏告警按 cheatType 分组，覆盖全部历史或日期段）。 */
    public Map<String, Object> cheatTypes(Instant start, Instant end) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] row : alertRepository.countGroupByCheatType(start, end.plus(1, ChronoUnit.MINUTES))) {
            rows.add(Map.of("cheat_type", String.valueOf(row[0]), "count", ((Number) row[1]).longValue()));
        }
        rows.sort((a, b) -> Long.compare(((Number) b.get("count")).longValue(), ((Number) a.get("count")).longValue()));
        return Map.of("items", rows);
    }

    /** 红屏状态分布（误报率：FALSE_POSITIVE 占比）。 */
    public Map<String, Object> redscreenHealth(Instant start, Instant end) {
        long pending = 0, confirmed = 0, falsePositive = 0;
        for (Object[] row : alertRepository.countGroupByState(start, end.plus(1, ChronoUnit.MINUTES))) {
            switch (String.valueOf(row[0])) {
                case "PENDING_INSPECT" -> pending += ((Number) row[1]).longValue();
                case "CONFIRMED" -> confirmed += ((Number) row[1]).longValue();
                case "FALSE_POSITIVE" -> falsePositive += ((Number) row[1]).longValue();
                default -> { }
            }
        }
        long total = pending + confirmed + falsePositive;
        double fpRate = total == 0 ? 0 : Math.round(falsePositive * 10000.0 / total) / 100.0;
        return Map.of("pending", pending, "confirmed", confirmed, "false_positive", falsePositive,
                "total", total, "false_positive_rate", fpRate);
    }

    /** 作弊记录误报率（已撤销记录占比）。 */
    public Map<String, Object> cheatRecordHealth() {
        long persisted = cheatRecordRepository.countByRevokedFalse();
        long revoked = cheatRecordRepository.countRevokedTrue();
        return Map.of("persisted", persisted, "revoked", revoked, "total", persisted + revoked);
    }

    /** 玩家信誉分布 + 账号状态分布。 */
    public Map<String, Object> playerProfile() {
        long total = accountRepository.count();
        Map<String, Object> rep = new LinkedHashMap<>();
        rep.put("0_49", accountRepository.countByReputationBetween(0, 49));
        rep.put("50_69", accountRepository.countByReputationBetween(50, 69));
        rep.put("70_84", accountRepository.countByReputationBetween(70, 84));
        rep.put("85_100", accountRepository.countByReputationBetween(85, 100));
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("normal", accountRepository.countByStatus("normal"));
        status.put("suspicious", accountRepository.countByStatus("suspicious"));
        status.put("high_risk", accountRepository.countByStatus("high_risk"));
        status.put("locked_inspect", accountRepository.countByStatus("locked_inspect"));
        return Map.of("total", total, "reputation", rep, "status", status);
    }

    /** 客户端版本分布（BEDROCK/JAVA）。 */
    public Map<String, Object> editionSplit(Instant start, Instant end) {
        long bedrock = eventRepository.countByEditionAndOccurredAtBetween(DetectionEvent.Edition.BEDROCK, start, end);
        long java = eventRepository.countByEditionAndOccurredAtBetween(DetectionEvent.Edition.JAVA, start, end);
        return Map.of("bedrock", bedrock, "java", java);
    }

    /** 申诉漏斗：按审核阶段统计各状态数量。 */
    public Map<String, Object> appealFunnel(Instant start, Instant end) {
        Map<String, Map<String, Long>> byStage = new LinkedHashMap<>();
        for (Object[] row : appealRepository.countGroupByStageAndStatus(start, end.plus(1, ChronoUnit.MINUTES))) {
            String stage = String.valueOf(row[0]);
            String st = String.valueOf(row[1]);
            byStage.computeIfAbsent(stage, k -> new LinkedHashMap<>())
                    .put(st, ((Number) row[2]).longValue());
        }
        long approved = byStage.values().stream().flatMap(m -> m.entrySet().stream())
                .filter(e -> e.getKey().equals("approved")).mapToLong(Map.Entry::getValue).sum();
        long rejected = byStage.values().stream().flatMap(m -> m.entrySet().stream())
                .filter(e -> e.getKey().equals("rejected")).mapToLong(Map.Entry::getValue).sum();
        long total = byStage.values().stream().flatMap(m -> m.entrySet().stream())
                .mapToLong(Map.Entry::getValue).sum();
        return Map.of("by_stage", byStage, "total", total, "approved", approved, "rejected", rejected);
    }

    /** 管理登录审计趋势（成功/失败按自然日）。 */
    public Map<String, Object> loginAudit(int days, String startDate, String endDate) {
        Instant start = window(reduceDays(days, startDate));
        Instant end = endOf(endDate);
        Map<String, Long> ok = new LinkedHashMap<>();
        Map<String, Long> fail = new LinkedHashMap<>();
        Map<String, Long> zero = emptyDayMap(start, end);
        zero.forEach((d, v) -> { ok.put(d, 0L); fail.put(d, 0L); });
        for (Object[] row : adminLoginLogRepository.countGroupByDayAndResult(start, end.plus(1, ChronoUnit.MINUTES))) {
            String d = day((Instant) row[0]);
            if ("success".equalsIgnoreCase(String.valueOf(row[1]))) ok.put(d, ((Number) row[2]).longValue());
            else fail.put(d, ((Number) row[2]).longValue());
        }
        return Map.of("days", keys(ok), "success", values(ok), "fail", values(fail));
    }

    // ---------- v4.8 BI 补全：导出 / 实时大屏 / 数据下钻 ----------

    /** 报表 CSV 导出。支持 detection-trend / redscreen-trend / login-audit / cheat-types。 */
    @SuppressWarnings("unchecked")
    public String exportCsv(String report, int days, String startDate, String endDate) {
        StringBuilder sb = new StringBuilder();
        switch (report == null ? "" : report) {
            case "detection-trend" -> {
                Map<String, Object> m = detectionTrend(days, startDate, endDate);
                appendTrendCsv(sb, (List<String>) m.get("days"), (List<Object>) m.get("counts"));
            }
            case "redscreen-trend" -> {
                Map<String, Object> m = redscreenTrend(days, startDate, endDate);
                appendTrendCsv(sb, (List<String>) m.get("days"), (List<Object>) m.get("counts"));
            }
            case "login-audit" -> {
                Map<String, Object> m = loginAudit(days, startDate, endDate);
                sb.append("date,success,fail\n");
                List<String> d = (List<String>) m.get("days");
                List<Object> ok = (List<Object>) m.get("success");
                List<Object> fail = (List<Object>) m.get("fail");
                for (int i = 0; i < d.size(); i++) {
                    sb.append(csvCell(d.get(i))).append(',')
                            .append(csvCell(String.valueOf(ok.get(i)))).append(',')
                            .append(csvCell(String.valueOf(fail.get(i)))).append('\n');
                }
            }
            case "cheat-types" -> {
                Instant cs = startDate != null && !startDate.isBlank() ? Instant.parse(startDate)
                        : Instant.now().minus(30, ChronoUnit.DAYS);
                Map<String, Object> m = cheatTypes(cs, endOf(endDate));
                sb.append("cheat_type,count\n");
                for (Map<String, Object> row : (List<Map<String, Object>>) m.get("items")) {
                    sb.append(csvCell(String.valueOf(row.get("cheat_type")))).append(',')
                            .append(csvCell(String.valueOf(row.get("count")))).append('\n');
                }
            }
            default -> throw new IllegalArgumentException("unsupported report: " + report);
        }
        return sb.toString();
    }

    private void appendTrendCsv(StringBuilder sb, List<String> dates, List<Object> counts) {
        sb.append("date,count\n");
        for (int i = 0; i < dates.size(); i++) {
            sb.append(csvCell(dates.get(i))).append(',')
                    .append(csvCell(String.valueOf(counts.get(i)))).append('\n');
        }
    }

    /** sanitize CSV 单元格，规避公式注入（= + - @ 前缀加引号）。 */
    private String csvCell(String v) {
        if (v == null) return "";
        if (!v.isEmpty() && (v.charAt(0) == '=' || v.charAt(0) == '+' || v.charAt(0) == '-' || v.charAt(0) == '@')) {
            return "'" + v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    /** 实时大屏快照：在线数、时段内检测/红屏量、最新红屏事件流。 */
    public Map<String, Object> realtime() {
        Instant now = Instant.now();
        Instant hourStart = now.minus(1, ChronoUnit.HOURS);
        Instant dayStart = now.minus(1, ChronoUnit.DAYS);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (RedscreenAlert a : alertRepository.findTop50ByOrderByOccurredAtDesc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("alertId", a.getAlertId());
            row.put("cheatType", a.getCheatType());
            row.put("level", a.getLevel());
            row.put("state", a.getState());
            row.put("occurredAt", a.getOccurredAt());
            recent.add(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("online", onlineStatusService.onlineCount());
        m.put("detection_last_hour", eventRepository.countByOccurredAtBetween(hourStart, now));
        m.put("detection_last_24h", eventRepository.countByOccurredAtBetween(dayStart, now));
        m.put("redscreen_last_hour", alertRepository.countByOccurredAtBetween(hourStart, now));
        m.put("redscreen_last_24h", alertRepository.countByOccurredAtBetween(dayStart, now));
        m.put("pending_inspect", alertRepository.countByState("PENDING_INSPECT"));
        m.put("recent_redscreens", recent);
        return m;
    }

    /** 检测明细下钻（按时间倒序，最近 50 条）。 */
    public List<Map<String, Object>> detectionDrilldown(int days, String startDate) {
        Instant start = window(reduceDays(days, startDate));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DetectionEvent e : eventRepository.findTop50ByOccurredAtAfterOrderByOccurredAtDesc(start)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", e.getId());
            row.put("pteid", e.getPteid());
            row.put("eventType", e.getEventType());
            row.put("severity", e.getSeverity());
            row.put("edition", e.getEdition());
            row.put("clientRiskScore", e.getClientRiskScore());
            row.put("clientVersion", e.getClientVersion());
            row.put("occurredAt", e.getOccurredAt());
            rows.add(row);
        }
        return rows;
    }

    /** 红屏明细下钻（按时间倒序，最近 50 条；pteid 脱敏展示）。 */
    public List<Map<String, Object>> redscreenDrilldown(int days, String startDate) {
        Instant start = window(reduceDays(days, startDate));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RedscreenAlert a : alertRepository.findTop50ByOrderByOccurredAtDesc()) {
            String occurred = a.getOccurredAt() == null ? null : a.getOccurredAt().toString();
            if (occurred == null || Instant.parse(occurred).isBefore(start)) break;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("alertId", a.getAlertId());
            row.put("pteid", a.getPteidMasked());
            row.put("cheatType", a.getCheatType());
            row.put("level", a.getLevel());
            row.put("riskScore", a.getRiskScore());
            row.put("state", a.getState());
            row.put("edition", a.getEdition());
            row.put("occurredAt", a.getOccurredAt());
            rows.add(row);
        }
        return rows;
    }

    // ---------- 工具 ----------

    private Instant window(Instant t) {
        return t.truncatedTo(ChronoUnit.DAYS);
    }

    private Instant reduceDays(int days, String startDate) {
        if (startDate != null && !startDate.isBlank()) return Instant.parse(startDate);
        // 窗口含今天共有 days 个自然日：从 (days-1) 天前的 0 点开始
        return Instant.now().minus(days - 1L, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
    }

    private Instant endOf(String endDate) {
        if (endDate != null && !endDate.isBlank()) return Instant.parse(endDate);
        return Instant.now();
    }

    /** 生成 [start, end] 逐自然日的 0 值映射。 */
    private Map<String, Long> emptyDayMap(Instant start, Instant end) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Instant d = start; !d.isAfter(end); d = d.plus(1, ChronoUnit.DAYS)) {
            m.put(day(d), 0L);
        }
        return m;
    }

    private List<String> keys(Map<String, Long> m) {
        return new ArrayList<>(m.keySet());
    }

    private List<Long> values(Map<String, Long> m) {
        return new ArrayList<>(m.values());
    }
}