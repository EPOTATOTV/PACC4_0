package com.potatotv.pacc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.CheatRecord;
import com.potatotv.pacc.domain.RedscreenAlert;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 红屏警告服务：事件生成、全在线广播、冷却去重、作弊记录（哈希链）、账号标记、查端入队。
 * 系统唯一的作弊响应手段（不封禁、不踢出）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // 流/存储层泛型 null 分析误报（本地定性安全）
public class RedscreenService {

    private final RedscreenAlertRepository alertRepository;
    private final AccountRepository accountRepository;
    private final CheatRecordRepository cheatRecordRepository;
    private final OnlineStatusService onlineStatusService;
    private final InspectService inspectService;
    private final WebhookDispatcher webhookDispatcher;
    private final ObjectMapper mapper;

    @Value("${pacc.detection.redscreen-threshold:85}")
    private int redscreenThreshold;
    @Value("${pacc.detection.severe-threshold:95}")
    private int severeThreshold;
    @Value("${pacc.detection.suspicious-low:70}")
    private int suspiciousLow;
    @Value("${pacc.detection.cooldown-minutes:10}")
    private int cooldownMinutes;

    /**
     * 依据风险评分决策。返回是否触发红屏。
     * 0-69 记录；70-84 可疑记录；85-94 二级红屏；95+ 三级红屏（优先查端）。
     */
    @Transactional
    public RedscreenAlert decideAndHandle(String pteid, String cheatType, int riskScore, String edition, String detail) {
        if (riskScore < redscreenThreshold) {
            // 记录为可疑（不入红屏库）
            return null;
        }
        // 冷却期去重：同账号同类型 10 分钟内不重复触发
        if (alertRepository.existsByPteidAndCheatTypeAndOccurredAtAfter(
                pteid, cheatType, Instant.now().minusSeconds(cooldownMinutes * 60L))) {
            return null;
        }

        Account account = accountRepository.findById(pteid).orElse(null);
        int level = riskScore >= severeThreshold ? 3 : 2;

        String alertId = "alert_" + Instant.now().toEpochMilli() + "_" + UUID.randomUUID().toString().substring(0, 4);
        long online = onlineStatusService.onlineCount();

        RedscreenAlert alert = RedscreenAlert.builder()
                .alertId(alertId)
                .level(level)
                .cheatType(cheatType)
                .pteid(pteid)
                .pteidMasked(mask(pteid))
                .riskScore(riskScore)
                .edition(edition)
                .state("PENDING_INSPECT")
                .broadcastOnline(online)
                .occurredAt(Instant.now())
                .build();
        alertRepository.save(alert);

        // 永久作弊记录 + 哈希链
        appendCheatRecord(alertId, pteid, cheatType, level, riskScore);

        // 账号状态标记
        if (account != null) {
            account.setStatus("locked_inspect");
            account.setTotalRedscreen(account.getTotalRedscreen() + 1);
            account.setLastRedScreenTime(Instant.now());
            if (account.getTotalRedscreen() >= 2) account.setStatus("high_risk");
            accountRepository.save(account);
        }

        // 查端入队
        int queuePosition;
        try {
            queuePosition = inspectService.enqueue(pteid, alertId, level);
        } catch (Exception e) {
            log.warn("查端入队失败，降级仅记录 pteid={} err={}", pteid, e.getMessage());
            queuePosition = 0;
        }

        // 全在线广播
        long ack = broadcast(alert, account);
        alert.setBroadcastAck(ack);
        alertRepository.save(alert);

        // Webhook 推送
        try {
            webhookDispatcher.onRedscreen(alert, queuePosition);
        } catch (Exception e) {
            log.warn("Webhook 推送失败 alert={} err={}", alertId, e.getMessage());
        }

        return alert;
    }

    private long broadcast(RedscreenAlert alert, Account cheater) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("event_type", "redscreen_alert");
        msg.put("alert_id", alert.getAlertId());
        msg.put("level", alert.getLevel());
        msg.put("cheat_type", alert.getCheatType());
        msg.put("pteid_masked", alert.getPteidMasked());
        msg.put("timestamp", alert.getOccurredAt().toString());
        msg.put("risk_score", alert.getRiskScore());
        msg.put("game_edition", alert.getEdition());
        try {
            return onlineStatusService.broadcast(mapper.writeValueAsString(msg));
        } catch (Exception e) {
            log.warn("广播序列化失败", e);
            return 0;
        }
    }

    private void appendCheatRecord(String alertId, String pteid, String cheatType, int level, int riskScore) {
        String prev = cheatRecordRepository.findTopByOrderByRecordHashDesc()
                .map(CheatRecord::getRecordHash).orElse("GENESIS");
        String recordHash = hash(prev + "|" + alertId + "|" + pteid + "|" + cheatType);
        cheatRecordRepository.save(CheatRecord.builder()
                .recordId(UUID.randomUUID().toString())
                .pteid(pteid)
                .alertId(alertId)
                .cheatType(cheatType)
                .level(level)
                .riskScore(riskScore)
                .prevHash(prev)
                .recordHash(recordHash)
                .occurredAt(Instant.now())
                .build());
    }

    public static String mask(String pteid) {
        if (pteid == null || pteid.length() < 4) return "****";
        return pteid.substring(0, 2) + "***" + pteid.substring(pteid.length() - 2);
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}