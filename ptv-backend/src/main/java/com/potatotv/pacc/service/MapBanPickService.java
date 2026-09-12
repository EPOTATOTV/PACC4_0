package com.potatotv.pacc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.Enrollment;
import com.potatotv.pacc.domain.MapBanPickAction;
import com.potatotv.pacc.domain.MapBanPickSession;
import com.potatotv.pacc.domain.MapEntry;
import com.potatotv.pacc.domain.MatchSession;
import com.potatotv.pacc.repository.EnrollmentRepository;
import com.potatotv.pacc.repository.MapBanPickActionRepository;
import com.potatotv.pacc.repository.MapBanPickSessionRepository;
import com.potatotv.pacc.repository.MapEntryRepository;
import com.potatotv.pacc.repository.MapPoolRepository;
import com.potatotv.pacc.repository.MatchSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地图 BP 状态机与回合推进。
 * <ul>
 *   <li>回合序列由赛制生成：每方 2 次 Ban，随后交替 Pick 直至赛制地图数（BO1/3/5）。</li>
 *   <li>{@code turnIndex} 是唯一推进依据；每位合法推进步都生成一条 {@code t_map_bp_action} 审计记录。</li>
 *   <li>严格权限链：仅某侧已 APPROVED 选手可在其回合（+许可设备 + 时间窗 + 操作间隔）内 Ban/Pick。</li>
 *   <li>每个入口惰性检查超时并进行系统自动操作（随机选合法图，记 timeout=true）。</li>
 *   <li>省粒度并发通过按 bpSessionId 的进程内锁串行化状态转移。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null")
public class MapBanPickService {

    private static final int BANS_PER_SIDE = 2;
    private static final Duration MIN_OP_INTERVAL = Duration.ofSeconds(1);

    private final MapBanPickSessionRepository sessionRepository;
    private final MapBanPickActionRepository actionRepository;
    private final MapEntryRepository entryRepository;
    private final MapPoolRepository poolRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final MatchSessionRepository matchRepository;
    private final MapBpEventBus eventBus;
    private final ObjectMapper mapper;

    /** 每 BP 会话一把进程内锁，串行化其状态转移。 */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    // ---------------- 回合计划 ----------------

    /** 单个回合步：由谁、做什么、属于第几轮。 */
    record Step(MapBanPickSession.Side side, MapBanPickAction.ActionType action, int round) {
        String token() {
            return side.name() + "_" + action.name();
        }
    }

    /** 由赛制生成回合序列：每方 2 次 Ban，再交替 Pick 至赛制地图数。 */
    static List<Step> plan(MapBanPickSession.Format format) {
        List<Step> steps = new ArrayList<>();
        for (int b = 0; b < BANS_PER_SIDE; b++) {
            steps.add(new Step(MapBanPickSession.Side.BLUE, MapBanPickAction.ActionType.BAN, 1));
            steps.add(new Step(MapBanPickSession.Side.RED, MapBanPickAction.ActionType.BAN, 1));
        }
        int n = format.maps;
        for (int p = 0; p < n; p++) {
            MapBanPickSession.Side side = (p % 2 == 0)
                    ? MapBanPickSession.Side.BLUE : MapBanPickSession.Side.RED;
            steps.add(new Step(side, MapBanPickAction.ActionType.PICK, p + 1));
        }
        return steps;
    }

    private static List<Step> planOf(MapBanPickSession s) {
        return plan(s.getFormat());
    }

    /** 当前回合步；流程结束或未开始返回空。 */
    private static Optional<Step> currentStep(MapBanPickSession s) {
        List<Step> plan = planOf(s);
        if (s.getStatus() != MapBanPickSession.Status.ACTIVE || s.getTurnIndex() >= plan.size()) {
            return Optional.empty();
        }
        return Optional.of(plan.get(s.getTurnIndex()));
    }

    // ---------------- 管理端生命周期 ----------------

