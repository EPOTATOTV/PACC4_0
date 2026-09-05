package com.potatotv.pacc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.potatotv.pacc.service.IntegrityGuardService.Input;
import com.potatotv.pacc.service.IntegrityGuardService.IntegrityReport;
import com.potatotv.pacc.service.IntegrityGuardService.State;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * v4.5 完整性护栏单测：CLEAN/SUSPECT/TAMPERED 三级判定与阈值边界。
 */
class IntegrityGuardServiceTest {

    private final IntegrityGuardService service = new IntegrityGuardService(25, 60);

    @Test
    void cleanWhenAllOk() {
        IntegrityReport r = service.assess(new Input(true, true, false, true, List.of()));
        assertEquals(State.CLEAN, r.state());
        assertEquals(0, r.score());
    }

    @Test
    void suspectWhenMinorSigIssue() {
        // 签名无效 +30 → 25 <= 30 < 60 → SUSPECT
        IntegrityReport r = service.assess(new Input(false, true, false, true, List.of()));
        assertEquals(State.SUSPECT, r.state());
        assertEquals(30, r.score());
    }

    @Test
    void codeHashMismatchAloneIsSuspect() {
        // 代码哈希不符 +40 → 25 <= 40 < 60 → SUSPECT（需叠加其他异常才到 TAMPERED）
        IntegrityReport r = service.assess(new Input(true, true, false, false, List.of()));
        assertEquals(State.SUSPECT, r.state());
        assertEquals(40, r.score());
    }

    @Test
    void tamperedWhenTestsigningAndDseOff() {
        // dse 关闭 +20，testsigning +30 → 50；再加个 hook +10 = 60 → TAMPERED 边界
        IntegrityReport r = service.assess(new Input(true, false, true, true, List.of("kernel32")));
        assertEquals(60, r.score());
        assertEquals(State.TAMPERED, r.state());
    }

    @Test
    void nullInputIsClean() {
        assertEquals(State.CLEAN, service.assess(null).state());
        assertEquals(0, service.assess(null).score());
    }

    @Test
    void scoreClampedAt100() {
        IntegrityReport r = service.assess(new Input(false, false, true, false, List.of("a", "b", "c", "d")));
        assertEquals(100, r.score());
        assertEquals(State.TAMPERED, r.state());
    }
}