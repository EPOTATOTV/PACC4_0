package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.ExtendedFeatureSchema;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.cheat.CheatFinding;
import com.potatotv.paccclient.detection.cheat.CheatType;
import com.potatotv.paccclient.detection.cheat.PrlDetectionEngine;
import com.potatotv.paccclient.probe.MemoryScanResult;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DF Alpha 1.0.0 端到端验证（文档 §10.3 验收）：
 * 「检测器写 ext_ 扩展特征 → PRL 规则读取并加权判定」全链路，
 * 并覆盖三层检测架构批次新增的网络 / 屏幕 / 系统增强检测器与规则。
 */
class DfAlphaEndToEndTest {

    /** 系统专项批次新增的 12 条 PRL 规则（规则名 = CheatType.code）。 */
    private static final List<String> NEW_CODES = List.of(
            "cheat_process", "suspicious_window", "known_cheat_module", "suspicious_module",
            "memory_signature", "cheat_file_trace", "cheat_registry_trace", "ifeo_hijack",
            "cheat_driver", "suspicious_input_device", "suspicious_network", "behavior_anomaly");

    /** 三层检测架构批次新增的 10 条 PRL 规则。 */
    private static final List<String> LAYER_CODES = List.of(
            "net_speed_anomaly", "net_fly_anomaly", "net_teleport", "net_packet_tamper",
            "vision_aimbot", "vision_esp", "onboard_macro", "injected_client",
            "kernel_callback", "unsigned_executable");

    private static PrlDetectionEngine newRuleEngine() {
        PrlDetectionEngine engine = new PrlDetectionEngine();
        engine.loadBuiltin();
        return engine;
    }

    private static ProcessSnapshot.ProcessInfo proc(String name) {
        return new ProcessSnapshot.ProcessInfo(4242, name, "C:\\" + name, null);
    }

    @Test
    void 扩展特征schema为74维且结构合法() {
        ExtendedFeatureSchema.validate();
        assertEquals(ExtendedFeatureSchema.SYSTEM_BATCH_SIZE + ExtendedFeatureSchema.LAYER_BATCH_SIZE, 74);
        assertEquals(74, ExtendedFeatureSchema.size());
        assertEquals(74, ExtendedFeatureSchema.keys().stream().distinct().count());
        assertTrue(ExtendedFeatureSchema.isExtendedKey("ext_process_score"));
        assertTrue(ExtendedFeatureSchema.isExtendedKey("ext_net_speed_ratio"));
        assertTrue(ExtendedFeatureSchema.isExtendedKey("ext_vision_hud_box_count"));
        assertTrue(ExtendedFeatureSchema.isExtendedKey("ext_macro_device_score"));
        assertTrue(ExtendedFeatureSchema.isExtendedKey("ext_sys_remote_thread_count"));
        assertTrue(ExtendedFeatureSchema.isExtendedKey("ext_fusion_score"));
    }

    @Test
    void 内置规则装载并包含全部新增规则() {
        PrlDetectionEngine engine = newRuleEngine();
        assertEquals(46, engine.size(), "24 条既有 + 系统专项 12 条 + 三层架构 10 条");
        List<String> names = engine.ruleNames();
        for (String code : NEW_CODES) {
            assertTrue(names.contains(code), "缺少新规则 " + code);
        }
        for (String code : LAYER_CODES) {
            assertTrue(names.contains(code), "缺少三层架构规则 " + code);
        }
    }

