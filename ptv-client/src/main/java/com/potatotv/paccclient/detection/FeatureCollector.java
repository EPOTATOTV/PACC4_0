package com.potatotv.paccclient.detection;

import com.potatotv.paccclient.detection.analysis.Stats;
import com.potatotv.paccclient.detection.analysis.TrajectoryAnalyzer;
import com.potatotv.paccclient.detection.samples.ActionSample;
import com.potatotv.paccclient.detection.samples.AttackSample;
import com.potatotv.paccclient.detection.samples.BlockActionSample;
import com.potatotv.paccclient.detection.samples.InputEvent;
import com.potatotv.paccclient.detection.samples.Point2D;
import com.potatotv.paccclient.detection.samples.PositionSample;
import com.potatotv.paccclient.detection.samples.Vec3;
import com.potatotv.paccclient.detection.stealth.StealthTelemetry;
import com.potatotv.paccclient.detection.telemetry.EnvironmentTelemetry;
import com.potatotv.paccclient.detection.telemetry.JvmTelemetry;
import com.potatotv.paccclient.detection.telemetry.NetworkTelemetry;
import com.potatotv.paccclient.detection.telemetry.TelemetrySnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * v5.2 特征采集器（文档 §2.2.4）：一次 {@link #collect()} 产出完整 178 维 {@link FeatureVector}。
 *
 * <p>数据来源：</p>
 * <ul>
 *   <li>战斗/移动组：来自注入的 {@link InputSource}（鼠标/键盘 Hook、位置与攻击采样）；</li>
 *   <li>环境设备/JVM/网络组：来自纯 JDK 内省的 {@link EnvironmentTelemetry}/{@link JvmTelemetry}/
 *       {@link NetworkTelemetry}（全部 graceful degradation，绝不抛出）；</li>
 *   <li>模组组：需 Java Agent（文档 §5）回填，本模块无数据源时置 0。</li>
 * </ul>
 *
 * <p>{@link #coverage()} 返回「本周期有真实数据源支撑」的维度数（文档验收 A02）。缺失维度一律填 0
 * 或中性默认值，保证任意时刻 {@code collect().size() == 178}。相对文档 §10.1，
 * 采集本身不做随机数、不做占位取样。</p>
 */
public final class FeatureCollector {

    /** 原版玩家地面移动基准速度（方块/秒，Java 版奔跑约 5.6）。 */
    private static final double BASE_SPEED = 5.6;
    /** 爆发点击间隔阈值（ms），与 {@code ClickIntervalAnalyzer.BURST_INTERVAL_MS} 一致。 */
    private static final long BURST_INTERVAL_MS = 30L;
    /** 单次采样距离超过该值视为瞬移（格）。 */
    private static final double TELEPORT_DISTANCE = 8.0;
    /** 台阶判定净上升高度（格）。 */
    private static final double STEP_MIN_RISE = 0.6;
    /** 坠落伤害判定高度（格）。 */
    private static final double FALL_DAMAGE_HEIGHT = 3.0;

    private final InputSource source;
    private final TrajectoryAnalyzer trajectoryAnalyzer = new TrajectoryAnalyzer();
    private int coverage;

    public FeatureCollector(InputSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /** 采集一帧完整特征向量（178 维，schema 顺序）。 */
    public FeatureVector collect() {
        Map<String, Double> values = new LinkedHashMap<>();
        Set<String> backed = new HashSet<>();
        collectCombat(values, backed);
        collectMovement(values, backed);
        merge(values, backed, EnvironmentTelemetry.snapshot());
        merge(values, backed, JvmTelemetry.snapshot());
        merge(values, backed, NetworkTelemetry.snapshot());
        // §4 隐身探针（PCIe DMA / IOMMU / 注入痕迹 / 调试通道 / 虚拟化 / 沙箱）：
        // 结果带 TTL 缓存，命令不可用或非受支持平台只写已知项，不虚增覆盖度
        if (PerfToggles.enabled(PerfToggles.STEALTH_PROBES)) {
            merge(values, backed, StealthTelemetry.toTelemetry(StealthTelemetry.probe()));
        }

        FeatureVector fv = new FeatureVector();
        int covered = 0;
        for (FeatureSchema.Dim dim : FeatureSchema.DIMS) {
            Double v = values.get(dim.key());
            fv.put(dim.key(), v == null ? FeatureSchema.NEUTRAL_DEFAULTS.getOrDefault(dim.key(), 0.0) : v);
            if (backed.contains(dim.key())) covered++;
        }
        coverage = covered;
        return fv;
    }

    /** 上一帧有真实数据支撑的维度数。 */
    public int coverage() {
        return coverage;
    }

    private void merge(Map<String, Double> values, Set<String> backed, TelemetrySnapshot snap) {
        values.putAll(snap.values());
        backed.addAll(snap.backed());
    }

    // ============================================================================================
    // 战斗组
    // ============================================================================================

    private void collectCombat(Map<String, Double> v, Set<String> backed) {
        List<InputEvent> clicks = filterInputs(InputEvent.Kind.CLICK);
        List<InputEvent> swings = filterInputs(InputEvent.Kind.SWING);
        List<AttackSample> attacks = source.attackSamples();

        collectClickFeatures(v, backed, clicks, swings);
        collectAimAndAttackFeatures(v, backed, attacks);
        collectInteractionFeatures(v, backed);
    }

    private List<InputEvent> filterInputs(InputEvent.Kind kind) {
        List<InputEvent> out = new ArrayList<>();
        for (InputEvent e : source.inputEvents()) {
            if (e != null && e.kind() == kind) out.add(e);
        }
        return out;
    }

    private void collectClickFeatures(Map<String, Double> v, Set<String> backed,
                                      List<InputEvent> clicks, List<InputEvent> swings) {
        List<Long> intervals = intervals(clickTimestamps(clicks));
        if (!clicks.isEmpty()) {
            long last = clicks.get(clicks.size() - 1).timestampMillis();
            double cps = 0;
            for (InputEvent c : clicks) {
                if (c.timestampMillis() >= last - 1000) cps++;
            }
            put(v, backed, "feature_click_cps", cps);
        }
        if (intervals.size() >= 2) {
            double[] arr = toArray(intervals);
            put(v, backed, "feature_click_interval_mean", Stats.mean(arr));
            put(v, backed, "feature_click_interval_var", Stats.variance(arr));
            put(v, backed, "feature_click_interval_cv", Stats.cv(arr));
            put(v, backed, "feature_click_interval_skew", Stats.skewness(arr));
            put(v, backed, "feature_click_interval_kurt", Stats.kurtosis(arr));
        }
        if (!intervals.isEmpty()) {
            int burst = 0;
            for (long i : intervals) {
                if (i < BURST_INTERVAL_MS) burst++;
            }
            put(v, backed, "feature_click_burst_count", burst);
            put(v, backed, "feature_click_burst_ratio", (double) burst / intervals.size());
        }
        if (!swings.isEmpty()) {
            long first = swings.get(0).timestampMillis();
            long last = swings.get(swings.size() - 1).timestampMillis();
            double span = Math.max(1.0, (last - first) / 1000.0);
            put(v, backed, "feature_swing_animation_speed", swings.size() / span);
            List<Long> sw = intervals(swingTimestamps(swings));
            if (sw.size() >= 2) {
                put(v, backed, "feature_swing_animation_cv", Stats.cv(toArray(sw)));
            }
        }
    }

    private void collectAimAndAttackFeatures(Map<String, Double> v, Set<String> backed,
                                             List<AttackSample> attacks) {
        List<Point2D> trajectory = source.mouseTrajectory();
        TrajectoryAnalyzer.TrajectoryAnalysis ta = trajectoryAnalyzer.analyze(trajectory, source.aimTarget());
        if (trajectory.size() >= 2) {
            put(v, backed, "feature_aim_bezier_fit_error", ta.rmse());
            put(v, backed, "feature_aim_trajectory_curvature", ta.curvature());
            put(v, backed, "feature_trajectory_curvature", ta.curvature());
            put(v, backed, "feature_aim_smoothness", clamp01(ta.smoothness()));
            put(v, backed, "feature_aim_snap_ratio", ta.snapRatio());
            put(v, backed, "feature_aim_entropy", trajectoryEntropy(trajectory));
            if (source.aimTarget() != null) {
                put(v, backed, "feature_aim_target_attraction", ta.attraction());
            }
            double jitter = microJitterEntropy(trajectory);
            put(v, backed, "feature_aim_micro_jitter_entropy", jitter);
            put(v, backed, "feature_jitter_entropy", jitter);
            put(v, backed, "feature_human_likeness", clamp01(jitter / 3.5));
            double[] jerk = thirdDifferenceRms(trajectory);
            put(v, backed, "feature_aim_rotation_jerk", jerk[0]);
        }

        if (attacks.isEmpty()) return;

        long first = attacks.get(0).timestampMillis();
        long last = attacks.get(attacks.size() - 1).timestampMillis();
        double span = Math.max(1e-3, (last - first) / 1000.0);

        double[] deltas = aimDeltas(attacks);
        if (deltas.length > 0) {
            double meanDelta = Stats.mean(deltas);
            put(v, backed, "feature_killaura_mean", meanDelta);
            put(v, backed, "feature_killaura_std", Stats.std(deltas));
            double dtSum = 0;
            for (int i = 1; i < attacks.size(); i++) {
                dtSum += Math.max(1, attacks.get(i).timestampMillis() - attacks.get(i - 1).timestampMillis());
            }
            put(v, backed, "feature_killaura_angle_speed",
                    dtSum <= 0 ? 0 : sum(deltas) * 1000.0 / dtSum);
            int snaps = 0;
            for (double d : deltas) {
                if (d > 30.0) snaps++;
            }
            put(v, backed, "feature_aim_snap_count", snaps);
            double sem = clamp01((meanDelta / 180.0 + (double) snaps / deltas.length) / 2.0);
            put(v, backed, "feature_semantic_killaura", sem);
        }

        double[] distances = new double[attacks.size()];
        double[] pitches = new double[attacks.size()];
        int criticals = 0;
        int impossible = 0;
        int airborneCriticals = 0;
        int hits = 0;
        for (int i = 0; i < attacks.size(); i++) {
            AttackSample a = attacks.get(i);
            distances[i] = a.distance();
            pitches[i] = a.pitch();
            if (a.critical()) {
                criticals++;
                if (a.airborne()) airborneCriticals++;
            }
            if (a.critical() && !a.airborne()) impossible++;
            if (a.hit()) hits++;
        }
        put(v, backed, "feature_reach_distance", attacks.get(attacks.size() - 1).distance());
        put(v, backed, "feature_reach_distance_mean", Stats.mean(distances));
        put(v, backed, "feature_reach_distance_max", max(distances));
        put(v, backed, "feature_criticals_rate", (double) criticals / attacks.size());
        put(v, backed, "feature_criticals_impossible", impossible);
        put(v, backed, "feature_criticals_airborne_ratio", criticals == 0 ? 0.0 : (double) airborneCriticals / criticals);
        put(v, backed, "feature_attack_pitch_mean", Stats.mean(pitches));
        put(v, backed, "feature_attack_pitch_std", Stats.std(pitches));
        put(v, backed, "feature_attack_rate", countAttacksInWindow(attacks, last));
        put(v, backed, "feature_hit_select_consistency", (double) hits / attacks.size());

        List<Long> attackIntervals = intervals(attackTimestamps(attacks));
        if (attackIntervals.size() >= 2) {
            put(v, backed, "feature_attack_interval_cv", Stats.cv(toArray(attackIntervals)));
        }
        List<Long> combo = new ArrayList<>();
        for (long i : attackIntervals) {
            if (i < 200) combo.add(i);
        }
        if (combo.size() >= 2) {
            put(v, backed, "feature_combo_interval_cv", Stats.cv(toArray(combo)));
        }

        int[] runs = targetRuns(attacks);
        if (runs.length > 0) {
            int maxRun = 0;
            double runSum = 0;
            for (int r : runs) {
                maxRun = Math.max(maxRun, r);
                runSum += r;
            }
            put(v, backed, "feature_aim_lock_time", (double) maxRun / attacks.size());
            double meanInterval = attackIntervals.isEmpty() ? 0.25 : Stats.mean(toArray(attackIntervals)) / 1000.0;
            put(v, backed, "feature_target_lock_duration", (runSum / runs.length) * meanInterval);
            put(v, backed, "feature_hit_select_entropy", targetEntropy(attacks));
            put(v, backed, "feature_killaura_target_switch_rate", switches(runs) / span);
        }

        collectAutoBlockAndCorrection(v, backed, attacks);
        collectSlowDrift(v, backed, deltas);
    }

    private void collectAutoBlockAndCorrection(Map<String, Double> v, Set<String> backed,
                                               List<AttackSample> attacks) {
        List<BlockActionSample> blocks = source.blockActions();
        int blocked = 0;
        double switchSum = 0;
        int switchCount = 0;
        double correctionSum = 0;
        int correctionCount = 0;
        for (int i = 0; i < attacks.size(); i++) {
            AttackSample a = attacks.get(i);
            if (a.blocked()) {
                blocked++;
                long nearest = nearestDistance(blocks, a.timestampMillis());
                if (nearest >= 0) {
                    switchSum += nearest;
                    switchCount++;
                }
            }
            if (i > 0 && attacks.get(i - 1).hit()) {
                correctionSum += Math.abs(a.yaw() - attacks.get(i - 1).yaw())
                        + Math.abs(a.pitch() - attacks.get(i - 1).pitch());
                correctionCount++;
            }
        }
        put(v, backed, "feature_autoblock_ratio", (double) blocked / attacks.size());
        if (switchCount > 0) {
            put(v, backed, "feature_autoblock_switch_time", switchSum / switchCount);
        }
        if (correctionCount > 0) {
            put(v, backed, "feature_aim_post_hit_correction", correctionSum / correctionCount);
        }
    }

    private void collectSlowDrift(Map<String, Double> v, Set<String> backed, double[] deltas) {
        if (deltas.length < 3) return;
        // 对瞄准变化幅度做线性趋势拟合，斜率绝对值即缓慢漂移量（人类近 0）
        double n = deltas.length;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < deltas.length; i++) {
            sx += i;
            sy += deltas[i];
            sxx += (double) i * i;
            sxy += i * deltas[i];
        }
        double denom = n * sxx - sx * sx;
        double slope = Math.abs(denom) < 1e-9 ? 0.0 : (n * sxy - sx * sy) / denom;
        put(v, backed, "feature_slow_accel_drift", slope);
    }

    private void collectInteractionFeatures(Map<String, Double> v, Set<String> backed) {
        List<ActionSample> actions = source.actionSamples();
        if (actions.isEmpty()) return;
        long first = actions.get(0).timestampMillis();
        long last = actions.get(actions.size() - 1).timestampMillis();
        double span = Math.max(1.0, (last - first) / 1000.0);

        List<Double> eatDurations = new ArrayList<>();
        List<Long> eatTimes = new ArrayList<>();
        int chest = 0;
        double chestItems = 0;
        int inv = 0;
        double armorDelay = 0;
        int armorCount = 0;
        for (ActionSample a : actions) {
            switch (a.kind()) {
                case EAT -> {
                    eatDurations.add(Math.max(0, a.durationMillis()));
                    eatTimes.add(a.timestampMillis());
                }
                case CHEST -> {
                    chest++;
                    chestItems += Math.max(0, a.amount());
                }
                case INVENTORY -> inv++;
                case ARMOR -> {
                    armorDelay += Math.max(0, a.durationMillis());
                    armorCount++;
                }
            }
        }
        if (!eatDurations.isEmpty()) {
            double[] ed = new double[eatDurations.size()];
            for (int i = 0; i < ed.length; i++) ed[i] = eatDurations.get(i);
            put(v, backed, "feature_fasteat_duration_mean", Stats.mean(ed));
            List<Long> ei = intervals(eatTimes);
            if (ei.size() >= 2) put(v, backed, "feature_fasteat_interval_cv", Stats.cv(toArray(ei)));
        }
        if (chest > 0) put(v, backed, "feature_cheststealer_items_per_sec", chestItems / span);
        if (inv > 0) put(v, backed, "feature_invmanager_ops_per_sec", inv / span);
        if (armorCount > 0) put(v, backed, "feature_autoarmor_equip_delay", armorDelay / armorCount);
    }

    // ============================================================================================
    // 移动组
    // ============================================================================================

    private void collectMovement(Map<String, Double> v, Set<String> backed) {
        List<PositionSample> positions = source.positionSamples();
        List<BlockActionSample> blocks = source.blockActions();
        collectPositionFeatures(v, backed, positions);
        collectBlockFeatures(v, backed, blocks);
    }

    private void collectPositionFeatures(Map<String, Double> v, Set<String> backed,
                                         List<PositionSample> positions) {
        if (positions.size() < 2) return;
        long first = positions.get(0).timestampMillis();
        long last = positions.get(positions.size() - 1).timestampMillis();
        double span = Math.max(1e-3, (last - first) / 1000.0);

        int steps = positions.size() - 1;
        double[] speeds = new double[steps];
        double[] accels = new double[steps];
        double airSpeed = 0;
        double groundSpeed = 0;
        int airCount = 0;
        int groundCount = 0;
        double maxDelta = 0;
        int teleports = 0;
        double pathLen = 0;
        double maxVerticalAir = 0;
        double fallDistanceMax = 0;
        int nofallViolations = 0;
        int voidSamples = 0;
        double voidTime = 0;
        double hoverCount = 0;
        double airborneSprint = 0;
        int sprintMoving = 0;
        int movingSamples = 0;
        double omniViolations = 0;
        double jumpHeightSum = 0;
        int jumpRuns = 0;
        double diagonalSpeed = 0;
        int diagonalCount = 0;
        double axialSpeed = 0;
        int axialCount = 0;
        double stepHeight = 0;
        int stepViolations = 0;
        double velocitySum = 0;
        double verticalVelocitySum = 0;
        double velocityCorrSum = 0;
        int velocityCorrCount = 0;
        double slowAccelSum = 0;
        int slowAccelCount = 0;
        double headingChangeSum = 0;
        int headingChangeCount = 0;
        double headingSecondDiff = 0;
        double pathCurvature = 0;
        int curvatureCount = 0;
        double maxAirRunY = 0;
        double airRunStartY = 0;
        long airRunStartTs = 0;
        boolean inAirRun = false;
        double blinkJump = 0;
        double maxAirRun = 0;

        double prevHeading = Double.NaN;
        double prevHeadingChange = Double.NaN;
        double prevSpeed = Double.NaN;

        for (int i = 1; i < positions.size(); i++) {
            PositionSample a = positions.get(i - 1);
            PositionSample b = positions.get(i);
            Vec3 pa = a.position();
            Vec3 pb = b.position();
            double dt = Math.max(1, b.timestampMillis() - a.timestampMillis()) / 1000.0;
            double dx = pb.x() - pa.x();
            double dz = pb.z() - pa.z();
            double dy = pb.y() - pa.y();
            double horiz = Math.hypot(dx, dz);
            double speed = horiz / dt;
            speeds[i - 1] = speed;
            pathLen += Math.hypot(horiz, dy);
            maxDelta = Math.max(maxDelta, Math.sqrt(horiz * horiz + dy * dy));
            if (Math.hypot(horiz, dy) > TELEPORT_DISTANCE) teleports++;
            if (b.timestampMillis() - a.timestampMillis() < 20) {
                blinkJump = Math.max(blinkJump, Math.sqrt(horiz * horiz + dy * dy));
            }

            if (horiz > 0.01) {
                movingSamples++;
                if (b.sprinting()) sprintMoving++;
                double heading = Math.atan2(dz, dx);
                if (!Double.isNaN(prevHeading)) {
                    double change = angleDiff(heading, prevHeading);
                    headingChangeSum += Math.abs(change);
                    headingChangeCount++;
                    if (!Double.isNaN(prevHeadingChange)) {
                        headingSecondDiff += Math.pow(change - prevHeadingChange, 2);
                    }
                    prevHeadingChange = change;
                    if (b.sprinting() && Math.abs(change) > 120 * Math.PI / 180) omniViolations++;
                }
                // 斜向/轴向速度分离
                if (Math.abs(dx) > 0.05 && Math.abs(dz) > 0.05) {
                    diagonalSpeed += speed;
                    diagonalCount++;
                } else {
                    axialSpeed += speed;
                    axialCount++;
                }
                prevHeading = heading;
            }
            if (i >= 2) {
                pathCurvature += discreteCurvature(pa, a.position(), pb);
                curvatureCount++;
            }

            if (b.onGround()) {
                groundCount++;
                groundSpeed += speed;
                if (!a.onGround() && inAirRun) {
                    // 落地：结算本次滞空
                    double runHeight = maxAirRunY - Math.min(airRunStartY, pb.y());
                    jumpHeightSum += Math.max(0, maxAirRunY - airRunStartY);
                    jumpRuns++;
                    if (runHeight > FALL_DAMAGE_HEIGHT && !b.fallDamageTaken()) nofallViolations++;
                    fallDistanceMax = Math.max(fallDistanceMax, runHeight);
                    inAirRun = false;
                    maxAirRun = Math.max(maxAirRun, (b.timestampMillis() - airRunStartTs) / 1000.0);
                }
                double rise = dy;
                if (rise > STEP_MIN_RISE && rise <= 1.5) {
                    stepHeight = Math.max(stepHeight, rise);
                }
                if (rise > STEP_MIN_RISE && horiz > 0.05) {
                    stepViolations++;
                }
            } else {
                airCount++;
                airSpeed += speed;
                if (Math.abs(dy) > maxVerticalAir) maxVerticalAir = Math.abs(dy) / dt;
                if (b.sprinting()) airborneSprint++;
                if (Math.abs(b.velocity().y()) < 0.05) hoverCount++;
                if (!inAirRun) {
                    inAirRun = true;
                    airRunStartY = pa.y();
                    airRunStartTs = a.timestampMillis();
                    maxAirRunY = pb.y();
                } else {
                    maxAirRunY = Math.max(maxAirRunY, pb.y());
                }
            }
            if (b.position().y() < 0) {
                voidSamples++;
                voidTime += dt;
            }

            velocitySum += b.velocity().horizontalLength();
            verticalVelocitySum += b.velocity().y();
            if (b.velocity().length() > 0.01 && horiz > 0.01) {
                velocityCorrSum += Stats.cosine(b.velocity().x(), b.velocity().z(), dx, dz);
                velocityCorrCount++;
            }
            if (!Double.isNaN(prevSpeed)) {
                double accel = (speed - prevSpeed) / dt;
                accels[i - 1] = accel;
                if (accel < 0) {
                    slowAccelSum += -accel;
                    slowAccelCount++;
                }
            }
            prevSpeed = speed;
        }

        double[] speedArr = Arrays.copyOf(speeds, Math.max(1, steps));
        if (steps <= 0) return;
        double meanSpeed = Stats.mean(speedArr);
        put(v, backed, "feature_speed_ratio", meanSpeed / BASE_SPEED);
        put(v, backed, "feature_speed_mean", meanSpeed);
        put(v, backed, "feature_speed_max", max(speedArr));
        put(v, backed, "feature_speed_variance", Stats.variance(speedArr));
        put(v, backed, "feature_speed_accel", Stats.mean(Arrays.copyOf(accels, steps)));
        put(v, backed, "feature_speed_jerk", rmsSecondDifference(speedArr));
        put(v, backed, "feature_ground_time_ratio", (double) groundCount / positions.size());
        put(v, backed, "feature_air_time_ratio", (double) airCount / positions.size());
        put(v, backed, "feature_fly_vertical_speed", maxVerticalAir);
        put(v, backed, "feature_fly_sustain_time", maxAirRun);
        put(v, backed, "feature_fly_hover_ratio", airCount == 0 ? 0.0 : hoverCount / airCount);
        put(v, backed, "feature_fall_distance_max", fallDistanceMax);
        put(v, backed, "feature_nofall_violations", nofallViolations);
        put(v, backed, "feature_nofall_void", voidSamples);
        put(v, backed, "feature_void_time", voidTime);
        put(v, backed, "feature_velocity_horizontal", velocitySum / positions.size());
        put(v, backed, "feature_velocity_vertical", verticalVelocitySum / positions.size());
        put(v, backed, "feature_velocity_reduction_ratio", velocityReduction(positions));
        put(v, backed, "feature_velocity_ratio", velocityReduction(positions));
        put(v, backed, "feature_position_delta_per_tick_max", maxDelta);
        put(v, backed, "feature_teleport_count", teleports);
        put(v, backed, "feature_blink_position_jump", blinkJump);
        put(v, backed, "feature_step_height", stepHeight);
        put(v, backed, "feature_step_height_max", stepHeight);
        put(v, backed, "feature_step_violations", stepViolations);
        put(v, backed, "feature_jump_frequency", jumpRuns / span);
        put(v, backed, "feature_jump_height_mean", jumpRuns == 0 ? 0.0 : jumpHeightSum / jumpRuns);
        put(v, backed, "feature_sprint_consistency", movingSamples == 0 ? 0.0 : (double) sprintMoving / movingSamples);
        put(v, backed, "feature_omnisprint_violations", omniViolations);
        put(v, backed, "feature_sprint_food_mismatch", airCount == 0 ? 0.0 : airborneSprint / airCount);
        put(v, backed, "feature_collision_ignored_ratio", groundCount == 0 || groundSpeed <= 0
                ? 0.0 : (airSpeed / Math.max(1, airCount)) / (groundSpeed / groundCount));
        put(v, backed, "feature_slowdown_ratio", slowAccelCount == 0 ? 0.0 : slowAccelSum / slowAccelCount / BASE_SPEED);
        put(v, backed, "feature_diagonal_speed_ratio", axialCount == 0 || axialSpeed <= 0
                ? 1.0 : (diagonalCount == 0 ? 1.0 : (diagonalSpeed / diagonalCount) / (axialSpeed / axialCount)));
        put(v, backed, "feature_strafe_ratio", headingChangeCount == 0 ? 0.0
                : clamp01(headingChangeSum / headingChangeCount / Math.PI));
        put(v, backed, "feature_path_smoothness", headingChangeCount == 0 ? 1.0
                : clamp01(1.0 - Math.sqrt(headingSecondDiff / headingChangeCount) / Math.PI));
        put(v, backed, "feature_path_curvature", curvatureCount == 0 ? 0.0 : pathCurvature / curvatureCount);
        put(v, backed, "feature_movement_correlation", velocityCorrCount == 0 ? 0.0
                : velocityCorrSum / velocityCorrCount);
        put(v, backed, "feature_movement_input_lag", pathLen <= 0 ? 1.0
                : positions.get(0).position().horizontalDistanceTo(positions.get(positions.size() - 1).position()) / pathLen);
    }

    private void collectBlockFeatures(Map<String, Double> v, Set<String> backed,
                                      List<BlockActionSample> blocks) {
        if (blocks.isEmpty()) return;
        long first = blocks.get(0).timestampMillis();
        long last = blocks.get(blocks.size() - 1).timestampMillis();
        double span = Math.max(1.0, (last - first) / 1000.0);

        List<Long> placeTimes = new ArrayList<>();
        List<Long> breakTimes = new ArrayList<>();
        int sneakPlaces = 0;
        double faceAngleSum = 0;
        int faceAngleCount = 0;
        double maxRadius = 0;
        for (BlockActionSample b : blocks) {
            if (b.place()) {
                placeTimes.add(b.timestampMillis());
                if (b.sneaking()) sneakPlaces++;
                faceAngleSum += b.faceAngle();
                faceAngleCount++;
            } else {
                breakTimes.add(b.timestampMillis());
                maxRadius = Math.max(maxRadius, b.radius());
            }
        }
        if (!placeTimes.isEmpty()) {
            put(v, backed, "feature_scaffold_block_per_sec", placeTimes.size() / span);
            put(v, backed, "feature_fastplace_block_per_sec", maxPerSecond(placeTimes));
            put(v, backed, "feature_scaffold_sneak_consistency", (double) sneakPlaces / placeTimes.size());
            if (faceAngleCount > 0) {
                put(v, backed, "feature_scaffold_pitch_angle", faceAngleSum / faceAngleCount);
            }
        }
        if (!breakTimes.isEmpty()) {
            put(v, backed, "feature_fastbreak_block_per_sec", maxPerSecond(breakTimes));
            put(v, backed, "feature_nuker_break_radius", maxRadius);
        }
    }

    /** 惯性收尾：verlet 风格的减速比例仅在检测到受击速度突变时输出，否则保持中性默认。 */
    private static double velocityReduction(List<PositionSample> positions) {
        double pre = 0;
        double post = 0;
        int hits = 0;
        for (int i = 2; i < positions.size(); i++) {
            Vec3 v0 = positions.get(i - 2).velocity();
            Vec3 v1 = positions.get(i).velocity();
            double drop = v0.horizontalLength() - v1.horizontalLength();
            if (drop > 3.0) {
                pre += v0.horizontalLength();
                post += v1.horizontalLength();
                hits++;
            }
        }
        if (hits == 0 || pre <= 0) return 1.0;
        return post / pre * hits / hits;
    }

    // ============================================================================================
    // 通用工具
    // ============================================================================================

    private static void put(Map<String, Double> v, Set<String> backed, String key, double value) {
        double x = Double.isFinite(value) ? value : 0.0;
        v.put(key, x);
        backed.add(key);
    }

    private static List<Long> clickTimestamps(List<InputEvent> clicks) {
        List<Long> out = new ArrayList<>(clicks.size());
        for (InputEvent c : clicks) out.add(c.timestampMillis());
        return out;
    }

    private static List<Long> swingTimestamps(List<InputEvent> swings) {
        List<Long> out = new ArrayList<>(swings.size());
        for (InputEvent c : swings) out.add(c.timestampMillis());
        return out;
    }

    private static List<Long> attackTimestamps(List<AttackSample> attacks) {
        List<Long> out = new ArrayList<>(attacks.size());
        for (AttackSample a : attacks) out.add(a.timestampMillis());
        return out;
    }

    private static List<Long> intervals(List<Long> timestamps) {
        List<Long> out = new ArrayList<>();
        for (int i = 1; i < timestamps.size(); i++) {
            out.add(Math.max(1L, timestamps.get(i) - timestamps.get(i - 1)));
        }
        return out;
    }

    private static double[] toArray(List<Long> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private static double[] aimDeltas(List<AttackSample> attacks) {
        if (attacks.size() < 2) return new double[0];
        double[] d = new double[attacks.size() - 1];
        for (int i = 1; i < attacks.size(); i++) {
            double dYaw = Math.abs(attacks.get(i).yaw() - attacks.get(i - 1).yaw()) % 360.0;
            if (dYaw > 180) dYaw = 360 - dYaw;
            double dPitch = Math.abs(attacks.get(i).pitch() - attacks.get(i - 1).pitch());
            d[i - 1] = Math.hypot(dYaw, dPitch);
        }
        return d;
    }

    private static double countAttacksInWindow(List<AttackSample> attacks, long now) {
        double n = 0;
        for (AttackSample a : attacks) {
            if (a.timestampMillis() >= now - 1000) n++;
        }
        return n;
    }

    private static long nearestDistance(List<BlockActionSample> blocks, long ts) {
        long best = -1;
        for (BlockActionSample b : blocks) {
            long d = Math.abs(b.timestampMillis() - ts);
            if (best < 0 || d < best) best = d;
        }
        return best;
    }

    private static int[] targetRuns(List<AttackSample> attacks) {
        List<Integer> runs = new ArrayList<>();
        int run = 0;
        int prev = Integer.MIN_VALUE;
        for (AttackSample a : attacks) {
            if (a.targetId() == prev) {
                run++;
            } else {
                if (run > 0) runs.add(run);
                run = 1;
                prev = a.targetId();
            }
        }
        if (run > 0) runs.add(run);
        int[] out = new int[runs.size()];
        for (int i = 0; i < out.length; i++) out[i] = runs.get(i);
        return out;
    }

    private static double switches(int[] runs) {
        int total = 0;
        for (int r : runs) total += r;
        return Math.max(0, runs.length - 1);
    }

    private static double targetEntropy(List<AttackSample> attacks) {
        Map<Integer, Integer> hist = new LinkedHashMap<>();
        for (AttackSample a : attacks) hist.merge(a.targetId(), 1, Integer::sum);
        double[] p = new double[hist.size()];
        int i = 0;
        for (int c : hist.values()) p[i++] = (double) c / attacks.size();
        return Stats.shannonEntropy(p);
    }

    private static double trajectoryEntropy(List<Point2D> trajectory) {
        int bins = 8;
        int[] hist = new int[bins];
        int n = 0;
        for (int i = 1; i < trajectory.size(); i++) {
            double dx = trajectory.get(i).x() - trajectory.get(i - 1).x();
            double dy = trajectory.get(i).y() - trajectory.get(i - 1).y();
            if (Math.hypot(dx, dy) < 1e-6) continue;
            double ang = Math.atan2(dy, dx);
            int b = (int) Math.floor((ang + Math.PI) / (2 * Math.PI) * bins) % bins;
            hist[b]++;
            n++;
        }
        if (n == 0) return 0.0;
        double[] p = new double[bins];
        for (int i = 0; i < bins; i++) p[i] = (double) hist[i] / n;
        return Stats.shannonEntropy(p);
    }

    /** 微抖动：轨迹点相对邻域均值的位移直方图熵（bit）。 */
    private static double microJitterEntropy(List<Point2D> trajectory) {
        if (trajectory.size() < 3) return 0.0;
        int bins = 8;
        int[] hist = new int[bins];
        int n = 0;
        double max = 0;
        double[] mags = new double[trajectory.size() - 2];
        for (int i = 1; i < trajectory.size() - 1; i++) {
            double ax = (trajectory.get(i - 1).x() + trajectory.get(i + 1).x()) / 2.0;
            double ay = (trajectory.get(i - 1).y() + trajectory.get(i + 1).y()) / 2.0;
            double m = Math.hypot(trajectory.get(i).x() - ax, trajectory.get(i).y() - ay);
            mags[n++] = m;
            max = Math.max(max, m);
        }
        if (max <= 0) return 0.0;
        for (int i = 0; i < n; i++) {
            int b = (int) Math.min(bins - 1, Math.floor(mags[i] / max * bins));
            hist[b]++;
        }
        double[] p = new double[bins];
        for (int i = 0; i < bins; i++) p[i] = (double) hist[i] / n;
        return Stats.shannonEntropy(p);
    }

    /** 轨迹三阶差分的均方根（加加速度）：{rms, 0}。 */
    private static double[] thirdDifferenceRms(List<Point2D> trajectory) {
        if (trajectory.size() < 4) return new double[]{0, 0};
        double[] h = new double[trajectory.size()];
        for (int i = 0; i < trajectory.size(); i++) h[i] = trajectory.get(i).y();
        double se = 0;
        int n = 0;
        for (int i = 3; i < h.length; i++) {
            double third = h[i] - 3 * h[i - 1] + 3 * h[i - 2] - h[i - 3];
            se += third * third;
            n++;
        }
        return new double[]{n == 0 ? 0 : Math.sqrt(se / n), 0};
    }

    private static double rmsSecondDifference(double[] a) {
        if (a.length < 3) return 0.0;
        double se = 0;
        int n = 0;
        for (int i = 2; i < a.length; i++) {
            double d = a[i] - 2 * a[i - 1] + a[i - 2];
            se += d * d;
            n++;
        }
        return n == 0 ? 0.0 : Math.sqrt(se / n);
    }

    private static double maxPerSecond(List<Long> times) {
        int best = 0;
        for (int i = 0; i < times.size(); i++) {
            int c = 0;
            for (int j = i; j < times.size() && times.get(j) - times.get(i) < 1000; j++) c++;
            best = Math.max(best, c);
        }
        return best;
    }

    private static double discreteCurvature(Vec3 a, Vec3 b, Vec3 c) {
        double v1x = b.x() - a.x();
        double v1z = b.z() - a.z();
        double v2x = c.x() - b.x();
        double v2z = c.z() - b.z();
        double cross = Math.abs(v1x * v2z - v1z * v2x);
        double n1 = Math.hypot(v1x, v1z);
        double n2 = Math.hypot(v2x, v2z);
        double n3 = Math.hypot(v1x + v2x, v1z + v2z);
        double denom = n1 * n2 * n3;
        return denom < 1e-9 ? 0.0 : 2.0 * cross / denom;
    }

    private static double angleDiff(double a, double b) {
        double d = a - b;
        while (d > Math.PI) d -= 2 * Math.PI;
        while (d < -Math.PI) d += 2 * Math.PI;
        return d;
    }

    private static double sum(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return s;
    }

    private static double max(double[] a) {
        double m = 0;
        for (double v : a) m = Math.max(m, v);
        return m;
    }

    private static double clamp01(double x) {
        if (Double.isNaN(x)) return 0.0;
        return Math.max(0.0, Math.min(1.0, x));
    }
}