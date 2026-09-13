package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.ReputationLog;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.ReputationLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 信誉分引擎：围绕 Account.reputation（0-100）提供加减分、变更审计明细、
 * 等级/权益推导与近 30 天趋势。作弊扣分、申诉通过恢复加分等由上游触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReputationService {

    public static final int MAX = 100;
    public static final int MIN = 0;

    private final AccountRepository accountRepository;
    private final ReputationLogRepository logRepository;

    /** 调整信誉分并记录明细；不存在的账号忽略（无需建号）。 */
    @Transactional
    public boolean adjust(String pteid, int delta, String reason, String source) {
        Account acc = accountRepository.findById(pteid).orElse(null);
        if (acc == null) {
            log.warn("信誉调整跳过：无账号 pteid={}", pteid);
            return false;
        }
        int before = acc.getReputation();
        int after = Math.max(MIN, Math.min(MAX, before + delta));
        acc.setReputation(after);
        accountRepository.save(acc);
        logRepository.save(ReputationLog.builder()
                .id(UUID.randomUUID().toString())
                .pteid(pteid)
                .delta(after - before)
                .scoreAfter(after)
                .reason(reason == null ? "调整" : reason)
                .source(source)
                .createdAt(Instant.now())
                .build());
        log.info("信誉调整 pteid={} {}±{} -> {} reason={}", pteid, before, delta, after, reason);
        return true;
    }

    /** 玩家视角：当前分 + 等级 + 权益 + 近 30 天趋势。 */
    public Map<String, Object> playerSummary(String pteid) {
        Account acc = accountRepository.findById(pteid).orElse(null);
        int score = acc == null ? 100 : clamp(acc.getReputation());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("tier", tier(score));
        out.put("equities", equities(tier(score)));
        out.put("trend", trend(pteid));
        return out;
    }

    /** 管理视角：某玩家信誉明细（最近 N 条变更 + 汇总）。 */
    public Map<String, Object> adminDetail(String pteid, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        Account acc = accountRepository.findById(pteid).orElse(null);
        int score = acc == null ? 100 : clamp(acc.getReputation());
        out.put("pteid", pteid);
        out.put("score", score);
        out.put("tier", tier(score));
        int n = Math.max(1, Math.min(100, limit));
        List<Map<String, Object>> logs = new ArrayList<>();
        for (ReputationLog l : logRepository.findByPteidOrderByCreatedAtDesc(pteid).stream().limit(n).toList()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("delta", l.getDelta());
            m.put("score_after", l.getScoreAfter());
            m.put("reason", l.getReason());
            m.put("source", l.getSource());
            m.put("created_at", l.getCreatedAt() == null ? "" : l.getCreatedAt().toString());
            logs.add(m);
        }
        out.put("logs", logs);
        return out;
    }

    /** 近 30 天信誉分快照（每日最后分值），供前端折线图。 */
    public List<Map<String, Object>> trend(String pteid) {
        Instant start = Instant.now().minus(30, ChronoUnit.DAYS);
        List<ReputationLog> logs = logRepository.findByPteidAndCreatedAtAfterOrderByCreatedAtAsc(pteid, start);
        List<Map<String, Object>> trend = new ArrayList<>();
        java.util.Map<String, Integer> byDay = new java.util.TreeMap<>();
        int current = 100;
        for (ReputationLog l : logs) {
            if (l.getCreatedAt() == null) continue;
            byDay.put(l.getCreatedAt().toString().substring(0, 10), l.getScoreAfter());
        }
        for (int i = 29; i >= 0; i--) {
            String day = Instant.now().minus(i, ChronoUnit.DAYS).toString().substring(0, 10);
            Integer v = byDay.get(day);
            if (v != null) current = v;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", day);
            p.put("score", current);
            trend.add(p);
        }
        return trend;
    }

    public static String tier(int score) {
        if (score >= 90) return "TRUSTED";
        if (score >= 70) return "NORMAL";
        if (score >= 40) return "MONITORED";
        return "RESTRICTED";
    }

    public static List<String> equities(String tier) {
        return switch (tier) {
            case "TRUSTED" -> List.of("全量赛事参与", "优先客服通道", "申诉绿色通道");
            case "NORMAL" -> List.of("标准赛事参与", "标准客服通道");
            case "MONITORED" -> List.of("赛事报名需复核", "查端频率提升");
            default -> List.of("仅可申诉与查看记录", "限流更严格");
        };
    }

    private int clamp(int v) { return Math.max(MIN, Math.min(MAX, v)); }
}