    @Transactional
    public MapBanPickSession create(MapBanPickSession.Format format, String poolId, String tournamentId,
                                    String matchId, String stageId, String blueEnrollmentId, String redEnrollmentId,
                                    String blueTeamName, String redTeamName, int turnTimeoutSeconds,
                                    String referee, String createdBy) {
        if (poolRepository.findById(poolId).orElse(null) == null) {
            throw new IllegalArgumentException("地图池不存在");
        }
        int timeout = turnTimeoutSeconds <= 0 ? 60 : turnTimeoutSeconds;
        MapBanPickSession s = MapBanPickSession.builder()
                .format(format)
                .poolId(poolId)
                .tournamentId(tournamentId)
                .matchId(matchId)
                .stageId(stageId)
                .blueEnrollmentId(blueEnrollmentId)
                .redEnrollmentId(redEnrollmentId)
                .blueTeamName(blueTeamName)
                .redTeamName(redTeamName)
                .totalRounds(format.maps)
                .turnTimeoutSeconds(timeout)
                .referee(referee)
                .createdBy(createdBy)
                .selectedMaps("[]")
                .bannedMaps("[]")
                .status(MapBanPickSession.Status.PENDING)
                .build();
        MapBanPickSession saved = sessionRepository.save(s);
        eventBus.publish(saved.getBpSessionId(), "bp_status",
                Map.of("status", "PENDING", "bp_session_id", saved.getBpSessionId()));
        return saved;
    }

    /** 开始：置 ACTIVE 并派生首个回合与截止时间。 */
    @Transactional
    public MapBanPickSession start(String bpId, String operator) {
        return withLock(bpId, () -> {
            MapBanPickSession s = require(bpId);
            if (s.getStatus() != MapBanPickSession.Status.PENDING) {
                throw new IllegalStateException("仅 PENDING 可开始");
            }
            s.setStatus(MapBanPickSession.Status.ACTIVE);
            s.setTurnIndex(0);
            s.setStartTime(Instant.now());
            applyTurnState(s);
            sessionRepository.save(s);
            eventBus.publish(bpId, "bp_status", Map.of("status", "ACTIVE"));
            pushState(s);
            return s;
        });
    }

    @Transactional
    public MapBanPickSession pause(String bpId, String operator) {
        return withLock(bpId, () -> {
            MapBanPickSession s = require(bpId);
            if (s.getStatus() != MapBanPickSession.Status.ACTIVE) {
                throw new IllegalStateException("仅 ACTIVE 可暂停");
            }
            s.setStatus(MapBanPickSession.Status.PAUSED);
            s.setCurrentTurnDeadline(null);
            sessionRepository.save(s);
            eventBus.publish(bpId, "bp_status", Map.of("status", "PAUSED"));
            pushState(s);
            return s;
        });
    }

    @Transactional
    public MapBanPickSession resume(String bpId, String operator) {
        return withLock(bpId, () -> {
            MapBanPickSession s = require(bpId);
            if (s.getStatus() != MapBanPickSession.Status.PAUSED) {
                throw new IllegalStateException("仅 PAUSED 可恢复");
            }
            s.setStatus(MapBanPickSession.Status.ACTIVE);
            applyTurnState(s);
            sessionRepository.save(s);
            eventBus.publish(bpId, "bp_status", Map.of("status", "ACTIVE"));
            pushState(s);
            return s;
        });
    }

    /** 重置为待开始，清空全部操作。 */
    @Transactional
    public MapBanPickSession reset(String bpId, String operator) {
        return withLock(bpId, () -> {
            MapBanPickSession s = require(bpId);
            actionRepository.deleteAll(actionRepository.findByBpSessionIdOrderByRoundNoAscCreatedAtAsc(bpId));
            s.setStatus(MapBanPickSession.Status.PENDING);
            s.setTurnIndex(0);
            s.setCurrentRound(1);
            s.setCurrentTurn(null);
            s.setStartTime(null);
            s.setEndTime(null);
            s.setCurrentTurnDeadline(null);
            s.setSelectedMaps("[]");
            s.setBannedMaps("[]");
            s.setCancelReason(null);
            sessionRepository.save(s);
            eventBus.publish(bpId, "bp_status", Map.of("status", "PENDING"));
            pushState(s);
            return s;
        });
    }

