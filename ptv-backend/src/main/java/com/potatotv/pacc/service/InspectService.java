package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.InspectSession;
import com.potatotv.pacc.repository.InspectSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 管理员远程查端服务：队列管理、查端会话、结论与解除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // 存储层/取值表达式的 null 分析误报
public class InspectService {

    private final InspectSessionRepository sessionRepository;
    private final com.potatotv.pacc.repository.AccountRepository accountRepository;
    private final com.potatotv.pacc.repository.RedscreenAlertRepository alertRepository;
    private final com.potatotv.pacc.repository.CheatRecordRepository cheatRecordRepository;
    private final OnlineStatusService onlineStatusService;
    private final InspectSignalBus inspectSignalBus;

    public int enqueue(String pteid, String alertId, int level) {
        InspectSession s = InspectSession.builder()
                .sessionId(UUID.randomUUID().toString())
                .pteid(pteid)
                .alertId(alertId)
                .state("QUEUED")
                .expiresAt(Instant.now().plusSeconds(30 * 60)) // 单次最长 30 分钟
                .build();
        sessionRepository.save(s);
        // 在线玩家即时通知其进入查端界面（简化：广播事件已含锁定信息）
        return (int) sessionRepository.findByStateOrderByStartedAtDesc("QUEUED").size();
    }

    public List<InspectSession> pending() {
        return sessionRepository.findByStateOrderByStartedAtDesc("QUEUED");
    }

    public List<InspectSession> all() {
        return sessionRepository.findAll();
    }

    /** 管理员开始查端。 */
    @Transactional
    public InspectSession start(String sessionId, String operator) {
        InspectSession s = sessionRepository.findById(sessionId).orElseThrow();
        s.setState("ACTIVE");
        s.setOperator(operator);
        s.setStartedAt(Instant.now());
        s.setAuditLog(s.getAuditLog() + timestamp() + " operator=" + operator + " start-inspect\n");
        sessionRepository.save(s);

        // 建立信令总线玩家腿并下发查端请求（玩家离线则留待管理端腿连接占位）
        WebSocketSession player = onlineStatusService.firstSession(s.getPteid()).orElse(null);
        inspectSignalBus.registerPlayer(sessionId, player, s.getPteid());
        if (player != null) {
            inspectSignalBus.sendToPlayer(sessionId, signal("inspect_request", s, null));
            log.info("已下发查端请求 sessionId={} pteid={}", sessionId, s.getPteid());
        } else {
            log.info("查端目标玩家离线，等待其上线/管理端连接 sessionId={} pteid={}", sessionId, s.getPteid());
        }
        return s;
    }

    /**
     * 提交查端结论并解除限制。
     * conclusion=confirmed 确认作弊（记录永久保留）；false_positive 误报（撤销记录、恢复信誉）。
     */
    @Transactional
    public com.potatotv.pacc.domain.Account conclude(String sessionId, String conclusion, String note) {
        InspectSession s = sessionRepository.findById(sessionId).orElseThrow();
        s.setConclusion(conclusion);
        s.setState("DONE");
        s.setAuditLog(s.getAuditLog() + timestamp() + " conclusion=" + conclusion + " note=" + note + "\n");
        sessionRepository.save(s);

        // 更新红屏记录状态
        var account = accountRepository.findById(s.getPteid()).orElseThrow();
        if ("false_positive".equalsIgnoreCase(conclusion)) {
            alertRepository.findById(s.getAlertId()).ifPresent(a -> {
                a.setState("FALSE_POSITIVE");
                a.setInspectConclusion("FALSE_POSITIVE");
                a.setResolvedAt(Instant.now());
                alertRepository.save(a);
            });
            account.setStatus("normal");
            account.setReputation(Math.min(100, account.getReputation() + 10));
            // 撤销对应的永久作弊记录（保留行仅标记 revoked，统计不再计入）
            if (s.getAlertId() != null) {
                cheatRecordRepository.revokeByAlert(s.getAlertId());
            }
        } else if ("confirmed".equalsIgnoreCase(conclusion)) {
            alertRepository.findById(s.getAlertId()).ifPresent(a -> {
                a.setState("CONFIRMED");
                a.setInspectConclusion("CONFIRMED");
                a.setResolvedAt(Instant.now());
                alertRepository.save(a);
            });
            account.setStatus(account.getTotalRedscreen() >= 2 ? "high_risk" : "normal");
        }
        accountRepository.save(account);
        // 查端结束：通知玩家端解除监管，并释放信令总线腿
        inspectSignalBus.sendToPlayer(sessionId, signal("inspect_bye", s, conclusion));
        inspectSignalBus.sendToAdmin(sessionId, signal("inspect_closed", s, conclusion));
        return account;
    }

    /** 组装一条下发/转发给玩家或管理端的查端信令（B1 文本取证协议）。 */
    private String signal(String type, InspectSession s, String extra) {
        StringBuilder sb = new StringBuilder("{\"type\":\"").append(type)
                .append("\",\"session_id\":\"").append(s.getSessionId())
                .append("\",\"pteid\":\"").append(s.getPteid()).append('"');
        if (extra != null) sb.append(",\"extra\":\"").append(extra).append('"');
        return sb.append('}').toString();
    }

    private String timestamp() {
        return java.time.OffsetDateTime.now().toString();
    }
}