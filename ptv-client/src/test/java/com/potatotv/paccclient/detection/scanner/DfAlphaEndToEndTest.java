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
 * 「检测器写 ext_ 扩展特征 → PRL 规则读取并加权判定」全链路。
 */
class DfAlphaEndToEndTest {

    /** 文档 §8.1 本批次新增的 12 条 PRL 规则（规则名 = CheatType.code）。 */
    private static final List<String> NEW_CODES = List.of(
            "cheat_process", "suspicious_window", "known_cheat_module", "suspicious_module",
            "memory_signature", "cheat_file_trace", "cheat_registry_trace", "ifeo_hijack",
            "cheat_driver", "suspicious_input_device", "suspicious_network", "behavior_anomaly");

    private static PrlDetectionEngine newRuleEngine() {
        PrlDetectionEngine engine = new PrlDetectionEngine();
        engine.loadBuiltin();
        return engine;
    }

    private static ProcessSnapshot.ProcessInfo proc(String name) {
        return new ProcessSnapshot.ProcessInfo(4242, name, "C:\\" + name, null);
    }

    @Test
    void 扩展特征schema为32维且结构合法() {
        ExtendedFeatureSchema.validate();
        assertEquals(32, ExtendedFeatureSchema.size());
        assertEquals(32, ExtendedFeatureSchema.keys().stream().distinct().count());
        assertTrue(ExtendedFeatureSchema.isExtendedKey("ext_process_score"));
    }

    @Test
    void 内置规则装载并包含全部12条新规则() {
        PrlDetectionEngine engine = newRuleEngine();
        assertEquals(36, engine.size(), "24 条既有 + 12 条新增");
        List<String> names = engine.ruleNames();
        for (String code : NEW_CODES) {
            assertTrue(names.contains(code), "缺少新规则 " + code);
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
    void 调度器登记10个内置检测器并可追加插件检测器() {
        ScannerRunner runner = ScannerRunner.withBuiltinDetectors();
        assertEquals(10, runner.detectors().size());
        assertEquals(Set.of("process_scanner", "module_scanner", "driver_scanner",
                        "file_scanner", "registry_scanner", "input_device_scanner",
                        "network_scanner", "memory_scanner", "behavior_ai_scanner",
                        "signature_matcher"),
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
        assertEquals(11, withPlugins.detectors().size(), "插件检测器应追加进调度器（文档 §2.4 步骤 7）");
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
}