    @Transactional
    public MapBanPickSession cancel(String bpId, String reason, String operator) {
        return withLock(bpId, () -> {
            MapBanPickSession s = require(bpId);
            if (s.getStatus() == MapBanPickSession.Status.COMPLETED) {
                throw new IllegalStateException("已完成的 BP 不可取消");
            }
            s.setStatus(MapBanPickSession.Status.CANCELLED);
            s.setCancelReason(reason);
            s.setEndTime(Instant.now());
            silentReject(s);
            sessionRepository.save(s);
            eventBus.publish(bpId, "bp_status", Map.of("status", "CANCELLED", "reason", reason));
            pushState(s);
            return s;
        });
    }

    /** 手动完成：把当前已 Pick 地图落定为最终选图并写入对局。 */
    @Transactional
    public MapBanPickSession complete(String bpId, String operator) {
        return withLock(bpId, () -> {
            MapBanPickSession s = require(bpId);
            if (s.getStatus() != MapBanPickSession.Status.ACTIVE
                    && s.getStatus() != MapBanPickSession.Status.PAUSED) {
                throw new IllegalStateException("仅进行中可完成");
            }
            finishSession(s);
            sessionRepository.save(s);
            persistToMatch(s);
            eventBus.publish(bpId, "bp_completed", Map.of("selected_maps", selectedList(s)));
            pushState(s);
            return s;
        });
    }

    // ---------------- 核心：Ban / Pick ----------------

    /** 玩家回合操作。 */
    @Transactional
    public Map<String, Object> playerAction(String bpId, String actionType, String mapId,
                                            String pteid, String deviceFp, String ip) {
        return withLock(bpId, () -> {
            MapBanPickSession s = require(bpId);
            lazyTimeout(s);
            if (s.getStatus() != MapBanPickSession.Status.ACTIVE) {
                throw new IllegalStateException("BP 未在进行中");
            }
            Step step = currentStep(s).orElseThrow(() -> new IllegalStateException("BP 已结束"));
            MapBanPickAction.ActionType want = MapBanPickAction.ActionType.valueOf(actionType);
            if (step.action() != want) {
                throw new IllegalArgumentException("当前轮次需要执行 " + step.action());
            }
            Enrollment en = approvedEnrollmentOf(pteid);
            if (!isSide(en, s, step.side())) {
                throw new SecurityException("非当前回合方，禁止操作");
            }
            if (en.getPermittedDeviceFingerprint() == null || deviceFp == null
                    || !en.getPermittedDeviceFingerprint().equals(deviceFp)) {
                throw new SecurityException("设备与许可设备不符");
            }
            // 操作间隔 ≥1s，防止脚本连点
            Optional<MapBanPickAction> last =
                    Optional.ofNullable(actionRepository
                            .findByBpSessionIdOrderByRoundNoAscCreatedAtAsc(bpId)).stream()
                            .flatMap(List::stream).sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                            .findFirst();
            if (last.isPresent() && last.get().getCreatedAt().plus(MIN_OP_INTERVAL).isAfter(Instant.now())) {
                throw new IllegalStateException("操作过于频繁，请稍候");
            }
            MapEntry map = mapForAction(s, mapId, step);
            // 响应耗时 = 自本回合起始（截止时间前推超时时长）至本次落子的毫秒数
            long respMs = 0;
            if (s.getCurrentTurnDeadline() != null && s.getTurnTimeoutSeconds() > 0) {
                long turnStart = s.getCurrentTurnDeadline().toEpochMilli() - s.getTurnTimeoutSeconds() * 1000L;
                respMs = Math.max(0, System.currentTimeMillis() - turnStart);
            }
            Instant actionTime = Instant.now();
            applyAction(s, step, map, pteid, deviceFp, ip, false, respMs, actionTime);
            return stateDto(s);
        });
    }

    /** 裁判强制操作：mapId 为空则随机选一张当前侧/当前动作的合法图。 */
    @Transactional
    public Map<String, Object> forceAction(String bpId, String actionType, String mapId, String operator) {
        return withLock(bpId, () -> {
            MapBanPickSession s = require(bpId);
            lazyTimeout(s);
            if (s.getStatus() != MapBanPickSession.Status.ACTIVE) {
                throw new IllegalStateException("BP 未在进行中");
            }
            Step step = currentStep(s).orElseThrow(() -> new IllegalStateException("BP 已结束"));
            MapBanPickAction.ActionType want = MapBanPickAction.ActionType.valueOf(actionType);
            if (step.action() != want) {
                throw new IllegalArgumentException("当前轮次需要执行 " + step.action());
            }
            MapEntry map = mapId == null || mapId.isBlank()
                    ? randomValidMap(s, step) : mapForAction(s, mapId, step);
            applyAction(s, step, map, "REFEREE:" + safe(operator), null, null, false, null, Instant.now());
            return stateDto(s);
        });
    }

