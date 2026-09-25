package com.potatotv.paccclient.detection;

import com.potatotv.paccclient.detection.samples.ActionSample;
import com.potatotv.paccclient.detection.samples.AttackSample;
import com.potatotv.paccclient.detection.samples.BlockActionSample;
import com.potatotv.paccclient.detection.samples.InputEvent;
import com.potatotv.paccclient.detection.samples.Point2D;
import com.potatotv.paccclient.detection.samples.PositionSample;

import java.util.List;

/**
 * 队列式 {@link InputSource} 实现：Hook / 采样线程调用 {@code push*} 写入环形缓冲，
 * 检测线程调用只读方法取快照。内部方法均同步，推入成本 O(1)。
 *
 * <p>默认容量按文档采集频率设定：输入 1024 / 轨迹 512 / 位置 256 / 攻击 128 / 方块 256 / 动作 128。</p>
 */
public final class BufferedInputSource implements InputSource {

    private final RingBuffer<InputEvent> inputs;
    private final RingBuffer<Point2D> trajectory;
    private final RingBuffer<PositionSample> positions;
    private final RingBuffer<AttackSample> attacks;
    private final RingBuffer<BlockActionSample> blocks;
    private final RingBuffer<ActionSample> actions;
    private volatile Point2D target;

    public BufferedInputSource() {
        this(1024, 512, 256, 128, 256, 128);
    }

    public BufferedInputSource(int inputCapacity, int trajectoryCapacity, int positionCapacity,
                               int attackCapacity, int blockCapacity, int actionCapacity) {
        this.inputs = new RingBuffer<>(inputCapacity);
        this.trajectory = new RingBuffer<>(trajectoryCapacity);
        this.positions = new RingBuffer<>(positionCapacity);
        this.attacks = new RingBuffer<>(attackCapacity);
        this.blocks = new RingBuffer<>(blockCapacity);
        this.actions = new RingBuffer<>(actionCapacity);
    }

    public synchronized void pushInput(InputEvent e) {
        inputs.add(e);
    }

    public synchronized void pushTrajectory(Point2D p) {
        trajectory.add(p);
    }

    public synchronized void pushPosition(PositionSample p) {
        positions.add(p);
    }

    public synchronized void pushAttack(AttackSample a) {
        attacks.add(a);
    }

    public synchronized void pushBlockAction(BlockActionSample b) {
        blocks.add(b);
    }

    public synchronized void pushAction(ActionSample a) {
        actions.add(a);
    }

    public void setAimTarget(Point2D target) {
        this.target = target;
    }

    /** 清空所有缓冲（会话切换时调用）。 */
    public synchronized void clear() {
        inputs.clear();
        trajectory.clear();
        positions.clear();
        attacks.clear();
        blocks.clear();
        actions.clear();
        target = null;
    }

    /** 最近 n 个输入事件（旧→新）。 */
    public synchronized List<InputEvent> recentInputs(int n) {
        return inputs.last(n);
    }

    @Override
    public synchronized List<InputEvent> inputEvents() {
        return inputs.snapshot();
    }

    @Override
    public synchronized List<Point2D> mouseTrajectory() {
        return trajectory.snapshot();
    }

    @Override
    public synchronized List<PositionSample> positionSamples() {
        return positions.snapshot();
    }

    @Override
    public synchronized List<AttackSample> attackSamples() {
        return attacks.snapshot();
    }

    @Override
    public synchronized List<BlockActionSample> blockActions() {
        return blocks.snapshot();
    }

    @Override
    public synchronized List<ActionSample> actionSamples() {
        return actions.snapshot();
    }

    @Override
    public Point2D aimTarget() {
        return target;
    }
}