    @Test
    void 进程检测器写扩展特征后PRL规则命中() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(
                proc("horion.exe"), proc("cheatengine-x86_64.exe")));
        FeatureVector fv = new FeatureVector();

        new ProcessScanner().detect(new DetectContext(probe, fv));

        assertEquals(65.0, fv.get("ext_process_score"), 1e-9, "35(客户端) + 30(内存修改器)");
        List<CheatFinding> hits = newRuleEngine().evaluate(fv, AnalysisContext.empty());
        assertTrue(hits.stream().anyMatch(h -> h.type() == CheatType.CHEAT_PROCESS),
                "ext_process_score 65 应触发 cheat_process 规则");
    }

    @Test
    void 内存检测器写扩展特征后PRL规则命中() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.MEMORY);
        probe.processes = new ProcessSnapshot(List.of(proc("Minecraft.Windows.exe")));
        probe.memoryResults.add(new MemoryScanResult("horion_aimbot", true, List.of(0x140000000L), null));
        probe.memoryResults.add(MemoryScanResult.unsupported("ce_speedhack", "no match"));
        probe.memoryResults.add(MemoryScanResult.unsupported("generic_aimbot_lock", "no match"));
        FeatureVector fv = new FeatureVector();

        new MemoryScanner().detect(new DetectContext(probe, fv));

        assertEquals(1.0, fv.get("ext_memory_supported"), 1e-9);
        assertEquals(1.0, fv.get("ext_memory_signature_hits"), 1e-9);
        List<CheatFinding> hits = newRuleEngine().evaluate(fv, AnalysisContext.empty());
        assertTrue(hits.stream().anyMatch(h -> h.type() == CheatType.MEMORY_SIGNATURE),
                "内存特征码命中应触发 memory_signature 规则");
    }

    @Test
    void 行为检测器多信号叠加后PRL规则命中() {
        FakeSystemProbe probe = new FakeSystemProbe();
        FeatureVector fv = new FeatureVector();
        fv.put("feature_click_interval_cv", 0.1);
        fv.put("feature_click_cps", 10);
        fv.put("feature_aim_smoothness", 0.99);
        fv.put("feature_aim_micro_jitter_entropy", 1.0);

        new BehaviorAIScanner().detect(new DetectContext(probe, fv));

        assertEquals(95.0, fv.get("ext_behavior_score"), 1e-9);
        List<CheatFinding> hits = newRuleEngine().evaluate(fv, AnalysisContext.empty());
        assertTrue(hits.stream().anyMatch(h -> h.type() == CheatType.BEHAVIOR_ANOMALY));
    }

    @Test
    void 单指标不触发行为异常规则() {
        FakeSystemProbe probe = new FakeSystemProbe();
        FeatureVector fv = new FeatureVector();
        fv.put("feature_aim_smoothness", 0.99);

        new BehaviorAIScanner().detect(new DetectContext(probe, fv));

        assertEquals(20.0, fv.get("ext_behavior_score"), 1e-9, "单一弱信号只给 20 分");
        List<CheatFinding> hits = newRuleEngine().evaluate(fv, AnalysisContext.empty());
        assertTrue(hits.stream().noneMatch(h -> h.type() == CheatType.BEHAVIOR_ANOMALY),
                "20 分 < 规则阈值 40，单指标不触发（文档 §9 注意事项 1）");
    }

    @Test
    void 调度器登记17个内置检测器并可追加插件检测器() {
        ScannerRunner runner = ScannerRunner.withBuiltinDetectors();
        assertEquals(17, runner.detectors().size(), "系统专项 10 个 + 三层架构 7 个");
        assertEquals(Set.of("process_scanner", "module_scanner", "driver_scanner",
                        "file_scanner", "registry_scanner", "input_device_scanner",
                        "network_scanner", "memory_scanner", "behavior_ai_scanner",
                        "signature_matcher",
                        "network_behavior_scanner", "screen_vision_scanner", "input_timing_scanner",
                        "dll_signature_scanner", "injection_scanner", "unsigned_executable_scanner",
                        "kernel_callback_scanner"),
                runner.detectors().stream().map(Detector::id).collect(Collectors.toSet()));

        Detector pluginDetector = new Detector() {
            @Override
            public String id() {
                return "plugin_detector";
            }

            @Override
            public long intervalMs() {
                return 1000;
            }

            @Override
            public Optional<com.potatotv.paccclient.detection.DetectionEvent> detect(DetectContext ctx) {
                return Optional.empty();
            }
        };
        ScannerRunner withPlugins = ScannerRunner.withBuiltinDetectors(List.of(pluginDetector));
        assertEquals(18, withPlugins.detectors().size(), "插件检测器应追加进调度器（文档 §2.4 步骤 7）");
    }

    @Test
    void 扩展特征不改变核心178维() {
        FeatureVector fv = new FeatureVector();
        fv.put("feature_click_cps", 8);
        fv.putExtended("ext_process_score", 65);

        assertEquals(1, fv.size(), "核心维度只含显式写入的键");
        assertEquals(1, fv.extendedCount());
        assertTrue(fv.toReportMap().containsKey("ext_process_score"), "上报视图应包含扩展维度");
    }

    // ------------------------------------------------------------------ 三层检测架构批次端到端

    @Test
    void 网络层速度规则需要屏幕印证或极端倍率() {
        // 4.5 倍 + 画面运动不匹配：双源印证，命中
        FeatureVector dual = new FeatureVector();
        dual.putExtended("ext_net_speed_ratio", 4.5);
        dual.putExtended("ext_vision_motion_mismatch", 1);
        List<CheatFinding> hits = newRuleEngine().evaluate(dual, AnalysisContext.empty());
        assertTrue(hits.stream().anyMatch(h -> h.type() == CheatType.NET_SPEED_ANOMALY),
                "网络 + 屏幕双源应命中 net_speed_anomaly");

        // 3.0 倍但画面运动正常：弱单源，不命中（文档 §5.2 多源印证）
        FeatureVector weak = new FeatureVector();
        weak.putExtended("ext_net_speed_ratio", 3.0);
        List<CheatFinding> single = newRuleEngine().evaluate(weak, AnalysisContext.empty());
        assertTrue(single.stream().noneMatch(h -> h.type() == CheatType.NET_SPEED_ANOMALY),
                "3 倍速且无屏幕印证属于弱单源，不应触发");

        // 6.0 倍：极端单源，命中
        FeatureVector extreme = new FeatureVector();
        extreme.putExtended("ext_net_speed_ratio", 6.0);
        List<CheatFinding> blatant = newRuleEngine().evaluate(extreme, AnalysisContext.empty());
        assertTrue(blatant.stream().anyMatch(h -> h.type() == CheatType.NET_SPEED_ANOMALY),
                "≥4 倍属于极端单源，应触发");
    }

    @Test
    void 透视规则单源高分命中而单条线条不命中() {
        FeatureVector many = new FeatureVector();
        many.putExtended("ext_vision_esp_lines", 12);
        List<CheatFinding> hits = newRuleEngine().evaluate(many, AnalysisContext.empty());
        assertTrue(hits.stream().anyMatch(h -> h.type() == CheatType.VISION_ESP),
                "12 条长直线（透视 ESP）单源高分应命中");

        FeatureVector few = new FeatureVector();
        few.putExtended("ext_vision_esp_lines", 4);
        assertTrue(newRuleEngine().evaluate(few, AnalysisContext.empty()).stream()
                        .noneMatch(h -> h.type() == CheatType.VISION_ESP),
                "4 条长直线属正常画面，不应触发");
    }

    @Test
    void 板载宏规则要求时序与设备双源() {
        FeatureVector both = new FeatureVector();
        both.putExtended("ext_input_fixed_interval", 1);
        both.putExtended("ext_input_click_jitter_ms", 0.3);
        both.putExtended("ext_macro_device_score", 45);
        assertTrue(newRuleEngine().evaluate(both, AnalysisContext.empty()).stream()
                        .anyMatch(h -> h.type() == CheatType.ONBOARD_MACRO),
                "固定间隔 + 极低抖动 + 宏设备分应命中 onboard_macro");

        FeatureVector timingOnly = new FeatureVector();
        timingOnly.putExtended("ext_input_fixed_interval", 1);
        timingOnly.putExtended("ext_input_click_jitter_ms", 0.3);
        assertTrue(newRuleEngine().evaluate(timingOnly, AnalysisContext.empty()).stream()
                        .noneMatch(h -> h.type() == CheatType.ONBOARD_MACRO),
                "缺宏设备线索时单一时序信号不触发（文档 §7 注意事项 4）");
    }

    @Test
    void 注入与内核规则在系统层特征上命中() {
        FeatureVector injected = new FeatureVector();
        injected.putExtended("ext_sys_remote_thread_count", 2);
        injected.putExtended("ext_sys_unsigned_module_count", 1);
        injected.putExtended("ext_sys_blacklisted_publisher", 1);
        assertTrue(newRuleEngine().evaluate(injected, AnalysisContext.empty()).stream()
                        .anyMatch(h -> h.type() == CheatType.INJECTED_CLIENT),
                "远程线程 + 未签名模块 + 黑名单发布者应命中 injected_client");

        FeatureVector kernel = new FeatureVector();
        kernel.putExtended("ext_sys_ssdt_hooks", 1);
        assertTrue(newRuleEngine().evaluate(kernel, AnalysisContext.empty()).stream()
                        .anyMatch(h -> h.type() == CheatType.KERNEL_CALLBACK),
                "SSDT hook 应命中 kernel_callback（内核能力接入后生效）");

        assertTrue(newRuleEngine().evaluate(new FeatureVector(), AnalysisContext.empty()).isEmpty(),
                "全零扩展特征不得触发任何规则（用户态版本内核维度恒 0）");
    }
}