    // ---------------- 超时自动操作 ----------------

    /** 惰性超时检查：当前 ACTIVE 且已到截止时间则系统随机落子（记 timeout=true）。 */
    private void lazyTimeout(MapBanPickSession s) {
        if (s.getStatus() != MapBanPickSession.Status.ACTIVE) return;
        Instant deadline = s.getCurrentTurnDeadline();
        if (deadline == null || !deadline.isBefore(Instant.now())) return;
        Step step = currentStep(s).orElse(null);
        if (step == null) return;
        MapEntry map = randomValidMap(s, step);
        applyAction(s, step, map, "SYSTEM", null, null, true, null, deadline);
        log.info("BP 回合超时自动操作 bp={} turn={}", s.getBpSessionId(), step.token());
    }

    /** 提示：暂停/取消时把系统自动跳过的回合作为占位落子非法地图无法进行，这里仅在超时推进处调用。 */
    private void silentReject(MapBanPickSession s) {
        // 取消/暂停不做自动落子，仅清截止时间避免误判
        s.setCurrentTurnDeadline(null);
    }

    // ---------------- 状态查询 ----------------

    public MapBanPickSession get(String bpId) {
        MapBanPickSession s = sessionRepository.findById(bpId).orElse(null);
        if (s != null && s.getStatus() == MapBanPickSession.Status.ACTIVE) {
            // 读路径同样惰性处理超时，确保进行中会话不因无人触发而卡回合
            return withLock(bpId, () -> {
                MapBanPickSession active = require(bpId);
                lazyTimeout(active);
                if (active.getStatus() == MapBanPickSession.Status.ACTIVE) sessionRepository.save(active);
                return active;
            });
        }
        return s;
    }

    public Map<String, Object> state(String bpId) {
        return withLock(bpId, () -> {
            MapBanPickSession s = require(bpId);
            lazyTimeout(s);
            if (s.getStatus() == MapBanPickSession.Status.ACTIVE) sessionRepository.save(s);
            return stateDto(s);
        });
    }

    public List<MapBanPickSession> all() {
        return sessionRepository.findAllByOrderByCreatedAtDesc();
    }

    public List<MapBanPickAction> actions(String bpId) {
        return actionRepository.findByBpSessionIdOrderByRoundNoAscCreatedAtAsc(bpId);
    }

    /** 玩家侧：本人所属且进行中的 BP 会话。 */
    public List<MapBanPickSession> activeForEnrollment(String pteid) {
        Enrollment en = enrollmentRepository.findFirstByPteidOrderByCreatedAtDesc(pteid).orElse(null);
        if (en == null) return List.of();
        return sessionRepository.findByStatusOrderByCreatedAtDesc(MapBanPickSession.Status.ACTIVE).stream()
                .filter(s -> en.getEnrollmentId().equals(s.getBlueEnrollmentId())
                        || en.getEnrollmentId().equals(s.getRedEnrollmentId()))
                .toList();
    }

    // ---------------- 内部实现 ----------------

    private void applyTurnState(MapBanPickSession s) {
        Step step = currentStep(s).orElse(null);
        if (step == null) return;
        s.setCurrentTurn(step.token());
        s.setCurrentRound(step.round());
        s.setCurrentTurnDeadline(Instant.now().plusSeconds(s.getTurnTimeoutSeconds()));
    }

