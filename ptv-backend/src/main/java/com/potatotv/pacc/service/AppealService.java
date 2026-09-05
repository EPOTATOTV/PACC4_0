package com.potatotv.pacc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.Appeal;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v4.4 误报申诉服务：自动初筛证据快照 + 多级审核流（auto→level1→level2→final）+ 误报恢复。
 * <p>审批通过时撤销关联作弊记录并恢复玩家信誉；全程记审计日志（slf4j）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppealService {

    /** 初筛分≥该值可直接建议通过（历史信誉良好 + 有未撤销记录 + 证据完整）。 */
    private static final int PRESCREEN_AUTO_APPROVE = 85;

    private final AppealRepository appealRepository;
    private final CheatRecordRepository cheatRecordRepository;
    private final AccountRepository accountRepository;
    private final MailerService mailerService;
    private final ObjectMapper mapper;

    /**
     * 提交申诉：附随 alert_id 时抓取关联作弊记录生成证据快照；按初筛分决定进入自动通过还是人工审核。
     */
    @Transactional
    public Appeal submit(String pteid, String reason, String description, String alertId) {
        Appeal a = Appeal.builder()
                .appealId(UUID.randomUUID().toString())
                .pteid(pteid)
                .alertId(alertId)
                .reason(reason == null ? "误报申诉" : reason)
                .description(description == null ? "" : description)
                .status("pending")
                .createdAt(Instant.now())
                .build();

        String evidenceSnapshot = snapshot(pteid, alertId, reason);
        a.setEvidenceJson(evidenceSnapshot);
        a.setPrescreenScore(prescreenScore(pteid, alertId));
        if (a.getPrescreenScore() >= PRESCREEN_AUTO_APPROVE) {
            a.setReviewStage("final");
            a.setReviewRole("sys");
        } else {
            a.setReviewStage("level1");
            a.setReviewRole("support");
        }
        appealRepository.save(a);
        log.info("申诉提交 id={} pteid={} 初筛分={} 阶段={} 证据={}",
                a.getAppealId(), pteid, a.getPrescreenScore(), a.getReviewStage(),
                evidenceSnapshot == null ? "无" : "有");
        return a;
    }

    /** 审核动作：approve 通过 / reject 驳回 / advance 升级到下一级。 */
    @Transactional
    public Appeal review(String id, String action, String reviewer, String role, String comment) {
        Appeal a = appealRepository.findById(id).orElse(null);
        if (a == null) return null;
        switch (action == null ? "" : action.toLowerCase()) {
            case "approve" -> approve(a, reviewer, role, comment);
            case "reject" -> reject(a, reviewer, role, comment);
            case "advance" -> advance(a, reviewer, role);
            default -> throw new IllegalArgumentException("未知审核动作: " + action);
        }
        return a;
    }

    private void approve(Appeal a, String reviewer, String role, String comment) {
        a.setStatus("approved");
        a.setReviewStage("final");
        a.setReviewRole(role == null ? "techlead" : role);
        a.setReviewer(reviewer == null ? "admin" : reviewer);
        a.setReviewComment(comment == null ? "" : comment);
        a.setReviewedAt(Instant.now());
        appealRepository.save(a);

        // 误报恢复：撤销关联作弊记录
        if (a.getAlertId() != null && !a.getAlertId().isBlank()) {
            cheatRecordRepository.revokeByAlert(a.getAlertId());
            log.info("申诉通过，撤销关联作弊记录 alertId={}", a.getAlertId());
        }
        restoreAccount(a.getPteid());
        notifyPlayer(a, true);
    }

    private void reject(Appeal a, String reviewer, String role, String comment) {
        a.setStatus("rejected");
        a.setReviewer(reviewer == null ? "admin" : reviewer);
        a.setReviewRole(role == null ? "techlead" : role);
        a.setReviewComment(comment == null ? "" : comment);
        a.setReviewedAt(Instant.now());
        appealRepository.save(a);
        notifyPlayer(a, false);
    }

    /** 升级到下一审核阶段/角色。 */
    private void advance(Appeal a, String reviewer, String role) {
        switch (a.getReviewStage()) {
            case "level1" -> { a.setReviewStage("level2"); a.setReviewRole("analyst"); }
            case "level2" -> { a.setReviewStage("final"); a.setReviewRole("techlead"); }
            default -> a.setReviewStage("final");
        }
        a.setStatus("in_review");
        a.setReviewer(reviewer == null ? "admin" : reviewer);
        if (role != null && !role.isBlank()) a.setReviewRole(role);
        appealRepository.save(a);
    }

    /** 玩家信誉恢复：重置为 100，解锁被标记的账号状态（仅恢复因红屏触发的锁定）。 */
    private void restoreAccount(String pteid) {
        accountRepository.findById(pteid).ifPresent(acc -> {
            acc.setReputation(100);
            if ("locked_inspect".equals(acc.getStatus()) || "high_risk".equals(acc.getStatus())) {
                acc.setStatus("normal");
            }
            accountRepository.save(acc);
        });
    }

    private void notifyPlayer(Appeal a, boolean approved) {
        String email = accountRepository.findById(a.getPteid()).map(Account::getEmail).orElse(null);
        if (email == null) {
            log.info("玩家邮箱未知，跳过申诉结果通知 pteid={}", a.getPteid());
            return;
        }
        String subject = approved ? "PACC 申诉处理结果：已通过" : "PACC 申诉处理结果：未通过";
        String body = approved
                ? "<p>您好，您提交的申诉 <b>" + a.getAppealId() + "</b> 已审核通过。</p>"
                + "<p>如关联的作弊记录为误报，已被撤销；相关信誉已恢复。</p>"
                : "<p>您好，您提交的申诉 <b>" + a.getAppealId() + "</b> 经审核未通过。</p>"
                + "<p>说明：" + (a.getReviewComment() == null ? "" : a.getReviewComment()) + "</p>";
        mailerService.send(email, subject, body);
    }

    /** 抓取关联作弊记录生成证据快照 JSON。 */
    private String snapshot(String pteid, String alertId, String reason) {
        if (alertId == null || alertId.isBlank()) return null;
        try {
            Map<String, Object> ev = new LinkedHashMap<>();
            cheatRecordRepository.findFirstByAlertId(alertId).ifPresent(rec -> {
                ev.put("record_id", rec.getRecordId());
                ev.put("alert_id", rec.getAlertId());
                ev.put("cheat_type", rec.getCheatType());
                ev.put("level", rec.getLevel());
                ev.put("risk_score", rec.getRiskScore());
                ev.put("record_hash", rec.getRecordHash());
                ev.put("revoked", rec.isRevoked());
            });
            if (ev.isEmpty()) {
                ev.put("alert_id", alertId);
            }
            ev.put("reason", reason);
            ev.put("captured_at", Instant.now().toString());
            return mapper.writeValueAsString(ev);
        } catch (Exception e) {
            log.warn("申诉证据快照生成失败 alertId={}", alertId);
            return null;
        }
    }

    /**
     * 自动初筛分（0-100）：申诉类型 误报 + 玩家信誉越好 + 存在未撤销关联记录 → 越高。
     */
    int prescreenScore(String pteid, String alertId) {
        int score = 0;
        Account acc = accountRepository.findById(pteid).orElse(null);
        if (acc != null) score += Math.max(0, acc.getReputation() - 50); // 0..50
        if (alertId != null && !alertId.isBlank()) score += 20;
        boolean hasRecord = alertId != null && cheatRecordRepository.countByAlertIdAndRevokedFalse(alertId) > 0;
        if (hasRecord) score += 30;
        return Math.min(100, score);
    }
}