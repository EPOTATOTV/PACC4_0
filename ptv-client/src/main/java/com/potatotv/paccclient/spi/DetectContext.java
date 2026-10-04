package com.potatotv.paccclient.spi;

import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.probe.OsInfo;
import com.potatotv.paccclient.probe.SystemProbe;

import java.util.Objects;

/**
 * 检测器执行上下文（文档 §4 各检测器 {@code detect(DetectContext ctx)} 的入参）。
 *
 * <p>探针是只读的系统数据入口；{@link #features()} 是本周期共享的特征向量，检测器把综合分写回
 * 扩展维度（{@code ext_} 前缀），PRL 规则随后读取并做统一阈值判定。检测器自身不决定处置层级，
 * 处置由 {@code LayeredDecision} 统一裁决（文档 §9 注意事项 4）。</p>
 */
public final class DetectContext {

    private final SystemProbe systemProbe;
    private final FeatureVector features;

    public DetectContext(SystemProbe systemProbe, FeatureVector features) {
        this.systemProbe = Objects.requireNonNull(systemProbe, "systemProbe");
        this.features = features == null ? new FeatureVector() : features;
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

    /** 写入一个扩展特征（{@code ext_} 前缀，见 {@code ExtendedFeatureSchema}）。 */
    public void putExtended(String key, double value) {
        features.putExtended(key, value);
    }
}