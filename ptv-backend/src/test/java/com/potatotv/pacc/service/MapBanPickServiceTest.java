package com.potatotv.pacc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.Enrollment;
import com.potatotv.pacc.domain.MapBanPickSession;
import com.potatotv.pacc.domain.MapEntry;
import com.potatotv.pacc.domain.MapPool;
import com.potatotv.pacc.repository.EnrollmentRepository;
import com.potatotv.pacc.repository.MapBanPickActionRepository;
import com.potatotv.pacc.repository.MapBanPickSessionRepository;
import com.potatotv.pacc.repository.MapEntryRepository;
import com.potatotv.pacc.repository.MapPoolRepository;
import com.potatotv.pacc.repository.MatchSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 地图 BP 状态机单元测试：回合序列生成、权限链、完整推进至完成、超时自动操作。
 */
class MapBanPickServiceTest {

    private MapBanPickSessionRepository sessionRepository;
    private MapBanPickActionRepository actionRepository;
    private MapEntryRepository entryRepository;
    private MapPoolRepository poolRepository;
    private EnrollmentRepository enrollmentRepository;
    private MatchSessionRepository matchRepository;
    private MapBpEventBus eventBus;
    private MapBanPickService service;

    private final String POOL = "POOL-X";
    private final String BLUE_PTEID = "blue-player";
    private final String RED_PTEID = "red-player";

    private Enrollment blueEnrollment;
    private Enrollment redEnrollment;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(MapBanPickSessionRepository.class);
        actionRepository = mock(MapBanPickActionRepository.class);
        entryRepository = mock(MapEntryRepository.class);
        poolRepository = mock(MapPoolRepository.class);
        enrollmentRepository = mock(EnrollmentRepository.class);
        matchRepository = mock(MatchSessionRepository.class);
        eventBus = mock(MapBpEventBus.class);
        service = new MapBanPickService(sessionRepository, actionRepository, entryRepository,
                poolRepository, enrollmentRepository, matchRepository, eventBus, new ObjectMapper());

        blueEnrollment = Enrollment.builder().enrollmentId("E-BLUE").pteid(BLUE_PTEID)
                .permittedDeviceFingerprint("fp-blue").status(Enrollment.Status.APPROVED).build();
        redEnrollment = Enrollment.builder().enrollmentId("E-RED").pteid(RED_PTEID)
                .permittedDeviceFingerprint("fp-red").status(Enrollment.Status.APPROVED).build();

        when(poolRepository.findById(POOL)).thenReturn(Optional.of(
                MapPool.builder().poolId(POOL).name("神域").build()));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(actionRepository.findByBpSessionIdOrderByRoundNoAscCreatedAtAsc(anyString()))
                .thenReturn(new ArrayList<>());

        when(enrollmentRepository.findFirstByPteidOrderByCreatedAtDesc(BLUE_PTEID))
                .thenReturn(Optional.of(blueEnrollment));
        when(enrollmentRepository.findFirstByPteidOrderByCreatedAtDesc(RED_PTEID))
                .thenReturn(Optional.of(redEnrollment));

