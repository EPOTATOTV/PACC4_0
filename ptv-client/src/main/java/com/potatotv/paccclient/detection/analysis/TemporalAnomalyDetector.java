package com.potatotv.paccclient.detection.analysis;

import com.potatotv.paccclient.ai.LstmAutoencoderModel;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.RingBuffer;

import java.util.List;

/**
 * 时序异常检测（文档 §3.1.3）：用 LSTM 时序自编码器学习正常行为序列，异常序列重构误差高。
 *
 * <p>内部维持最近 {@value #BUFFER} 步特征向量，达到 {@value #WINDOW} 步后取出最近
 * {@value #WINDOW} 步展平送入 {@link LstmAutoencoderModel#reconstruct(double[])}，
 * 返回输入与重构的均方误差。</p>
 *
 * <p>降级：未加载模型（PTV 尚未灰度下发权重）时恒返回 0.0，检测链路不因此中断
 * （文档 §2.1.4「模型加载失败 → 回退到规则引擎判定」）。</p>
 */
public final class TemporalAnomalyDetector {

    /** 触发时序推理所需的最少步数，也是送入模型的时间步长度。 */
    public static final int WINDOW = 32;
    /** 内部环形缓冲容量（保留比窗口更长的历史，便于后续扩展）。 */
    public static final int BUFFER = 64;

    private final RingBuffer<double[]> history = new RingBuffer<>(BUFFER);
    private final LstmAutoencoderModel model;

    /** 无模型构造（恒返回 0.0，用于规则回退链路）。 */
    public TemporalAnomalyDetector() {
        this(null);
    }

    /** 注入已加载的 LSTM 时序自编码器；{@code null} 表示未加载。 */
    public TemporalAnomalyDetector(LstmAutoencoderModel model) {
        this.model = model;
    }

    /** 是否已加载时序模型。 */
    public boolean modelLoaded() {
        return model != null;
    }

    /** 当前已累积的历史步数（测试与可观测性用）。 */
    public int historySize() {
        return history.size();
    }

    /**
     * 推入一步特征并计算时序异常分。
     *
     * @param fv 当前特征向量
     * @return 均方重构误差；历史不足 {@value #WINDOW} 步或未加载模型时为 0.0
     */
    public double anomalyScore(FeatureVector fv) {
        history.add(fv == null ? new double[0] : fv.toArray());
        if (model == null || history.size() < WINDOW) return 0.0;
        List<double[]> window = history.last(WINDOW);
        int stepDim = 0;
        for (double[] step : window) stepDim = Math.max(stepDim, step.length);
        if (stepDim == 0) return 0.0;

        double[] flat = new double[WINDOW * stepDim];
        for (int i = 0; i < WINDOW; i++) {
            double[] step = window.get(i);
            System.arraycopy(step, 0, flat, i * stepDim, step.length);
        }
        double[] rec = model.reconstruct(flat);
        int len = Math.min(flat.length, rec.length);
        if (len == 0) return 0.0;
        double se = 0;
        for (int i = 0; i < len; i++) {
            double d = flat[i] - rec[i];
            se += d * d;
        }
        return se / len;
    }
}