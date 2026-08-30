package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Enrollment;
import com.potatotv.pacc.domain.MatchSession;
import com.potatotv.pacc.domain.SuspicionFlag;
import com.potatotv.pacc.domain.TournamentNotice;
import com.potatotv.pacc.domain.TournamentStage;
import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.CompetitionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 赛事风控与参赛门禁。
 * <ul>
 *   <li>/api/admin/competition/**：管理端（X-Admin-Key 保护）——嫌疑列表/裁判复核/门禁报名与审批/宏观概览；</li>
 *   <li>/api/player/competition/enrollment：玩家自查（JWT 保护）。</li>
 * </ul>
 */
@RestController
@RequiredArgsConstructor
public class CompetitionController {

    private final CompetitionService competitionService;
    private final AccountService accountService;

    // ---- 管理端 ----

    @GetMapping("/api/admin/competition/overview")
    public Map<String, Object> overview() {
        return competitionService.overview();
    }

    /** 宏观风控按 IP 聚类：同 IP 下多账号（疑似枪手/代练网络）。 */
    @GetMapping("/api/admin/competition/ip-clusters")
    public List<Map<String, Object>> ipClusters(@RequestParam(defaultValue = "3") int minAccounts) {
        return competitionService.ipClusters(minAccounts);
    }

    @GetMapping("/api/admin/competition/flags")
    public List<SuspicionFlag> flags(@RequestParam(defaultValue = "") String keyword) {
        return competitionService.flagList(keyword);
    }

    @PostMapping("/api/admin/competition/flags/{flagId}/review")
    public ResponseEntity<?> review(@PathVariable String flagId, @RequestBody Map<String, String> body) {
        competitionService.review(flagId, body.getOrDefault("decision", "clear"),
                body.get("reviewer"), body.get("comment"));
        return ResponseEntity.ok(Map.of("flag_id", flagId, "ok", true));
    }

    @GetMapping("/api/admin/competition/enrollments")
    public List<Enrollment> enrollments() {
        return competitionService.enrollmentList();
    }

    @GetMapping("/api/admin/competition/enrollments/stats")
    public Map<String, Object> enrollmentStats() {
        return competitionService.enrollmentStats();
    }

    /** 管理员为选手报名并绑定许可设备；若缺许可设备则按账号当前活动设备补录。 */
    @PostMapping("/api/admin/competition/enrollments")
    public ResponseEntity<?> enroll(@RequestBody Map<String, String> body) {
        String pteid = body.get("pteid");
        if (pteid == null || pteid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 pteid"));
        }
        String permitted = body.getOrDefault("permitted_device_fingerprint",
                accountService.listDevices(pteid).stream()
                        .filter(d -> d.isActive())
                        .map(d -> d.getDeviceFingerprint())
                        .findFirst()
                        .orElse(""));
        Enrollment e = competitionService.enroll(body.get("tournament_id"), pteid,
                body.getOrDefault("display_name", pteid), permitted, body.get("operator"));
        return ResponseEntity.ok(e);
    }

    @PostMapping("/api/admin/competition/enrollments/{enrollmentId}/approve")
    public ResponseEntity<?> approve(@PathVariable String enrollmentId, @RequestBody Map<String, String> body) {
        boolean ok = Boolean.parseBoolean(body.getOrDefault("approve", "true"));
        competitionService.approve(enrollmentId, ok, body.get("operator"), body.get("note"));
        return ResponseEntity.ok(Map.of("enrollment_id", enrollmentId, "ok", true));
    }

