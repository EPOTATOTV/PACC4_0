package com.potatotv.paccclient.detection;

import com.potatotv.paccclient.detection.samples.ActionSample;
import com.potatotv.paccclient.detection.samples.AttackSample;
import com.potatotv.paccclient.detection.samples.BlockActionSample;
import com.potatotv.paccclient.detection.samples.InputEvent;
import com.potatotv.paccclient.detection.samples.Point2D;
import com.potatotv.paccclient.detection.samples.PositionSample;

import java.util.List;

/**
 * 端侧行为数据源抽象（文档 §2.2.4）：由鼠标/键盘 Hook、游戏内存只读采样与客户端 tick 钩子
 * 推入原始事件，{@link FeatureCollector} 只读消费。
 * <p>所有列表均按时间戳升序，且不包含原始输入明文（仅统计所需的最小信息）。</p>
 */
public interface InputSource {

    /** 原始输入事件（点击 / 挥臂 / 按键）。 */
    List<InputEvent> inputEvents();

    /** 鼠标轨迹点（最近若干毫秒）。 */
    List<Point2D> mouseTrajectory();

    /** 位置采样。 */
    List<PositionSample> positionSamples();

    /** 攻击采样。 */
    List<AttackSample> attackSamples();

    /** 方块放置/破坏采样。 */
    List<BlockActionSample> blockActions();

    /** 界面动作采样（进食/开箱/背包/护甲）。 */
    List<ActionSample> actionSamples();

    /** 当前瞄准目标（无目标返回 {@code null}）。 */
    default Point2D aimTarget() {
        return null;
    }
}