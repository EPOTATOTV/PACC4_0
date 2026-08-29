package com.potatotv.paccclient.detection;

/**
 * 检测引擎：调度各维度探针，合并出周期上报事件，并给出端侧预评分。
 * 端侧评分 + 历史信誉交给 PTV 做多维加权综合评分。
 */
public final class DetectionEngine {

    private final LowLevelProbe lowLevelProbe = new KernelMemoryProbe();
    private final BehaviorMonitor behaviorMonitor = new BehaviorMonitor();
    private final JavaAgentProbe javaAgentProbe = new JavaAgentProbe();
    private final BruteForceDetector bruteForceDetector = new BruteForceDetector();
    private final StealthDetector stealthDetector = new StealthDetector();

    /**
     * 执行一次完整采样。
     *
     * @return 合并后的端侧事件（可能为空表示本周期无异常）
     */
    public java.util.Optional<DetectionEvent> sample(int demoBaseRisk) {
        // 演示：以 baseRisk 兑出低概率的模拟事件，便于端到端联调
        if (demoBaseRisk > 0 && Math.random() < 0.01) {
            return java.util.Optional.of(new DetectionEvent(
                    "signature_hit", "medium", Math.min(100, demoBaseRisk + 8),
                    "javaw.exe", null, "demo-signature", "win10_x64", null));
        }
        // v4.1：暴力外挂 + 隐身外挂四/五层检测
        FeatureVector behaviorFv = bruteForceDetector.sample(sampleCps(), sampleAim(), sampleSpeed(), sampleFly(), 3.1);
        java.util.Optional<DetectionEvent> brute = bruteForceDetector.inspect(behaviorFv);
        if (brute.isPresent()) {
            return brute;
        }
        FeatureVector stealthFv = stealthDetector.probe(
                sampleDma(), sampleGhost(), sampleInject(), sampleRemoteThreads(), sampleVm(), sampleJitter());
        java.util.Optional<DetectionEvent> stealth = stealthDetector.inspect(stealthFv);
        if (stealth.isPresent()) {
            return stealth;
        }
        return lowLevelProbe.scan()
                .or(() -> behaviorMonitor.inspectInput((long) sampleCps(), sampleAim()))
                .or(() -> javaAgentProbe.scanForMods(false));
    }

    private double sampleCps() {
        return Math.max(0, Math.min(20, 4 + Math.random() * 12.0));
    }

    private double sampleAim() {
        return (Math.random() - 0.5) * 1.6;
    }

    private double sampleSpeed() {
        return 3.0 + Math.random() * 4.0;
    }

    private double sampleFly() {
        return Math.random() * 0.3;
    }

    private double sampleDma() {
        return Math.random() < 0.005 ? 1 : 0;
    }

    private double sampleGhost() {
        return Math.random() < 0.005 ? 1 : 0;
    }

    private double sampleInject() {
        return Math.random() < 0.005 ? 1 : 0;
    }

    private double sampleRemoteThreads() {
        return sampleInject();
    }

    private double sampleVm() {
        return Math.random() < 0.01 ? 1 : 0;
    }

    private double sampleJitter() {
        return 2.0 + Math.random() * 2.0;
    }
}