    /** 核心落子：记录审计、更新统计与列表、推进回合/收尾，并广播。 */
    private void applyAction(MapBanPickSession s, Step step, MapEntry map,
                             String operatorPteid, String deviceFp, String ip,
                             boolean timeout, Long responseTimeMs, Instant actionTime) {
        MapBanPickAction action = MapBanPickAction.builder()
                .bpSessionId(s.getBpSessionId())
                .roundNo(step.round())
                .team(step.side())
                .actionType(step.action())
                .mapId(map.getMapId())
                .mapName(map.getName())
                .operatorPteid(operatorPteid)
                .operatorDeviceFp(deviceFp)
                .clientIp(ip)
                .responseTimeMs(responseTimeMs)
                .timeout(timeout)
                .createdAt(actionTime)
                .build();
        actionRepository.save(action);

        if (step.action() == MapBanPickAction.ActionType.BAN) {
            entryRepository.incrementBanCount(map.getMapId());
            bannedMapsAdd(s, map, step.side(), step.round());
        } else {
            entryRepository.incrementPickCount(map.getMapId());
            selectedListAdd(s, map, step.side(), step.round());
        }

        s.setTurnIndex(s.getTurnIndex() + 1);
        if (s.getTurnIndex() >= planOf(s).size()) {
            finishSession(s);
        } else {
            applyTurnState(s);
        }
        sessionRepository.save(s);

        if (s.getStatus() == MapBanPickSession.Status.COMPLETED) {
            persistToMatch(s);
            eventBus.publish(s.getBpSessionId(), "bp_completed",
                    Map.of("selected_maps", selectedList(s)));
        } else {
            eventBus.publish(s.getBpSessionId(), "bp_action", Map.of(
                    "side", step.side().name(), "action", step.action().name(),
                    "map_id", map.getMapId(), "map_name", map.getName(),
                    "timeout", timeout));
            eventBus.publish(s.getBpSessionId(), "bp_turn_change", Map.of(
                    "turn", s.getCurrentTurn(), "round", s.getCurrentRound()));
        }
        pushState(s);
    }

    /** 收尾：COMPLETED，落定最终选图。 */
    private void finishSession(MapBanPickSession s) {
        s.setStatus(MapBanPickSession.Status.COMPLETED);
        s.setEndTime(Instant.now());
        s.setCurrentTurn(null);
        s.setCurrentTurnDeadline(null);
    }

    /** 最终选图写入对局会话。 */
    private void persistToMatch(MapBanPickSession s) {
        if (s.getMatchId() == null || s.getMatchId().isBlank()) return;
        matchRepository.findById(s.getMatchId()).ifPresent(m -> {
            m.setSelectedMaps(write(selectedList(s)));
            m.setBpSessionId(s.getBpSessionId());
            matchRepository.save(m);
        });
    }

    private Map<String, Object> stateDto(MapBanPickSession s) {
        Step step = currentStep(s).orElse(null);
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("bp_session_id", s.getBpSessionId());
        dto.put("tournament_id", s.getTournamentId());
        dto.put("match_id", s.getMatchId());
        dto.put("format", s.getFormat().name());
        dto.put("status", s.getStatus().name());
        dto.put("pool_id", s.getPoolId());
        dto.put("blue_team_name", s.getBlueTeamName());
        dto.put("red_team_name", s.getRedTeamName());
        dto.put("turn_index", s.getTurnIndex());
        dto.put("current_turn", s.getCurrentTurn());
        dto.put("current_round", s.getCurrentRound());
        dto.put("total_rounds", s.getTotalRounds());
        dto.put("turn_timeout_seconds", s.getTurnTimeoutSeconds());
        dto.put("turn_deadline", s.getCurrentTurnDeadline() == null ? null : s.getCurrentTurnDeadline().toString());
        dto.put("can_act_for", step == null ? null : step.side().name());
        dto.put("start_time", s.getStartTime() == null ? null : s.getStartTime().toString());
        dto.put("end_time", s.getEndTime() == null ? null : s.getEndTime().toString());
        dto.put("cancel_reason", s.getCancelReason());
        dto.put("selected_maps", selectedList(s));
        dto.put("banned_maps", bannedList(s));
        dto.put("actions", actions(s.getBpSessionId()));
        return dto;
    }

    private void pushState(MapBanPickSession s) {
        eventBus.publish(s.getBpSessionId(), "bp_state", stateDto(s));
    }

