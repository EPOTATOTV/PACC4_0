package com.potatotv.pacc.service.apm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.ApmAlert;
import com.potatotv.pacc.domain.ApmMetricHourly;
import com.potatotv.pacc.repository.ApmAlertRepository;
import com.potatotv.pacc.repository.ApmMetricHourlyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * v5.4 §2.6 APM 阈值告警：按小时聚合指标对每个「平台 + 版本」桶做阈值判定并落 {@link ApmAlert}。
 *
 * <p>判定口径统一取 <b>p95</b>（不是均值，也不是 p50）：APM 关心的是「有多少人被影响」，
 * 均值会被大量健康采样稀释到看不见问题，p95 才代表受影响的那条尾巴。
 * 唯一例外是 {@code game_fps} / {@code client_report_success_rate} 这类「越低越差」的规则，
 * 用同一条 p95 反向比较（低于阈值即触发），语义仍然一致。</p>
 *
 * <p>按 {@code 平台 + 版本} 分桶而不是全局平均：全局平均会把「新版本在某个平台上劣化」这件事
 * 抹平成一条平线，而这正是版本回归最需要被看见的信号。</p>
 *
 * <p>去重与冷却：{@code alert_key = 规则|平台|版本}，冷却窗口内（默认 30 分钟）已有 OPEN 行则不重复生成，
 * 避免同一问题每小时刷一条。通知走飞书机器人 Webhook（{@code PACC_APM_ALERT_WEBHOOK_URL}），
 * 未配置时静默跳过——通知失败绝不能影响告警落库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApmAlertService {

    /**
     * 告警规则（对应设计文档 §2.6）。
     *
     * @param key          规则标识（进 alert_key，不可随意改）
     * @param name         告警名（管理端展示）
     * @param metric       判定的指标名（必须是 {@link ApmCatalog} 登记过的）
     * @param level        P0/P1/P2
     * @param threshold    阈值
     * @param lowerIsWorse true 表示低于阈值触发（帧率、成功率）
     * @param message      告警文案前缀
     */
    public record AlertRule(String key, String name, String metric, String level,
                            double threshold, boolean lowerIsWorse, String message) { }

    public static final List<AlertRule> RULES = List.of(
            new AlertRule("cpu_high", "CPU占用过高", "sys_cpu_process", "P1", 15, false,
                    "PACC 进程 CPU 占用过高"),
            new AlertRule("mem_high", "内存占用过高", "sys_mem_process", "P1", 500, false,
                    "PACC 进程内存占用过高"),
            new AlertRule("fps_impact", "FPS影响过大", "game_fps", "P0", 50, true,
                    "游戏帧率过低，反作弊组件疑似影响性能"),
            new AlertRule("input_latency", "输入延迟增加", "game_input_latency_p95", "P1", 5, false,
                    "输入延迟 P95 增高"),
            new AlertRule("detect_latency", "检测延迟过高", "detect_engine_latency", "P2", 50, false,
                    "检测引擎延迟过高"),
            new AlertRule("ai_timeout", "AI推理超时", "detect_ai_infer_latency", "P2", 100, false,
                    "AI 推理延迟超时"),
            new AlertRule("report_fail", "上报失败率高", "client_report_success_rate", "P1", 90, true,
                    "客户端上报成功率过低"),
            new AlertRule("crash_rate", "客户端崩溃率", "client_crash_count", "P0", 1, false,
                    "客户端崩溃次数异常"),
            new AlertRule("integrity_fail", "完整性校验失败", "client_integrity_state", "P0", 0.5, false,
                    "完整性校验失败率异常"),
            new AlertRule("antidebug_rate", "反调试触发率", "client_antidebug_state", "P1", 0.1, false,
                    "反调试触发率异常")
    );

    /** 通知体构造器（只用来拼飞书机器人的 JSON，无额外配置需求）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 列表返回条数上限。 */
    public static final int MAX_LIMIT = 200;

    private final ApmAlertRepository alerts;
    private final ApmMetricHourlyRepository hourly;

    /** 同一去重键的冷却窗口（分钟）。 */
    @Value("${pacc.apm.alert-cooldown-minutes:30}")
    private int cooldownMinutes = 30;

    /** 总开关：本地/离线联调时可关掉，避免定时任务持续写告警。 */
    @Value("${pacc.apm.alert-enabled:true}")
    private boolean alertEnabled = true;

    /** 飞书机器人 Webhook（未配置则不通知）。 */
    @Value("${pacc.apm.alert-webhook-url:${PACC_APM_ALERT_WEBHOOK_URL:}}")
    private String webhookUrl = "";

    /** 每小时第 15 分钟评估刚关闭的那一小时。 */
    @Scheduled(cron = "0 15 * * * ?")
    public void scheduledEvaluate() {
        Instant closed = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        try {
            int fired = evaluate(closed);
            if (fired > 0) log.info("APM 告警评估完成 hour={} 新增={}", closed, fired);
        } catch (Exception e) {
            log.warn("APM 告警评估失败 hour={} err={}", closed, e.getMessage());
        }
    }

    /**
     * 评估指定整点的告警。
     *
     * @param hour 目标小时（内部截断到整点）
     * @return 新增告警条数
     */
    @Transactional
    public int evaluate(Instant hour) {
        if (!alertEnabled) return 0;
        Instant from = hour.truncatedTo(ChronoUnit.HOURS);
        Instant to = from.plus(1, ChronoUnit.HOURS);
        List<ApmMetricHourly> rows = hourly.findByMetricHourBetween(from, to);
        if (rows.isEmpty()) return 0;

        Map<String, AlertRule> byMetric = new HashMap<>();
        for (AlertRule rule : RULES) byMetric.putIfAbsent(rule.metric(), rule);

        Instant now = Instant.now();
        Instant cooldownFrom = now.minus(Math.max(0, cooldownMinutes), ChronoUnit.MINUTES);
        int fired = 0;
        for (ApmMetricHourly row : rows) {
            AlertRule rule = byMetric.get(nz(row.getMetricName()));
            if (rule == null) continue;
            double observed = row.getP95Value();
            boolean breach = rule.lowerIsWorse() ? observed < rule.threshold() : observed > rule.threshold();
            if (!breach) continue;

            String key = rule.key() + "|" + nz(row.getPlatform()) + "|" + nz(row.getClientVer());
            Optional<ApmAlert> last = alerts.findFirstByAlertKeyAndStatusOrderByOccurredAtDesc(key, ApmAlert.Status.OPEN);
            if (last.isPresent() && last.get().getOccurredAt() != null
                    && last.get().getOccurredAt().isAfter(cooldownFrom)) {
                continue;   // 冷却窗口内已有未处理告警
            }

            ApmAlert alert = ApmAlert.builder()
                    .alertKey(key)
                    .alertName(rule.name())
                    .metricName(row.getMetricName())
                    .platform(nz(row.getPlatform()))
                    .clientVer(nz(row.getClientVer()))
                    .level(ApmAlert.Level.valueOf(rule.level()))
                    .threshold(rule.threshold())
                    .observed(round2(observed))
                    .message(rule.message() + "（p95=" + round2(observed) + "，阈值 " + rule.threshold() + "）")
                    .status(ApmAlert.Status.OPEN)
                    .occurredAt(now)
                    .build();
            alerts.save(alert);
            fired++;
            log.warn("APM 告警触发 key={} level={} metric={} observed={} threshold={}",
                    key, rule.level(), row.getMetricName(), round2(observed), rule.threshold());
            notify(alert);
        }
        return fired;
    }

    /**
     * 飞书机器人通知（best-effort）。
     *
     * <p>用虚拟线程异步发、5 秒超时、异常全部吞掉：通知是「顺带做的事」，
     * 一旦 Webhook 慢或不可达，绝不能反向拖住聚合任务或让告警落库失败。</p>
     */
    private void notify(ApmAlert alert) {
        if (webhookUrl == null || webhookUrl.isBlank()) return;
        String text = "[PACC APM][" + alert.getLevel() + "] " + alert.getAlertName()
                + " 平台=" + alert.getPlatform() + " 版本=" + alert.getClientVer()
                + " 指标=" + alert.getMetricName() + " 阈值=" + alert.getThreshold()
                + " 实测=" + alert.getObserved() + " 时间=" + alert.getOccurredAt();
        final String body;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("msg_type", "text");
            payload.put("content", Map.of("text", text));
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("APM 告警通知体构造失败 key={} err={}", alert.getAlertKey(), e.getMessage());
            return;
        }
        final String url = webhookUrl;
        Thread.startVirtualThread(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(5))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 300) {
                    log.warn("APM 告警通知返回异常 code={} key={}", response.statusCode(), alert.getAlertKey());
                }
            } catch (Exception e) {
                log.warn("APM 告警通知发送失败 key={} err={}", alert.getAlertKey(), e.getMessage());
            }
        });
    }

    /**
     * 确认告警。
     *
     * @throws NoSuchElementException  告警不存在
     * @throws IllegalStateException   已确认或已解决
     */
    @Transactional
    public ApmAlert ack(String id, String actor) {
        ApmAlert alert = alerts.findById(id).orElseThrow(() -> new NoSuchElementException("告警不存在"));
        if (alert.getStatus() != ApmAlert.Status.OPEN) {
            throw new IllegalStateException("告警已确认或已解决");
        }
        alert.setStatus(ApmAlert.Status.ACKED);
        alert.setAckedBy(actor == null || actor.isBlank() ? "api-key" : actor);
        alert.setAckedAt(Instant.now());
        return alerts.save(alert);
    }

    /**
     * 告警列表。
     *
     * @param status 状态过滤（OPEN/ACKED/RESOLVED），空或无法识别时不限状态
     * @param limit  返回条数，夹到 [1, 200]
     */
    public Map<String, Object> list(String status, int limit) {
        ApmAlert.Status parsed = parseStatus(status);
        List<ApmAlert> rows = parsed == null
                ? alerts.findTop100ByOrderByOccurredAtDesc()
                : alerts.findByStatusOrderByOccurredAtDesc(parsed);
        int max = Math.max(1, Math.min(MAX_LIMIT, limit));

        List<Map<String, Object>> items = new ArrayList<>();
        for (ApmAlert row : rows) {
            if (items.size() >= max) break;
            items.add(view(row));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("open_total", alerts.countByStatus(ApmAlert.Status.OPEN));
        return out;
    }

    /** 单条告警的管理端视图（字段名即前端契约）。 */
    public static Map<String, Object> view(ApmAlert alert) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", alert.getId());
        m.put("alert_name", nz(alert.getAlertName()));
        m.put("metric_name", nz(alert.getMetricName()));
        m.put("platform", nz(alert.getPlatform()));
        m.put("client_ver", nz(alert.getClientVer()));
        m.put("level", alert.getLevel() == null ? "" : alert.getLevel().name());
        m.put("threshold", alert.getThreshold());
        m.put("observed", alert.getObserved());
        m.put("message", nz(alert.getMessage()));
        m.put("status", alert.getStatus() == null ? "" : alert.getStatus().name());
        m.put("occurred_at", iso(alert.getOccurredAt()));
        m.put("acked_by", nz(alert.getAckedBy()));
        m.put("acked_at", iso(alert.getAckedAt()));
        return m;
    }

    private static ApmAlert.Status parseStatus(String status) {
        if (status == null || status.isBlank()) return null;
        try {
            return ApmAlert.Status.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;   // 非法状态按「不限」处理，避免管理端因为拼错参数看到空页
        }
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
}