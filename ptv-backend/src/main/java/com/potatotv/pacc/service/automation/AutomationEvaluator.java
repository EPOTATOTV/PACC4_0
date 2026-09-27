package com.potatotv.pacc.service.automation;

import com.potatotv.pacc.df.DfProperties;
import com.potatotv.pacc.domain.alert.AlertGroup;
import com.potatotv.pacc.domain.automation.AutomationExecution;
import com.potatotv.pacc.domain.automation.AutomationRule;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.AutomationExecutionRepository;
import com.potatotv.pacc.repository.AutomationRuleRepository;
import com.potatotv.pacc.repository.ClientCrashReportRepository;
import com.potatotv.pacc.repository.DetectionEventRepository;
import com.potatotv.pacc.repository.AlertGroupRepository;
import com.potatotv.pacc.repository.IocIndicatorRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * §4.3.3 自动化响应评估器：周期扫描「trigger → action」规则，命中阈值即调用对应动作处理器，
 * 并把每次执行落审计日志（{@code t_automation_execution}）。
 *
 * <p>五行内置规则（见 {@link #seedDefaults()}）：
 * <ol>
 *   <li>{@code FAMILY_DETECTION_BURST}：同一作弊家族 1 小时检测数超阈值 → {@code ESCALATE_SENSITIVITY}；</li>
 *   <li>{@code NEW_IOC}：窗口内出现新型 IOC → {@code GENERATE_RULE_GRAY}；</li>
 *   <li>{@code MISREPORT_RATE_HIGH}：误报成功率高 → {@code DOWNGRADE_RULE_ALERT}；</li>
 *   <li>{@code BACKEND_LOAD_HIGH}：后端 CPU 负载高 → {@code DEGRADE_DETECTIONS}；</li>
 *   <li>{@code CLIENT_CRASH_RATE_HIGH}：新客户端崩溃率高 → {@code PAUSE_CANARY_ROLLBACK}。</li>
 * </ol>
 *
 * <p>停用（{@code enabled=false}）的规则直接跳过，不产生任何动作或状态变更，即 no-op。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@SuppressWarnings("null") // 仓储/流式聚合 null 分析误报
public class AutomationEvaluator {

    /** 触发条件编码常量。 */
    public static final String TRIGGER_FAMILY_BURST = "FAMILY_DETECTION_BURST";
    public static final String TRIGGER_NEW_IOC = "NEW_IOC";
    public static final String TRIGGER_MISREPORT_RATE = "MISREPORT_RATE_HIGH";
    public static final String TRIGGER_BACKEND_LOAD = "BACKEND_LOAD_HIGH";
    public static final String TRIGGER_CRASH_RATE = "CLIENT_CRASH_RATE_HIGH";

    private final AutomationRuleRepository ruleRepository;
    private final AutomationExecutionRepository executionRepository;
    private final AutomationActionRegistry registry;
    private final DfProperties props;
    private final SystemLoadProvider systemLoadProvider;
    private final AlertGroupRepository alertGroupRepository;
    private final IocIndicatorRepository iocIndicatorRepository;
    private final AppealRepository appealRepository;
    private final ClientCrashReportRepository crashReportRepository;
    private final DetectionEventRepository detectionEventRepository;

    /** 启动时播种五条内置规则（按 code 幂等，已存在则不动）。 */
    @PostConstruct
    public void seedDefaults() {
        try {
            seed(TRIGGER_FAMILY_BURST, "作弊家族检测风暴", "family detection count >= 100 / 1h",
                    "ESCALATE_SENSITIVITY", 100.0, 60, 30);
            seed(TRIGGER_NEW_IOC, "新型 IOC 出现", "new ioc count >= 1 / window",
                    "GENERATE_RULE_GRAY", 1.0, 60, 30);
            seed(TRIGGER_MISREPORT_RATE, "误报成功率过高", "misreport rate > 0.20",
                    "DOWNGRADE_RULE_ALERT", 0.20, 1440, 60);
            seed(TRIGGER_BACKEND_LOAD, "后端负载过高", "backend cpu load > 80%",
                    "DEGRADE_DETECTIONS", 80.0, 15, 15);
            seed(TRIGGER_CRASH_RATE, "新客户端崩溃率过高", "client crash rate > 0.01",
                    "PAUSE_CANARY_ROLLBACK", 0.01, 60, 60);
        } catch (RuntimeException e) {
            log.warn("播种自动化规则失败 err={}", e.getMessage());
        }
    }

    private void seed(String code, String name, String expr, String action, double threshold,
                      int windowMin, int cooldownMin) {
        if (ruleRepository.findByCode(code).isPresent()) {
            return;
        }
        ruleRepository.save(AutomationRule.builder()
                .id("rule_" + code.toLowerCase())
                .code(code)
                .name(name)
                .triggerExpr(expr)
                .actionCode(action)
                .threshold(threshold)
                .windowMin(windowMin)
                .cooldownMin(cooldownMin)
                .enabled(true)
                .builtin(true)
                .build());
    }

    /** 定时评估（周期取自 {@code pacc.df.automation.evaluate-interval-ms}，默认 1 分钟）。 */
    @Scheduled(fixedDelayString = "${pacc.df.automation.evaluate-interval-ms:60000}", initialDelay = 45_000)
    public void tick() {
        try {
            List<AutomationExecution> executions = evaluate("scheduler");
            if (!executions.isEmpty()) {
                log.info("自动化响应本轮执行 {} 条", executions.size());
            }
        } catch (RuntimeException e) {
            log.warn("自动化响应对评估失败 err={}", e.getMessage());
        }
    }

    /**
     * 评估全部启用规则并执行命中的动作。
     *
     * @return 本轮产生的执行审计记录
     */
    @Transactional
    public List<AutomationExecution> evaluate(String actor) {
        if (!props.getAutomation().isEnabled()) {
            return List.of();
        }
        List<AutomationExecution> results = new ArrayList<>();
        for (AutomationRule rule : ruleRepository.findAllByOrderByCodeAsc()) {
            if (!rule.isEnabled() || inCooldown(rule)) {
                continue;
            }
            Map<String, Object> metrics = collectMetrics(rule);
            if (!fired(rule, metrics)) {
                continue;
            }
            results.add(execute(rule, metrics, actor));
        }
        return results;
    }

    /** 执行单条规则动作（含审计落库与 lastFiredAt 更新）。 */
    private AutomationExecution execute(AutomationRule rule, Map<String, Object> metrics, String actor) {
        Optional<AutomationActionHandler> handler = registry.find(rule.getActionCode());
        if (handler.isEmpty()) {
            return save(rule, AutomationExecution.ST_SKIPPED, "未注册动作: " + rule.getActionCode(), false, actor);
        }
        try {
            AutomationActionResult result = handler.get().execute(new AutomationContext(rule, metrics, actor));
            AutomationExecution execution = save(rule, result.applied()
                    ? AutomationExecution.ST_SUCCESS : AutomationExecution.ST_SKIPPED,
                    result.detail(), result.reversible(), actor);
            if (result.applied()) {
                rule.setLastFiredAt(Instant.now());
                rule.setUpdatedAt(Instant.now());
                ruleRepository.save(rule);
            }
            return execution;
        } catch (RuntimeException e) {
            log.warn("自动化动作执行失败 rule={} action={} err={}", rule.getCode(), rule.getActionCode(), e.getMessage());
            return save(rule, AutomationExecution.ST_FAILED, "执行失败: " + e.getMessage(), false, actor);
        }
    }

    private AutomationExecution save(AutomationRule rule, String status, String detail,
                                     boolean reversible, String actor) {
        return executionRepository.save(AutomationExecution.builder()
                .ruleCode(rule.getCode())
                .actionCode(rule.getActionCode())
                .status(status)
                .detail(truncate(detail, 1990))
                .reversible(reversible)
                .reverted(false)
                .executedAt(Instant.now())
                .executedBy(actor)
                .build());
    }

    /** 采集规则所需的观测指标（仅取窗口内数据）。 */
    private Map<String, Object> collectMetrics(AutomationRule rule) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofMinutes(Math.max(1, rule.getWindowMin())));
        metrics.put("windowMin", rule.getWindowMin());
        switch (rule.getCode() == null ? "" : rule.getCode()) {
            case TRIGGER_FAMILY_BURST -> {
                Map<String, Integer> byFamily = new LinkedHashMap<>();
                for (AlertGroup g : alertGroupRepository.findByLastSeenAtAfter(since)) {
                    if (g.getFamilyCode() == null || g.getFamilyCode().isBlank()) {
                        continue;
                    }
                    byFamily.merge(g.getFamilyCode(), g.getSignalCount(), Integer::sum);
                }
                Map.Entry<String, Integer> top = byFamily.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).orElse(null);
                metrics.put("family", top == null ? "" : top.getKey());
                metrics.put("count", top == null ? 0 : top.getValue());
            }
            case TRIGGER_NEW_IOC -> metrics.put("count", iocIndicatorRepository.countByFirstSeenAfter(since));
            case TRIGGER_MISREPORT_RATE -> {
                long total = appealRepository.count();
                long misreport = appealRepository.countByAutoReview("MISREPORT");
                metrics.put("totalAppeals", total);
                metrics.put("misreportCount", misreport);
                metrics.put("misreportRate", total == 0 ? 0.0 : misreport / (double) total);
            }
            case TRIGGER_BACKEND_LOAD -> metrics.put("load", round2(systemLoadProvider.cpuLoad() * 100.0));
            case TRIGGER_CRASH_RATE -> {
                long crashes = crashReportRepository.countByCreatedAtAfter(since);
                long clients = detectionEventRepository.countDistinctPteidBetween(since, now);
                metrics.put("crashes", crashes);
                metrics.put("activeClients", clients);
                metrics.put("crashRate", clients == 0 ? 0.0 : crashes / (double) clients);
            }
            default -> {
                // 未知触发条件：不采集指标，规则不会命中
            }
        }
        return metrics;
    }

    /** 判定触发条件是否成立。 */
    private boolean fired(AutomationRule rule, Map<String, Object> metrics) {
        double threshold = rule.getThreshold();
        return switch (rule.getCode() == null ? "" : rule.getCode()) {
            case TRIGGER_FAMILY_BURST, TRIGGER_NEW_IOC -> number(metrics, "count") >= threshold;
            case TRIGGER_MISREPORT_RATE -> number(metrics, "misreportRate") > threshold;
            case TRIGGER_BACKEND_LOAD -> number(metrics, "load") > threshold;
            case TRIGGER_CRASH_RATE -> number(metrics, "crashRate") > threshold;
            default -> false;
        };
    }

    private boolean inCooldown(AutomationRule rule) {
        Instant last = rule.getLastFiredAt();
        if (last == null) {
            return false;
        }
        int cooldown = rule.getCooldownMin() <= 0
                ? Math.max(1, props.getAutomation().getDefaultCooldownMinutes())
                : rule.getCooldownMin();
        return last.plus(Duration.ofMinutes(cooldown)).isAfter(Instant.now());
    }

    /* -------------------------------- 管理端只读 / 启停 -------------------------------- */

    /** 全部规则（按 code 排序）。 */
    @Transactional(readOnly = true)
    public List<AutomationRule> listRules() {
        return ruleRepository.findAllByOrderByCodeAsc();
    }

    /** 启用 / 停用规则（停用后评估器跳过，即 no-op）。 */
    @Transactional
    public AutomationRule toggle(String code, boolean enabled) {
        AutomationRule rule = ruleRepository.findByCode(code)
                .orElseThrow(() -> new IllegalArgumentException("自动化规则不存在: " + code));
        rule.setEnabled(enabled);
        rule.setUpdatedAt(Instant.now());
        return ruleRepository.save(rule);
    }

    /** 执行历史分页。 */
    @Transactional(readOnly = true)
    public Map<String, Object> executions(int page, int size) {
        PageRequest pg = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "executedAt"));
        Page<AutomationExecution> data = executionRepository.findAllByOrderByExecutedAtDesc(pg);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", data.getContent());
        out.put("total", data.getTotalElements());
        out.put("page", data.getNumber());
        out.put("totalPages", data.getTotalPages());
        return out;
    }

    /** 回滚一次已生效的自动动作（依据执行明细恢复前值）。 */
    @Transactional
    public AutomationExecution revert(Long executionId, String actor) {
        AutomationExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("执行记录不存在: " + executionId));
        if (execution.isReverted()) {
            throw new IllegalStateException("该执行已回滚: " + executionId);
        }
        AutomationActionHandler handler = registry.find(execution.getActionCode())
                .orElseThrow(() -> new IllegalArgumentException("未注册动作: " + execution.getActionCode()));
        AutomationActionResult result = handler.revert(execution);
        execution.setReverted(result.applied());
        execution.setRevertDetail(truncate(result.detail(), 1990));
        executionRepository.save(execution);
        log.info("自动化动作回滚 id={} action={} by={} detail={}",
                executionId, execution.getActionCode(), actor, result.detail());
        return execution;
    }

    private static double number(Map<String, Object> metrics, String key) {
        Object v = metrics.get(key);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}