package com.potatotv.paccclient.spi;

import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.InputSource;
import com.potatotv.paccclient.probe.OsInfo;
import com.potatotv.paccclient.probe.SystemProbe;

import java.util.Objects;
import java.util.Optional;

/**
 * 检测器执行上下文（文档 §4 各检测器 {@code detect(DetectContext ctx)} 的入参）。
 *
 * <p>探针是只读的系统数据入口；{@link #features()} 是本周期共享的特征向量，检测器把综合分写回
 * 扩展维度（{@code ext_} 前缀），PRL 规则随后读取并做统一阈值判定。检测器自身不决定处置层级，
 * 处置由 {@code LayeredDecision} 统一裁决（文档 §9 注意事项 4）。</p>
 *
 * <p>{@link #inputSource()} 是三层架构批次新增的可选入参：输入时序检测器（
 * {@code InputTimingScanner}）需要原始点击 / 按键事件序列才能算间隔熵、抖动与键鼠同步率，
 * 而这些序列不在系统探针也不在特征向量里。为兼容既有调用方，构造器保留两参重载，
 * 未注入时为 {@link Optional#empty()}，检测器按「无数据 → 不产出」降级。</p>
 */
public final class DetectContext {

    private final SystemProbe systemProbe;
    private final FeatureVector features;
    private final InputSource inputSource;

    public DetectContext(SystemProbe systemProbe, FeatureVector features) {
        this(systemProbe, features, null);
    }

    public DetectContext(SystemProbe systemProbe, FeatureVector features, InputSource inputSource) {
        this.systemProbe = Objects.requireNonNull(systemProbe, "systemProbe");
        this.features = features == null ? new FeatureVector() : features;
        this.inputSource = inputSource;
    }

    /** 只读系统探针。 */
    public SystemProbe systemProbe() {
        return systemProbe;
    }

    /** 当前系统信息摘要（脱敏）。 */
    public OsInfo osInfo() {
        return systemProbe.osInfo();
    }

    /** 本周期共享特征向量（检测器写扩展维度用）。 */
    public FeatureVector features() {
        return features;
    }

    /** 只读行为数据源（点击 / 按键 / 轨迹）；未注入时为空。 */
    public Optional<InputSource> inputSource() {
        return Optional.ofNullable(inputSource);
    }

    /** 写入一个扩展特征（{@code ext_} 前缀，见 {@code ExtendedFeatureSchema}）。 */
    public void putExtended(String key, double value) {
        features.putExtended(key, value);
    }
}