    // ---------------- JSON 列表辅助 ----------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> selectedList(MapBanPickSession s) {
        return readList(s.getSelectedMaps());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> bannedList(MapBanPickSession s) {
        return readList(s.getBannedMaps());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            Object o = mapper.readValue(json, List.class);
            return (List<Map<String, Object>>) o;
        } catch (Exception e) {
            log.warn("BP JSON 解析失败 err={}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void bannedMapsAdd(MapBanPickSession s, MapEntry map, MapBanPickSession.Side side, int round) {
        List<Map<String, Object>> list = bannedList(s);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("map_id", map.getMapId());
        item.put("map_name", map.getName());
        item.put("side", side.name());
        item.put("round", round);
        list.add(item);
        s.setBannedMaps(write(list));
    }

    private void selectedListAdd(MapBanPickSession s, MapEntry map, MapBanPickSession.Side side, int round) {
        List<Map<String, Object>> list = selectedList(s);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("map_id", map.getMapId());
        item.put("map_name", map.getName());
        item.put("side", side.name());
        item.put("round", round);
        list.add(item);
        s.setSelectedMaps(write(list));
    }

    private String write(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ---------------- 合法性校验 ----------------

    /** 校验敌方：地图须属于本会话池、active 且未被选/未在 Ban。 */
    private MapEntry mapForAction(MapBanPickSession s, String mapId, Step step) {
        MapEntry map = entryRepository.findById(mapId).orElse(null);
        if (map == null || !s.getPoolId().equals(map.getPoolId()) || !map.isActive()) {
            throw new IllegalArgumentException("地图无效或不可用");
        }
        if (bannedList(s).stream().anyMatch(m -> mapId.equals(m.get("map_id")))) {
            throw new IllegalArgumentException("该地图已被 Ban");
        }
        boolean picked = selectedList(s).stream().anyMatch(m -> mapId.equals(m.get("map_id")));
        if (step.action() == MapBanPickAction.ActionType.PICK && picked) {
            throw new IllegalArgumentException("该地图已被 Pick");
        }
        return map;
    }

    /** 随机选一张当前步合法且未占用（Ban/Pick 各不重复）的地图。 */
    private MapEntry randomValidMap(MapBanPickSession s, Step step) {
        List<MapEntry> candidates = entryRepository.findByPoolIdAndActiveTrueOrderByOrderNoAsc(s.getPoolId());
        List<String> usedBans = bannedList(s).stream().map(m -> String.valueOf(m.get("map_id"))).toList();
        List<String> usedPicks = selectedList(s).stream().map(m -> String.valueOf(m.get("map_id"))).toList();
        List<MapEntry> pool = candidates.stream()
                .filter(m -> !usedBans.contains(m.getMapId()))
                .filter(m -> step.action() != MapBanPickAction.ActionType.PICK
                        || !usedPicks.contains(m.getMapId()))
                .toList();
        if (pool.isEmpty()) {
            throw new IllegalStateException("地图池可用图不足，无法自动落子");
        }
        return pool.get(new java.security.SecureRandom().nextInt(pool.size()));
    }

    private MapBanPickSession require(String bpId) {
        return sessionRepository.findById(bpId)
                .orElseThrow(() -> new IllegalArgumentException("BP 会话不存在"));
    }

    private Enrollment approvedEnrollmentOf(String pteid) {
        Enrollment en = enrollmentRepository.findFirstByPteidOrderByCreatedAtDesc(pteid).orElse(null);
        if (en == null || en.getStatus() != Enrollment.Status.APPROVED) {
            throw new SecurityException("选手未通过参赛审批");
        }
        return en;
    }

    private boolean isSide(Enrollment en, MapBanPickSession s, MapBanPickSession.Side side) {
        return side == MapBanPickSession.Side.BLUE
                ? en.getEnrollmentId().equals(s.getBlueEnrollmentId())
                : en.getEnrollmentId().equals(s.getRedEnrollmentId());
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }

    // ---------------- 进程内锁 ----------------

    private <T> T withLock(String bpId, java.util.function.Supplier<T> task) {
        Object lock = locks.computeIfAbsent(bpId, k -> new Object());
        synchronized (lock) {
            try {
                return task.get();
            } finally {
                // 无等待释放锁（JUC 语义下锁对象保留以复用；不主动移除避免并发竞态）
            }
        }
    }
}