        when(entryRepository.findById(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return Optional.of(MapEntry.builder().mapId(id).poolId(POOL).name("图" + id).active(true).build());
        });
        when(entryRepository.findByPoolIdAndActiveTrueOrderByOrderNoAsc(POOL)).thenAnswer(inv -> {
            List<MapEntry> list = new ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                String id = "M" + i;
                list.add(MapEntry.builder().mapId(id).poolId(POOL).name("图" + id).active(true).build());
            }
            return list;
        });
    }

    /** 创建并开始一个会话（返回已开始对象引用供后续操作）。 */
    private MapBanPickSession newStartedSession(MapBanPickSession.Format format) {
        MapBanPickSession s = service.create(format, POOL, "T-1", null, null,
                "E-BLUE", "E-RED", "蓝队", "红队", 60, "裁判", "admin");
        when(sessionRepository.findById(s.getBpSessionId())).thenReturn(Optional.of(s));
        return service.start(s.getBpSessionId(), "admin");
    }

    @Test
    void planSequenceMatchesFormats() {
        List<MapBanPickService.Step> bo1 = MapBanPickService.plan(MapBanPickSession.Format.BO1);
        assertEquals(5, bo1.size(), "BO1 = 4 Ban + 1 Pick");
        assertEquals("RED_BAN", bo1.get(3).token(), "第4步应为红方Ban");

        List<MapBanPickService.Step> bo3 = MapBanPickService.plan(MapBanPickSession.Format.BO3);
        assertEquals(7, bo3.size(), "BO3 = 4 Ban + 3 Pick");
        assertEquals("BLUE_PICK", bo3.get(6).token(), "BO3 最后一轮由蓝方Pick（蓝方优先）");

        List<MapBanPickService.Step> bo5 = MapBanPickService.plan(MapBanPickSession.Format.BO5);
        assertEquals(9, bo5.size(), "BO5 = 4 Ban + 5 Pick");
    }

    @Test
    void forceDrivenFlowCompletesBo3() throws Exception {
        MapBanPickSession s = newStartedSession(MapBanPickSession.Format.BO3);
        assertTrue(s.getStatus() == MapBanPickSession.Status.ACTIVE, "开始后应 ACTIVE");

        String[] acts = {"BAN", "BAN", "BAN", "BAN", "PICK", "PICK", "PICK"};
        String[] maps = {"M1", "M2", "M3", "M4", "M5", "M6", "M7"};

        for (int i = 0; i < acts.length; i++) {
            Map<String, Object> st = service.forceAction(s.getBpSessionId(), acts[i], maps[i], "ref");
            assertNotNull(st);
        }

        MapBanPickSession done = sessionRepository.findById(s.getBpSessionId()).orElseThrow();
        assertEquals(MapBanPickSession.Status.COMPLETED, done.getStatus(), "应推进到 COMPLETED");
        List<?> selected = new ObjectMapper().readValue(done.getSelectedMaps(), List.class);
        assertEquals(3, selected.size(), "BO3 最终选图应为 3 张");
        assertEquals(M7_STEP_LABEL, ((java.util.Map<?, ?>) selected.get(2)).get("map_name"));
    }

    private static final String M7_STEP_LABEL = "图M7";

    @Test
    void playerActionAdvancesTurnForBlueBan() {
        MapBanPickSession s = newStartedSession(MapBanPickSession.Format.BO1);
        Map<String, Object> state = service.playerAction(s.getBpSessionId(), "BAN", "M1",
                BLUE_PTEID, "fp-blue", "1.2.3.4");
        assertEquals("RED_BAN", state.get("current_turn"), "蓝方Ban后应轮到红方Ban");
        assertEquals(s.getBpSessionId(), state.get("bp_session_id"));
    }

    @Test
    void wrongSideRejected() {
        MapBanPickSession s = newStartedSession(MapBanPickSession.Format.BO1);
        assertThrows(SecurityException.class, () ->
                service.playerAction(s.getBpSessionId(), "BAN", "M1", RED_PTEID, "fp-red", "1.2.3.4"));
    }

    @Test
    void deviceMismatchRejected() {
        MapBanPickSession s = newStartedSession(MapBanPickSession.Format.BO1);
        assertThrows(SecurityException.class, () ->
                service.playerAction(s.getBpSessionId(), "BAN", "M1", BLUE_PTEID, "fp-wrong", "1.2.3.4"));
    }

    @Test
    void wrongActionTypeRejected() {
        MapBanPickSession s = newStartedSession(MapBanPickSession.Format.BO1);
        assertThrows(IllegalArgumentException.class, () ->
                service.playerAction(s.getBpSessionId(), "PICK", "M1", BLUE_PTEID, "fp-blue", "1.2.3.4"));
    }

    @Test
    void unapprovedPlayerRejected() {
        when(enrollmentRepository.findFirstByPteidOrderByCreatedAtDesc("stranger"))
                .thenReturn(Optional.of(Enrollment.builder().enrollmentId("E-X").pteid("stranger")
                        .status(Enrollment.Status.PENDING).build()));
        MapBanPickSession s = newStartedSession(MapBanPickSession.Format.BO1);
        assertThrows(SecurityException.class, () ->
                service.playerAction(s.getBpSessionId(), "BAN", "M1", "stranger", "fp", "1.2.3.4"));
    }

    @Test
    void expiredTurnAutoAdvances() {
        MapBanPickSession s = newStartedSession(MapBanPickSession.Format.BO1);
        // 把当前回合截止时间推到过去，触发惰性超时自动落子
        s.setCurrentTurnDeadline(Instant.now().minusSeconds(5));
        // state() 读路径会做惰性超时推进
        service.state(s.getBpSessionId());
        MapBanPickSession after = sessionRepository.findById(s.getBpSessionId()).orElseThrow();
        assertEquals(1, after.getTurnIndex(), "超时后应推进一步");
        assertEquals("RED_BAN", after.getCurrentTurn());
    }
}