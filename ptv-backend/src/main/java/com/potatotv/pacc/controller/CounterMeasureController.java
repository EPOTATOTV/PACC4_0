package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.CheatHardwareProfile;
import com.potatotv.pacc.domain.CheatHardwareProfile.CheatFlag;
import com.potatotv.pacc.domain.CheatHardwareProfile.DeviceClass;
import com.potatotv.pacc.service.CounterMeasureRiskService;
import com.potatotv.pacc.service.CounterMeasureRiskService.CounterMeasureResult;
import com.potatotv.pacc.service.HardwareFingerprintService.HardwareReport;
import com.potatotv.pacc.service.IntegrityGuardService.Input;
import com.potatotv.pacc.repository.CheatHardwareProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

/**
 * v4.5 硬件级作弊与完整性对抗接口（受 X-Admin-Key 保护）。
 * <p>对抗风险融合分析 / 设备指纹库管理 / 分级统计。</p>
 */
@RestController
@RequestMapping("/api/admin/countermeasure")
@RequiredArgsConstructor
public class CounterMeasureController {

    private final CounterMeasureRiskService riskService;
    private final CheatHardwareProfileRepository profileRepository;

    /** 对抗风险融合分析：硬件指纹 + 输入时序宏 + 完整性自检 → 综合风险与置信度。 */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody Map<String, Object> body) {
        String pteid = str(body.get("pteid"));
        String edition = str(body.get("edition"));

        List<HardwareReport> reports = parseReports(body.get("reports"));
        List<Double> intervals = parseIntervals(body.get("intervals_ms"));
        Input integrity = parseIntegrity(body.get("integrity"));

        if (pteid.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "pteid required"));

        CounterMeasureResult r = riskService.handle(pteid, edition, reports, intervals, integrity);
        return ResponseEntity.ok(toMap(r));
    }

    /** 设备指纹库列表（按风险分倒序）。 */
    @GetMapping("/profiles")
    public List<CheatHardwareProfile> profiles() {
        return profileRepository.findByActiveTrueOrderByRiskScoreDesc();
    }

    /** 新增一条硬件指纹。 */
    @PostMapping("/profiles")
    public ResponseEntity<?> addProfile(@RequestBody Map<String, String> b) {
        try {
            CheatHardwareProfile p = CheatHardwareProfile.builder()
                    .id(UUID.randomUUID().toString())
                    .vendor(b.get("vendor"))
                    .vid(emptyToNull(b.get("vid")))
                    .pid(b.get("pid"))
                    .deviceClass(parseDeviceClass(b.get("device_class")))
                    .fingerprintPatterns(b.get("fingerprint_patterns"))
                    .cheatFlag(parseCheatFlag(b.get("cheat_flag")))
                    .riskScore(Integer.parseInt(b.getOrDefault("risk_score", "70")))
                    .active(true)
                    .createdBy(b.get("operator"))
                    .createdAt(Instant.now())
                    .build();
            return ResponseEntity.ok(profileRepository.save(p));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, Object> toMap(CounterMeasureResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("risk_score", r.riskScore());
        m.put("confidence_tier", r.tier().name());
        m.put("forced_redscreen", r.forcedRedscreen());
        m.put("cheat_type", r.cheatType());
        m.put("components", Map.of(
                "hardware", r.components().hardwareScore(),
                "input_macro", r.components().macroScore(),
                "integrity", r.components().integrityScore(),
                "integrity_state", String.valueOf(r.components().integrityState())));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<HardwareReport> parseReports(Object raw) {
        List<HardwareReport> out = new ArrayList<>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> rep = (Map<?, ?>) o;
            out.add(new HardwareReport(
                    str(rep.get("vid")), str(rep.get("pid")),
                    parseDeviceClass(str(rep.get("device_class"))),
                    str(rep.get("device_string"))));
        }
        return out;
    }

    private static List<Double> parseIntervals(Object raw) {
        List<Double> out = new ArrayList<>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<?>) raw) {
            try {
                out.add(Double.valueOf(o.toString()));
            } catch (NumberFormatException ignored) {
                // 跳过非法间隔
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Input parseIntegrity(Object raw) {
        if (!(raw instanceof Map)) return null;
        Map<?, ?> m = (Map<?, ?>) raw;
        List<String> hooks = new ArrayList<>();
        Object h = m.get("hook_found");
        if (h instanceof List) for (Object x : (List<?>) h) if (x != null) hooks.add(x.toString());
        return new Input(
                bool(m.get("signature_valid")),
                bool(m.get("dse_operating")),
                bool(m.get("testsigning_on")),
                bool(m.get("code_hash_match")),
                hooks);
    }

    private static DeviceClass parseDeviceClass(String s) {
        if (s == null || s.isBlank()) return DeviceClass.UNKNOWN;
        try {
            return DeviceClass.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DeviceClass.UNKNOWN;
        }
    }

    private static CheatFlag parseCheatFlag(String s) {
        if (s == null || s.isBlank()) return CheatFlag.GENERIC;
        try {
            return CheatFlag.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return CheatFlag.GENERIC;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static boolean bool(Object o) {
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}