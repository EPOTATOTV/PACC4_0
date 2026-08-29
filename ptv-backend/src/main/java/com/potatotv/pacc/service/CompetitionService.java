package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Enrollment;
import com.potatotv.pacc.domain.MatchSession;
import com.potatotv.pacc.domain.SuspicionFlag;
import com.potatotv.pacc.domain.TournamentConfig;
import com.potatotv.pacc.domain.TournamentNotice;
import com.potatotv.pacc.domain.TournamentStage;
import com.potatotv.pacc.repository.EnrollmentRepository;
import com.potatotv.pacc.repository.LoginEventRepository;
import com.potatotv.pacc.repository.MatchSessionRepository;
import com.potatotv.pacc.repository.SuspicionFlagRepository;
import com.potatotv.pacc.repository.TournamentConfigRepository;
import com.potatotv.pacc.repository.TournamentNoticeRepository;
import com.potatotv.pacc.repository.TournamentStageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 赛事风控聚合服务。
 * <ul>
 *   <li>登录事件落库（网关侧底座）；</li>
 *   <li>代练/共享聚合检测：短期多设备/多 IP 交替、设备抖动 → 生成带哈希链的嫌疑；</li>
 *   <li>参赛门禁：管理员绑定 PTEID+许可设备并审批，玩家自查。</li>
 * </ul>
 * 进程内实现，演示/单实例适用；生产多实例建议把 "记住最近登录事件" 的下游改为集中存储。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class CompetitionService {

    /** 判定"短期交替"的窗口（24 小时）。 */
    private static final Duration WINDOW = Duration.ofHours(24);
    /** 共享/代练：窗口内设备数与 IP 数的下限。 */
    private static final int MIN_DISTINCT_DEVICES = 2;
    private static final int MIN_DISTINCT_IPS = 2;

    private final LoginEventRepository loginEventRepository;
    private final SuspicionFlagRepository flagRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final MatchSessionRepository matchRepository;
    private final TournamentStageRepository stageRepository;
    private final TournamentNoticeRepository noticeRepository;
    private final TournamentConfigRepository configRepository;

    /** 记录一次登录并触发聚合检测。 */
    public void recordLogin(String pteid, String hashedFp, String ip) {
        if (pteid == null) return;
        loginEventRepository.save(LoginEvent.builder()
                .pteid(pteid)
                .deviceFingerprint(hashedFp)
                .ip(ip)
                .createdAt(Instant.now())
                .build());
        try {
            aggregate(pteid);
        } catch (Exception e) {
            // 聚合失败不影响登录主流程
            log.warn("代练聚合检测失败 pteid={}: {}", pteid, e.getMessage());
        }
    }

    /** 网关聚合：短期多设备 / 多 IP 交替 → 代练/共享嫌疑。 */
    private void aggregate(String pteid) {
        Instant after = Instant.now().minus(WINDOW);
        List<LoginEvent> events = loginEventRepository.findByPteidAndCreatedAtAfterOrderByCreatedAtDesc(pteid, after);
        if (events.size() < MIN_DISTINCT_DEVICES) return;

        long deviceCount = events.stream().map(LoginEvent::getDeviceFingerprint).distinct().count();
        long ipCount = events.stream().map(LoginEvent::getIp).distinct().count();
        if (deviceCount >= MIN_DISTINCT_DEVICES && ipCount >= MIN_DISTINCT_IPS) {
            String summary = "PTEID " + pteid + " 在 " + WINDOW.toHours() + " 小时内使用 " + deviceCount
                    + " 台设备 / " + ipCount + " 个 IP 交替登录，疑似代练或共享账号";
            createFlag(pteid, SuspicionFlag.Kind.SHARED_ACCOUNT_MULTI_DEVICE, summary,
                    Math.min(100, (int) (45 + deviceCount * 15 + ipCount * 8)));
        } else if (ipCount >= MIN_DISTINCT_IPS) {
            String summary = "PTEID " + pteid + " 在 " + WINDOW.toHours() + " 小时内使用 " + ipCount
                    + " 个不同 IP 登录，疑似网络代练/共享";
            createFlag(pteid, SuspicionFlag.Kind.SHARED_ACCOUNT_MULTI_IP, summary,
                    Math.min(80, 50 + (int) ipCount * 10));
        }
    }

    /** 创建带证据哈希链的嫌疑记录（链保证证据可复核、不可静默删除）。 */
    private void createFlag(String pteid, SuspicionFlag.Kind kind, String summary, int weight) {
        SuspicionFlag prev = topFlag(pteid);
        String prevHash = prev == null ? "TEAM-GENESIS" : prev.getChainHash();
        String chainHash = sha256(prevHash + "||" + canonical(summary));
        boolean duringMatch = matchRepository
                .findFirstByPteidAndStatusOrderByStartedAtDesc(pteid, MatchSession.Status.ACTIVE)
                .isPresent();
        SuspicionFlag flag = SuspicionFlag.builder()
                .flagId(java.util.UUID.randomUUID().toString())
                .pteid(pteid)
                .kind(kind)
                .detail(summary)
                .weight(weight)
                .evidenceSummary(summary)
                .prevChainHash(prevHash)
                .chainHash(chainHash)
                .status(SuspicionFlag.Status.OPEN)
                .duringMatch(duringMatch)
                .build();
        flagRepository.save(flag);
        log.info("生成赛事风控嫌疑 {} chainHash={} duringMatch={}", kind, chainHash, duringMatch);
    }

    private SuspicionFlag topFlag(String pteid) {
        List<SuspicionFlag> list = flagRepository.findByPteidOrderByCreatedAtDesc(pteid);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 裁判复核：OPEN → REVIEWED / BAN。 */
    public void review(String flagId, String decision, String reviewer, String comment) {
        flagRepository.findById(flagId).ifPresent(f -> {
            f.setStatus("ban".equalsIgnoreCase(decision)
                    ? SuspicionFlag.Status.ESB : SuspicionFlag.Status.REVIEWED);
            f.setReviewedAt(Instant.now());
            if (reviewer != null) f.setReviewer(reviewer);
            if (comment != null) f.setReviewComment(comment);
            flagRepository.save(f);
        });
    }

    public List<SuspicionFlag> flagList(String keyword) {
        if (keyword == null || keyword.isBlank()) return flagRepository.findAllByOrderByCreatedAtDesc();
        String kw = keyword.trim().toLowerCase();
        return flagRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(f -> (f.getPteid() != null && f.getPteid().toLowerCase().contains(kw))
                        || (f.getDetail() != null && f.getDetail().toLowerCase().contains(kw)))
                .toList();
    }

    public List<Enrollment> enrollmentList() {
        return enrollmentRepository.findAllByOrderByCreatedAtDesc();
    }

    /** 报名并提交审批。 */
    public Enrollment enroll(String tournamentId, String pteid, String displayName,
                             String permittedFp, String operator) {
        Enrollment e = Enrollment.builder()
                .tournamentId(tournamentId)
                .pteid(pteid)
                .displayName(displayName)
                .permittedDeviceFingerprint(permittedFp)
                .status(Enrollment.Status.PENDING)
                .operator(operator)
                .build();
        return enrollmentRepository.save(e);
    }

    /** 审批门禁。 */
    public void approve(String enrollmentId, boolean approve, String operator, String note) {
        enrollmentRepository.findById(enrollmentId).ifPresent(e -> {
            e.setStatus(approve ? Enrollment.Status.APPROVED : Enrollment.Status.REJECTED);
            e.setApprovedAt(Instant.now());
            e.setOperator(operator);
            if (note != null) e.setNote(note);
            enrollmentRepository.save(e);
        });
    }

    /** 主办方为已报名选手分配/修改队伍标签（队伍名+徽章颜色）。 */
    @Transactional
    public Enrollment setTeam(String enrollmentId, String teamName, String teamColor) {
        return enrollmentRepository.findById(enrollmentId).map(e -> {
            if (teamName != null) e.setTeamName(teamName);
            if (teamColor != null) e.setTeamColor(teamColor);
            return enrollmentRepository.save(e);
        }).orElse(null);
    }

    /** 批量将多个报名分到同一队伍。返回实际更新条数。 */
    @Transactional
    public int setTeamBatch(List<String> enrollmentIds, String teamName, String teamColor) {
        int n = 0;
        for (String id : enrollmentIds) {
            Enrollment e = this.setTeam(id, teamName, teamColor);
            if (e != null) n++;
        }
        return n;
    }

    /** 玩家自查：是否已报名、是否在比赛中使用被许可的设备。 */
    public Map<String, Object> playerEnrollmentStatus(String pteid, String currentDeviceFp) {
        java.util.Optional<Enrollment> opt = enrollmentRepository.findFirstByPteidOrderByCreatedAtDesc(pteid);
        if (opt.isEmpty()) {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("enrolled", false);
            m.put("permitted", false);
            m.put("canEnterMatch", false);
            m.put("enrollment", null);
            return m;
        }
        Enrollment e = opt.get();
        boolean permitted = e.getPermittedDeviceFingerprint() != null
                && e.getPermittedDeviceFingerprint().equals(currentDeviceFp);
        boolean approved = e.getStatus() == Enrollment.Status.APPROVED;
        return Map.of(
                "enrolled", true,
                "status", e.getStatus().name(),
                "permitted", permitted,
                "canEnterMatch", approved && permitted,
                "enrollment", e);
    }

    // ---------------- 赛事报名（腾讯文档收集表 + 设备绑定） ----------------

    public TournamentConfig config(String tournamentId) {
        return configRepository.findById(tournamentId).orElse(null);
    }

    @Transactional
    public TournamentConfig updateConfig(String tournamentId, String title, String tencentDocUrl,
                                         Instant applyDeadline, Boolean allowRegister, String note) {
        TournamentConfig c = configRepository.findById(tournamentId).orElseGet(() ->
                TournamentConfig.builder().tournamentId(tournamentId).build());
        if (title != null) c.setTitle(title);
        if (tencentDocUrl != null) c.setTencentDocUrl(tencentDocUrl);
        if (applyDeadline != null) c.setApplyDeadline(applyDeadline);
        if (allowRegister != null) c.setAllowRegister(allowRegister);
        if (note != null) c.setNote(note);
        c.setUpdatedAt(Instant.now());
        return configRepository.save(c);
    }

    /** 玩家报名页所需信息：赛事配置 + 我的报名状态 + 报名人数统计。 */
    public Map<String, Object> registerInfo(String tournamentId, String pteid, String currentDeviceFp) {
        TournamentConfig cfg = config(tournamentId);
        Map<String, Object> status = playerEnrollmentStatus(pteid, currentDeviceFp);
        long submitted = enrollmentList().stream()
                .filter(e -> tournamentId.equals(e.getTournamentId())).count();
        long pending = enrollmentList().stream()
                .filter(e -> tournamentId.equals(e.getTournamentId())
                        && e.getStatus() == Enrollment.Status.PENDING).count();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("tournament_id", tournamentId);
        out.put("title", cfg == null ? null : cfg.getTitle());
        out.put("tencent_doc_url", cfg == null ? null : cfg.getTencentDocUrl());
        out.put("apply_deadline", cfg == null ? null : cfg.getApplyDeadline());
        out.put("allow_register", cfg == null || cfg.isAllowRegister());
        out.put("submitted", submitted);
        out.put("pending", pending);
        out.put("status", status);
        return out;
    }

    /** 玩家正式提交参赛申请（设备绑定后调用）。已存在报名则不再重复创建。 */
    @Transactional
    public Enrollment submitRegister(String tournamentId, String pteid, String displayName,
                                     String permittedFp) {
        java.util.Optional<Enrollment> existing = enrollmentRepository.findFirstByPteidOrderByCreatedAtDesc(pteid);
        if (existing.isPresent()
                && tournamentId.equals(existing.get().getTournamentId())
                && existing.get().getStatus() != Enrollment.Status.REJECTED) {
            existing.get().setPermittedDeviceFingerprint(permittedFp);
            return enrollmentRepository.save(existing.get());
        }
        return enroll(tournamentId, pteid, displayName, permittedFp, pteid);
    }

    /** 宏观风控统计：按嫌疑类型与按 IP 聚集。 */
    public Map<String, Object> overview() {
        List<SuspicionFlag> flags = flagRepository.findAllByOrderByCreatedAtDesc();
        java.util.Map<String, Long> byKind = new java.util.LinkedHashMap<>();
        for (SuspicionFlag f : flags) {
            byKind.merge(f.getKind().name(), 1L, Long::sum);
        }
        return Map.of("total_flags", flags.size(), "by_kind", byKind);
    }

    /**
     * 宏观风控按 IP 聚类：聚合登录事件，找出同一来源 IP 下出现过的不同账号。
     * 多账号共用一个 IP 可能指向网吧/局域网（合规场景）；在赛事语境下若数量较高，
     * 常提示"枪手/代练网络"（一个 IP 后多个账号交替作答）。返回按账号数降序。
     */
    public List<Map<String, Object>> ipClusters(int minAccounts) {
        int min = Math.max(2, minAccounts);
        java.util.Map<String, java.util.Set<String>> byIp = new java.util.LinkedHashMap<>();
        for (LoginEvent e : loginEventRepository.findAll()) {
            if (e.getIp() == null || e.getIp().isBlank() || e.getPteid() == null) continue;
            byIp.computeIfAbsent(e.getIp(), k -> new java.util.LinkedHashSet<>()).add(e.getPteid());
        }
        return byIp.entrySet().stream()
                .filter(entry -> entry.getValue().size() >= min)
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .map(entry -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    List<String> pteids = new java.util.ArrayList<>(entry.getValue());
                    m.put("ip", entry.getKey());
                    m.put("account_count", entry.getValue().size());
                    m.put("pteids", pteids);
                    return m;
                })
                .toList();
    }

    // ---------------- 对局会话（session token 隔离） ----------------

    /** 当前账号的全部对局会话（新→旧）。 */
    public List<MatchSession> matchList() {
        return matchRepository.findAllByOrderByStartedAtDesc();
    }

    /**
     * 为已审批且许可设备可用的选手发起一场比赛，生成入场 token。
     * 仅当存在 APPROVED 报名时才允许；旧的有效会话先结束，保证单选手单场次。
     */
    @Transactional
    public MatchSession startMatch(String tournamentId, String pteid, long durationMinutes, String operator) {
        Enrollment en = enrollmentRepository.findFirstByPteidOrderByCreatedAtDesc(pteid).orElse(null);
        if (en == null || en.getStatus() != Enrollment.Status.APPROVED) {
            throw new IllegalArgumentException("选手未通过参赛审批，无法发起对局");
        }
        if (en.getPermittedDeviceFingerprint() == null || en.getPermittedDeviceFingerprint().isBlank()) {
            throw new IllegalArgumentException("尚未绑定许可设备，无法发起对局");
        }
        // 结束该选手既有有效会话
        matchRepository.findFirstByPteidAndStatusOrderByStartedAtDesc(pteid, MatchSession.Status.ACTIVE)
                .ifPresent(m -> {
                    m.setStatus(MatchSession.Status.ENDED);
                    m.setEndedAt(Instant.now());
                    matchRepository.save(m);
                });
        Instant now = Instant.now();
        MatchSession session = MatchSession.builder()
                .matchId(randomToken())
                .tournamentId(tournamentId)
                .pteid(pteid)
                .deviceFingerprint(en.getPermittedDeviceFingerprint())
                .status(MatchSession.Status.ACTIVE)
                .startedAt(now)
                .expiresAt(now.plusSeconds(Math.max(1, durationMinutes) * 60L))
                .operator(operator)
                .build();
        return matchRepository.save(session);
    }

    /** 结束一场对局。 */
    public void endMatch(String matchId, String operator) {
        matchRepository.findById(matchId).ifPresent(m -> {
            m.setStatus(MatchSession.Status.ENDED);
            m.setEndedAt(Instant.now());
            m.setOperator(operator);
            matchRepository.save(m);
        });
    }

    /** 玩家当前场次（仅有效会话）。 */
    public MatchSession currentMatch(String pteid) {
        return matchRepository.findFirstByPteidAndStatusOrderByStartedAtDesc(pteid, MatchSession.Status.ACTIVE)
                .orElse(null);
    }

    /**
     * 入场校验：令牌存在且归属该玩家、会话有效未过期、且当前活动设备与场次许可设备一致。
     * 返回 allowed 与原因；额外给玩家/客户端判定。
     */
    @Transactional
    public Map<String, Object> validateMatch(String pteid, String matchToken, String currentDeviceFp) {
        if (matchToken == null || matchToken.isBlank()) {
            return Map.of("allowed", false, "reason", "missing_token");
        }
        var session = matchRepository.findByMatchIdAndPteid(matchToken, pteid).orElse(null);
        if (session == null) {
            return Map.of("allowed", false, "reason", "invalid_token");
        }
        if (session.getStatus() != MatchSession.Status.ACTIVE) {
            return Map.of("allowed", false, "reason", "session_ended");
        }
        if (session.getExpiresAt() != null && session.getExpiresAt().isBefore(Instant.now())) {
            return Map.of("allowed", false, "reason", "session_expired");
        }
        boolean sameDevice = currentDeviceFp != null && session.getDeviceFingerprint() != null
                && session.getDeviceFingerprint().equals(currentDeviceFp);
        if (!sameDevice) {
            return Map.of("allowed", false, "reason", "device_mismatch",
                    "message", "当前设备与报名许可设备不符，禁止入场");
        }
        session.setLastSeenAt(Instant.now());
        matchRepository.save(session);
        return Map.of("allowed", true, "reason", "ok",
                "match_id", session.getMatchId(), "expires_at", session.getExpiresAt());
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---------------- 赛事进程（可自由编辑的阶段列表） ----------------

    public List<TournamentStage> stages(String tournamentId) {
        return stageRepository.findByTournamentIdOrderByOrderNoAsc(tournamentId);
    }

    /** 新增阶段；默认排到末尾。 */
    @Transactional
    public TournamentStage addStage(String tournamentId, String title, String kind,
                                    String status, Instant start, Instant end, String note) {
        List<TournamentStage> list = stages(tournamentId);
        int last = list.stream().mapToInt(TournamentStage::getOrderNo).max().orElse(0);
        TournamentStage s = TournamentStage.builder()
                .tournamentId(tournamentId)
                .orderNo(last + 10)
                .title(title)
                .kind(kind == null ? TournamentStage.Kind.CUSTOM : TournamentStage.Kind.valueOf(kind))
                .status(status == null ? TournamentStage.Status.PENDING : TournamentStage.Status.valueOf(status))
                .startTime(start)
                .endTime(end)
                .note(note)
                .build();
        return stageRepository.save(s);
    }

    /** 更新阶段（标题/类别/时间/状态/结果/备注；某字段显式提供才覆盖）。 */
    @Transactional
    public void updateStage(String stageId, String title, String kind, String status,
                            Instant start, Instant end, String resultNote, String note) {
        stageRepository.findById(stageId).ifPresent(s -> {
            if (title != null) s.setTitle(title);
            if (kind != null) s.setKind(TournamentStage.Kind.valueOf(kind));
            if (status != null) s.setStatus(TournamentStage.Status.valueOf(status));
            if (start != null) s.setStartTime(start);
            if (end != null) s.setEndTime(end);
            if (resultNote != null) s.setResultNote(resultNote);
            if (note != null) s.setNote(note);
            stageRepository.save(s);
        });
    }

    /** 重排：把阶段移到指定顺序（基于当前列表重新编号）。 */
    @Transactional
    public void reorderStage(String tournamentId, int from, int to) {
        List<TournamentStage> list = stages(tournamentId);
        if (from < 0 || from >= list.size() || to < 0 || to >= list.size()) return;
        TournamentStage moved = list.remove(from);
        list.add(to, moved);
        if (moved != null) {
            for (int i = 0; i < list.size(); i++) {
                list.get(i).setOrderNo((i + 1) * 10);
                stageRepository.save(list.get(i));
            }
        }
    }

    @Transactional
    public void deleteStage(String stageId) {
        stageRepository.deleteById(stageId);
    }

    // ---------------- 赛事公告 ----------------

    public List<TournamentNotice> notices(String tournamentId) {
        return noticeRepository.findByTournamentIdOrderByCreatedAtDesc(tournamentId);
    }

    @Transactional
    public TournamentNotice publishNotice(String tournamentId, String title, String content,
                                          boolean pinned, String operator) {
        TournamentNotice n = TournamentNotice.builder()
                .tournamentId(tournamentId)
                .title(title)
                .content(content)
                .pinned(pinned)
                .operator(operator)
                .build();
        return noticeRepository.save(n);
    }

    @Transactional
    public void deleteNotice(String noticeId) {
        noticeRepository.deleteById(noticeId);
    }

    private static String canonical(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}