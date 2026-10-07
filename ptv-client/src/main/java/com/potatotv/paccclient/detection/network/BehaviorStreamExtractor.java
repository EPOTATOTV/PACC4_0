package com.potatotv.paccclient.detection.network;

import com.potatotv.paccclient.detection.network.protocol.ParsedPacket;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 行为数据流提取器（网络代理层 §2.4）：把解析后的协议包转成可打分的时域指标。
 *
 * <p>三类有界环形缓冲：移动（1024）、旋转（1024）、动作（512）；包级时间戳默认取
 * {@link System#nanoTime()}，测试可用 {@link #feed(ParsedPacket, long)} 注入可控时钟。
 * 时间窗口指标（瞬移 60s / 攻击 5s / 放置 10s / 服务器纠正 60s / 无坠落 10s）都以最近一次
 * feed 的时间戳为「现在」，这样合成时钟与真实时钟都能算对。全部方法同步，可被多个代理线程
 * 并发调用。</p>
 *
 * <p>丢包率需要序号，而 {@link ParsedPacket} 不携带序号，故由代理在旁路读 RakNet 序号后调用
 * {@link #noteSequence(long)} 单独登记；Java（TCP）无丢包语义，不登记即为 0。</p>
 */
public final class BehaviorStreamExtractor {

    private static final int MAX_MOVE = 1024;
    private static final int MAX_ROTATION = 1024;
    private static final int MAX_ACTION = 512;

    private static final long WINDOW_5S = 5_000_000_000L;
    private static final long WINDOW_10S = 10_000_000_000L;
    private static final long WINDOW_60S = 60_000_000_000L;

    /** 单次水平位移超过该值即记一次瞬移。 */
    private static final double TELEPORT_DISTANCE = 8.0;
    /** 人类极限破坏间隔（ms）。 */
    private static final double HUMAN_BREAK_MS = 250.0;

    private final Deque<MoveSample> moves = new ArrayDeque<>();
    private final Deque<RotationSample> rotations = new ArrayDeque<>();
    private final Deque<ActionSample> actions = new ArrayDeque<>();
    private final Deque<Long> teleportTimes = new ArrayDeque<>();
    private final Deque<Long> correctionTimes = new ArrayDeque<>();

    private long lastTimestamp;
    private long flyNanos;
    private int packetCount;
    private long lastSeq = -1L;
    private int seqTotal;
    private int seqJumps;

    /** 生产路径：时间戳取 {@link System#nanoTime()}。 */
    public void feed(ParsedPacket packet) {
        feed(packet, System.nanoTime());
    }

    /** 可注入时钟的入流入口（测试用）。 */
    public synchronized void feed(ParsedPacket packet, long timestampNanos) {
        if (packet == null) {
            return;
        }
        packetCount++;
        lastTimestamp = timestampNanos;
        switch (packet) {
            case ParsedPacket.MovePlayer mp -> onMove(mp, timestampNanos);
            case ParsedPacket.PlayerAuthInput pi -> onAuthInput(pi, timestampNanos);
            case ParsedPacket.PlayerAction pa -> onPlayerAction(pa.actionType(), timestampNanos);
            case ParsedPacket.Attack ignored -> addAction(timestampNanos, ActionKind.ATTACK);
            case ParsedPacket.UseItem ignored -> addAction(timestampNanos, ActionKind.PLACE);
            case ParsedPacket.ServerCorrection ignored -> {
                correctionTimes.addLast(timestampNanos);
                prune(correctionTimes, timestampNanos, WINDOW_60S);
            }
            case ParsedPacket.Unknown ignored -> {
            }
        }
    }

    /** 登记一个 RakNet 序号（供丢包率统计）。 */
    public synchronized void noteSequence(long sequence) {
        seqTotal++;
        if (lastSeq >= 0 && sequence != lastSeq + 1) {
            seqJumps++;
        }
        lastSeq = sequence;
    }

    /** 水平速度（格/s），取最近两次移动采样。 */
    public synchronized double currentSpeed() {
        MoveSample[] pair = lastTwoMoves();
        if (pair == null) {
            return 0;
        }
        double dt = (pair[1].nanos - pair[0].nanos) / 1e9;
        if (dt <= 0) {
            return 0;
        }
        return Math.hypot(pair[1].x - pair[0].x, pair[1].z - pair[0].z) / dt;
    }

    /** 垂直速度（格/s），取最近两次移动采样。 */
    public synchronized double verticalSpeed() {
        MoveSample[] pair = lastTwoMoves();
        if (pair == null) {
            return 0;
        }
        double dt = (pair[1].nanos - pair[0].nanos) / 1e9;
        if (dt <= 0) {
            return 0;
        }
        return (pair[1].y - pair[0].y) / dt;
    }

    /** 持续飞行时间（秒）：连续上升累计，落地或下降即清零。 */
    public synchronized double flyDurationSeconds() {
        return flyNanos / 1e9;
    }

    /** 旋转角速度（度/s）：yaw 归一化到 [-180,180] 后，对 sqrt(Δyaw²+Δpitch²)/Δt 取均方根。 */
    public synchronized double rotationSpeed() {
        int n = rotations.size();
        if (n < 2) {
            return 0;
        }
        RotationSample[] arr = rotations.toArray(new RotationSample[0]);
        int intervals = Math.min(20, n - 1);
        int start = n - intervals;
        double sumSq = 0;
        int count = 0;
        for (int i = start; i < n; i++) {
            RotationSample prev = arr[i - 1];
            RotationSample cur = arr[i];
            double dt = (cur.nanos - prev.nanos) / 1e9;
            if (dt <= 0) {
                continue;
            }
            double dYaw = normalizeYaw(cur.yaw - prev.yaw);
            double dPitch = cur.pitch - prev.pitch;
            double speed = Math.sqrt(dYaw * dYaw + dPitch * dPitch) / dt;
            sumSq += speed * speed;
            count++;
        }
        return count == 0 ? 0 : Math.sqrt(sumSq / count);
    }

    /** 最近 60 秒内单次水平位移 > 8 格的次数。 */
    public synchronized int teleportCount() {
        prune(teleportTimes, lastTimestamp, WINDOW_60S);
        return teleportTimes.size();
    }

    /** 最近 5 秒攻击次数 / 5。 */
    public synchronized double attackCps() {
        return countActions(ActionKind.ATTACK, WINDOW_5S) / 5.0;
    }

    /** 最近 10 秒放置次数 / 10。 */
    public synchronized double placeRate() {
        return countActions(ActionKind.PLACE, WINDOW_10S) / 10.0;
    }

    /**
     * 破坏速度倍率：最近 10 次破坏间隔中位数越快倍率越大，等于 {@code 250ms / 中位数}。
     * 无数据或数据不足返回 1.0（中性）。间隔 < 250ms（快于人类极限）时倍率 > 1。
     */
    public synchronized double breakSpeedRatio() {
        List<Long> breaks = new ArrayList<>();
        for (ActionSample s : actions) {
            if (s.kind == ActionKind.BREAK) {
                breaks.add(s.nanos);
            }
        }
        if (breaks.size() < 2) {
            return 1.0;
        }
        int samples = Math.min(10, breaks.size());
        List<Double> intervals = new ArrayList<>();
        int start = breaks.size() - samples;
        for (int i = start + 1; i < breaks.size(); i++) {
            intervals.add((breaks.get(i) - breaks.get(i - 1)) / 1e6);
        }
        if (intervals.isEmpty()) {
            return 1.0;
        }
        Collections.sort(intervals);
        double median = intervals.get(intervals.size() / 2);
        if (median <= 0) {
            return 1.0;
        }
        return HUMAN_BREAK_MS / median;
    }

    /** 最近 60 秒服务器纠正次数。 */
    public synchronized int serverCorrectionCount() {
        prune(correctionTimes, lastTimestamp, WINDOW_60S);
        return correctionTimes.size();
    }

    /** 最近 10 秒内 y 落差 > 3 格，且期间无 onGround 采样。 */
    public synchronized boolean nofallSuspect() {
        long cutoff = lastTimestamp - WINDOW_10S;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        boolean anyGround = false;
        int count = 0;
        Iterator<MoveSample> it = moves.descendingIterator();
        while (it.hasNext()) {
            MoveSample s = it.next();
            if (s.nanos < cutoff) {
                break;
            }
            minY = Math.min(minY, s.y);
            maxY = Math.max(maxY, s.y);
            if (s.onGround) {
                anyGround = true;
            }
            count++;
        }
        return count >= 2 && (maxY - minY) > 3.0 && !anyGround;
    }

    /** 丢包率：序号跳跃次数 / 已登记序号包数；无序号数据返回 0。 */
    public synchronized double packetLossRate() {
        return seqTotal == 0 ? 0 : (double) seqJumps / seqTotal;
    }

    /** 已入流包总数。 */
    public synchronized int packetCount() {
        return packetCount;
    }

    /** 清空全部缓冲与计数。 */
    public synchronized void reset() {
        moves.clear();
        rotations.clear();
        actions.clear();
        teleportTimes.clear();
        correctionTimes.clear();
        lastTimestamp = 0;
        flyNanos = 0;
        packetCount = 0;
        lastSeq = -1L;
        seqTotal = 0;
        seqJumps = 0;
    }

    private void onMove(ParsedPacket.MovePlayer mp, long ts) {
        boolean rotNonZero = mp.pitch() != 0f || mp.yaw() != 0f;
        boolean allZeroPos = mp.x() == 0.0 && mp.y() == 0.0 && mp.z() == 0.0;
        if (rotNonZero) {
            addRotation(ts, mp.yaw(), mp.pitch());
        }
        if (allZeroPos && rotNonZero) {
            // 纯旋转帧（Java 0x16）：坐标位留 0，不能混进移动采样，否则瞬移/速度全被污染。
            return;
        }
        MoveSample prev = moves.peekLast();
        if (prev != null) {
            long dtNanos = ts - prev.nanos;
            double dx = mp.x() - prev.x;
            double dz = mp.z() - prev.z;
            if (Math.hypot(dx, dz) > TELEPORT_DISTANCE) {
                teleportTimes.addLast(ts);
                prune(teleportTimes, ts, WINDOW_60S);
            }
            if (mp.onGround() || mp.y() <= prev.y) {
                flyNanos = 0;
            } else if (dtNanos > 0) {
                flyNanos += dtNanos;
            }
        }
        moves.addLast(new MoveSample(ts, mp.x(), mp.y(), mp.z(), mp.onGround()));
        while (moves.size() > MAX_MOVE) {
            moves.removeFirst();
        }
    }

    private void onAuthInput(ParsedPacket.PlayerAuthInput pi, long ts) {
        if (pi.pitch() != 0.0 || pi.yaw() != 0.0) {
            addRotation(ts, pi.yaw(), pi.pitch());
        }
    }

    private void onPlayerAction(int actionType, long ts) {
        // 2 = STOP_DESTROY_BLOCK（Java）/ STOP_BREAK（基岩）：一次方块破坏完成。
        if (actionType == 2) {
            addAction(ts, ActionKind.BREAK);
        }
    }

    private void addRotation(long ts, double yaw, double pitch) {
        rotations.addLast(new RotationSample(ts, yaw, pitch));
        while (rotations.size() > MAX_ROTATION) {
            rotations.removeFirst();
        }
    }

    private void addAction(long ts, ActionKind kind) {
        actions.addLast(new ActionSample(ts, kind));
        while (actions.size() > MAX_ACTION) {
            actions.removeFirst();
        }
    }

    private MoveSample[] lastTwoMoves() {
        if (moves.size() < 2) {
            return null;
        }
        Iterator<MoveSample> it = moves.descendingIterator();
        MoveSample last = it.next();
        MoveSample prev = it.next();
        return new MoveSample[]{prev, last};
    }

    private int countActions(ActionKind kind, long window) {
        long cutoff = lastTimestamp - window;
        int n = 0;
        Iterator<ActionSample> it = actions.descendingIterator();
        while (it.hasNext()) {
            ActionSample s = it.next();
            if (s.nanos < cutoff) {
                break;
            }
            if (s.kind == kind) {
                n++;
            }
        }
        return n;
    }

    private static void prune(Deque<Long> queue, long now, long window) {
        long cutoff = now - window;
        while (!queue.isEmpty() && queue.peekFirst() < cutoff) {
            queue.removeFirst();
        }
    }

    private static double normalizeYaw(double delta) {
        double r = delta % 360.0;
        if (r < -180.0) {
            r += 360.0;
        }
        if (r > 180.0) {
            r -= 360.0;
        }
        return r;
    }

    /** 动作类别。 */
    private enum ActionKind {
        ATTACK,
        PLACE,
        BREAK
    }

    private record MoveSample(long nanos, double x, double y, double z, boolean onGround) {
    }

    private record RotationSample(long nanos, double yaw, double pitch) {
    }

    private record ActionSample(long nanos, ActionKind kind) {
    }
}