    /** 为已报名选手分配/修改队伍标签。 */
    @PostMapping("/api/admin/competition/enrollments/{enrollmentId}/team")
    public ResponseEntity<?> setTeam(@PathVariable String enrollmentId, @RequestBody Map<String, String> body) {
        Enrollment e = competitionService.setTeam(enrollmentId, body.get("team_name"), body.get("team_color"));
        if (e == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(e);
    }

    /** 批量分配队伍：将多个报名分到同一队伍。 */
    @PostMapping("/api/admin/competition/enrollments/team/batch")
    public ResponseEntity<?> setTeamBatch(@RequestBody Map<String, Object> body) {
        Object rawIds = body.get("enrollment_ids");
        if (!(rawIds instanceof List)) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 enrollment_ids 数组"));
        }
        List<String> ids = new java.util.ArrayList<>();
        for (Object o : (List<?>) rawIds) {
            if (o != null) ids.add(o.toString());
        }
        if (ids.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "未选择报名"));
        String teamName = body.get("team_name") == null ? null : body.get("team_name").toString();
        String teamColor = body.get("team_color") == null ? null : body.get("team_color").toString();
        if (teamName == null || teamName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 team_name"));
        }
        return ResponseEntity.ok(Map.of("updated", competitionService.setTeamBatch(ids, teamName, teamColor)));
    }

    // ---- 对局会话 ----

    @GetMapping("/api/admin/competition/matches")
    public List<MatchSession> matches() {
        return competitionService.matchList();
    }

    /** 为已审批选手发起一场对局，生成入场 token。 */
    @PostMapping("/api/admin/competition/matches")
    public ResponseEntity<?> startMatch(@RequestBody Map<String, String> body) {
        try {
            long minutes = Long.parseLong(body.getOrDefault("duration_minutes", "240"));
            MatchSession s = competitionService.startMatch(body.get("tournament_id"),
                    body.get("pteid"), minutes, body.get("operator"));
            return ResponseEntity.ok(s);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/api/admin/competition/matches/{matchId}/end")
    public ResponseEntity<?> endMatch(@PathVariable String matchId, @RequestBody(required = false) Map<String, String> body) {
        competitionService.endMatch(matchId, body == null ? null : body.get("operator"));
        return ResponseEntity.ok(Map.of("match_id", matchId, "ok", true));
    }

    // ---- 赛事进程（可自由编辑的阶段） ----

    @GetMapping("/api/admin/competition/stages")
    public List<TournamentStage> stages(@RequestParam("tournament_id") String tournamentId) {
        return competitionService.stages(tournamentId);
    }

    @PostMapping("/api/admin/competition/stages")
    public ResponseEntity<?> addStage(@RequestBody Map<String, String> body) {
        if (body.get("tournament_id") == null || body.get("title") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 tournament_id 与 title"));
        }
        return ResponseEntity.ok(competitionService.addStage(body.get("tournament_id"), body.get("title"),
                body.get("kind"), body.get("status"), parseInstant(body.get("start_time")),
                parseInstant(body.get("end_time")), body.get("note")));
    }

    @PutMapping("/api/admin/competition/stages/{stageId}")
    public ResponseEntity<?> updateStage(@PathVariable String stageId, @RequestBody Map<String, String> body) {
        competitionService.updateStage(stageId, body.get("title"), body.get("kind"), body.get("status"),
                parseInstant(body.get("start_time")), parseInstant(body.get("end_time")),
                body.get("result_note"), body.get("note"));
        return ResponseEntity.ok(Map.of("stage_id", stageId, "ok", true));
    }

    @PatchMapping("/api/admin/competition/stages/reorder")
    public ResponseEntity<?> reorderStage(@RequestBody Map<String, String> body) {
        try {
            competitionService.reorderStage(body.get("tournament_id"),
                    Integer.parseInt(body.getOrDefault("from", "0")),
                    Integer.parseInt(body.getOrDefault("to", "0")));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "from/to 需为数字"));
        }
    }

    @DeleteMapping("/api/admin/competition/stages/{stageId}")
    public ResponseEntity<?> deleteStage(@PathVariable String stageId) {
        competitionService.deleteStage(stageId);
        return ResponseEntity.ok(Map.of("stage_id", stageId, "ok", true));
    }

    // ---- 赛事公告 ----

    @GetMapping("/api/admin/competition/notices")
    public List<TournamentNotice> notices(@RequestParam("tournament_id") String tournamentId) {
        return competitionService.notices(tournamentId);
    }

    @PostMapping("/api/admin/competition/notices")
    public ResponseEntity<?> publishNotice(@RequestBody Map<String, String> body) {
        if (body.get("tournament_id") == null || body.get("title") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 tournament_id 与 title"));
        }
        return ResponseEntity.ok(competitionService.publishNotice(body.get("tournament_id"),
                body.get("title"), body.getOrDefault("content", ""),
                Boolean.parseBoolean(body.getOrDefault("pinned", "false")), body.get("operator")));
    }

    @DeleteMapping("/api/admin/competition/notices/{noticeId}")
    public ResponseEntity<?> deleteNotice(@PathVariable String noticeId) {
        competitionService.deleteNotice(noticeId);
        return ResponseEntity.ok(Map.of("notice_id", noticeId, "ok", true));
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Instant.parse(s); } catch (RuntimeException e) { return null; }
    }

    // ---- 赛事报名配置（腾讯文档收集表 + 截止时间） ----

    @GetMapping("/api/admin/competition/config")
    public ResponseEntity<?> config(@RequestParam("tournament_id") String tournamentId) {
        return ResponseEntity.ok(competitionService.config(tournamentId));
    }

    @PutMapping("/api/admin/competition/config")
    public ResponseEntity<?> updateConfig(@RequestBody Map<String, String> body) {
        if (body.get("tournament_id") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 tournament_id"));
        }
        String allow = body.get("allow_register");
        return ResponseEntity.ok(competitionService.updateConfig(body.get("tournament_id"),
                body.get("title"), body.get("tencent_doc_url"), parseInstant(body.get("apply_deadline")),
                allow == null ? null : Boolean.parseBoolean(allow), body.get("note")));
    }

    /** 玩家：报名页信息（配置 + 我的状态 + 人数）。 */
    @GetMapping("/api/player/competition/register")
    public ResponseEntity<?> playerRegisterInfo(@RequestParam("tournament_id") String tournamentId, HttpServletRequest req) {
        String pteid = req.getAttribute("pteid").toString();
        return ResponseEntity.ok(competitionService.registerInfo(tournamentId, pteid, currentFp(pteid)));
    }

    /** 玩家：提交参赛申请（携带当前绑定设备指纹）。 */
    @PostMapping("/api/player/competition/register")
    public ResponseEntity<?> playerSubmitRegister(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String pteid = req.getAttribute("pteid").toString();
        String tournamentId = body.get("tournament_id");
        if (tournamentId == null || tournamentId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 tournament_id"));
        }
        String fp = body.getOrDefault("device_fingerprint", currentFp(pteid));
        String displayName = body.getOrDefault("display_name", pteid);
        return ResponseEntity.ok(competitionService.submitRegister(tournamentId, pteid, displayName, fp));
    }

    // ---- 玩家自查 ----

    /** 玩家查看自己的赛事状态：是否报名、当前设备是否在许可名单、能否入场。 */
    @GetMapping("/api/player/competition/enrollment")
    public Map<String, Object> myEnrollment(HttpServletRequest req) {
        String pteid = req.getAttribute("pteid").toString();
        String currentFp = currentFp(pteid);
        return competitionService.playerEnrollmentStatus(pteid, currentFp);
    }

    /** 玩家当前进行中的对局会话。 */
    @GetMapping("/api/player/competition/matches/current")
    public ResponseEntity<?> myCurrentMatch(HttpServletRequest req) {
        MatchSession s = competitionService.currentMatch(req.getAttribute("pteid").toString());
        return s == null
                ? ResponseEntity.ok(Map.of("in_match", false))
                : ResponseEntity.ok(Map.of("in_match", true, "match", s));
    }

    /** 入场校验：提交对局 token，校验会话与当前设备是否许可。 */
    @PostMapping("/api/player/competition/matches/validate")
    public ResponseEntity<Map<String, Object>> validateMatch(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String pteid = req.getAttribute("pteid").toString();
        return ResponseEntity.ok(competitionService.validateMatch(pteid,
                body.get("match_token"), currentFp(pteid)));
    }

    /** 玩家赛程（只读）：返回其已报名赛事的所有阶段，未报名/未获批则空。 */
    @GetMapping("/api/player/competition/stages")
    public ResponseEntity<List<TournamentStage>> playerStages(HttpServletRequest req) {
        String tid = playerTournamentId(req.getAttribute("pteid").toString());
        return ResponseEntity.ok(tid == null ? List.of() : competitionService.stages(tid));
    }

    /** 玩家公告（只读）：其已报名赛事的公告。 */
    @GetMapping("/api/player/competition/notices")
    public ResponseEntity<List<TournamentNotice>> playerNotices(HttpServletRequest req) {
        String tid = playerTournamentId(req.getAttribute("pteid").toString());
        return ResponseEntity.ok(tid == null ? List.of() : competitionService.notices(tid));
    }

    /** 由玩家当前报名推断其所属赛事；仅 APPROVED 报名返回赛事 ID。 */
    private String playerTournamentId(String pteid) {
        return competitionService.enrollmentList().stream()
                .filter(e -> pteid.equals(e.getPteid())
                        && e.getStatus() == Enrollment.Status.APPROVED
                        && e.getTournamentId() != null && !e.getTournamentId().isBlank())
                .findFirst()
                .map(Enrollment::getTournamentId)
                .orElse(null);
    }

    private String currentFp(String pteid) {
        return accountService.listDevices(pteid).stream()
                .filter(d -> d.isActive())
                .map(d -> d.getDeviceFingerprint())
                .findFirst()
                .orElse(null);
    }
}
