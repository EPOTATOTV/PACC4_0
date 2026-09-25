package com.potatotv.paccclient.ai;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.FeatureVector;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 端侧轻量 AI：加载 PTV 灰度下发的 {@code .paccm} 模型（XGBoost 结构化特征 + Autoencoder/LSTM-AE
 * 时序异常），对原始特征做端侧初筛，降低传输与误报（文档 §2.1）。
 *
 * <p>并发与热更新：全部模型状态封装在不可变快照 {@link Models} 中，字段 {@code volatile} 引用；
 * 每次 {@code load} 生成新快照后一次性替换，推理侧读取的是稳定快照，热更新不会撕裂。</p>
 *
 * <p>降级策略（文档 §2.1.4）：未加载 / 推理超时（&gt;50ms）→ 规则回退；模型过旧（&gt;30 天）→ 降权。</p>
 */
public final class LocalAiModel {

    /** 单次推理超时阈值：超过则跳过 AI，仅用规则（文档 §2.1.4）。 */
    private static final long TIMEOUT_NANOS = 50_000_000L;
    /** 模型过期阈值（天），超过则分数减半并标记需更新。 */
    private static final long STALE_DAYS = 30;
    private static final double W_XGB = 0.7;
    private static final double W_ANOMALY = 0.3;

    /** 不可变模型快照：一次 load 产生一份，volatile 替换实现无锁热更新。 */
    private static final class Models {
        final XGBoostModel xgb;
        final AutoencoderModel ae;
        final LstmAutoencoderModel lstm;
        final int version;
        final Instant loadedAt;

        Models(XGBoostModel xgb, AutoencoderModel ae, LstmAutoencoderModel lstm, int version, Instant loadedAt) {
            this.xgb = xgb;
            this.ae = ae;
            this.lstm = lstm;
            this.version = version;
            this.loadedAt = loadedAt;
        }
    }

    private volatile Models models;
    private final AtomicInteger versionSeq = new AtomicInteger();

    /** 从 {@code .paccm} 流加载（含头部校验与类型分发）。加载失败原状态保持不变。 */
    public void load(InputStream modelStream) throws IOException {
        PaccModelFormat.ReadModel rm = PaccModelFormat.read(modelStream);
        apply(rm.header(), rm.rawWeights());
    }

    /** 从 {@code .paccm} 字节加载，等价于 {@link #load(InputStream)}。 */
    public void load(byte[] raw) throws IOException {
        PaccModelFormat.ReadModel rm = PaccModelFormat.read(new ByteArrayInputStream(raw == null ? new byte[0] : raw));
        apply(rm.header(), rm.rawWeights());
    }

    /** 按 modelType 解析权重并生成新快照；同类型模型被替换，其余类型保留（支持 XGB+AE 组合）。 */
    private void apply(PaccModelFormat.ModelHeader h, byte[] rawWeights) {
        Models cur = models;
        XGBoostModel xgb = cur == null ? null : cur.xgb;
        AutoencoderModel ae = cur == null ? null : cur.ae;
        LstmAutoencoderModel lstm = cur == null ? null : cur.lstm;
        switch (h.modelType()) {
            case PaccModelFormat.TYPE_XGBOOST -> xgb = XGBoostModel.deserialize(rawWeights, h.featureDim());
            case PaccModelFormat.TYPE_AUTOENCODER -> ae = AutoencoderModel.deserialize(rawWeights, h.featureDim());
            case PaccModelFormat.TYPE_LSTM_AE -> {
                int[] shape = LstmAutoencoderModel.peekShape(rawWeights);
                lstm = LstmAutoencoderModel.deserialize(rawWeights, shape[0], shape[1]);
            }
            default -> throw new IllegalArgumentException("未知模型类型: " + h.modelType());
        }
        models = new Models(xgb, ae, lstm, versionSeq.incrementAndGet(), Instant.now());
    }

    public boolean loaded() {
        return models != null;
    }

    public int modelVersion() {
        Models m = models;
        return m == null ? 0 : m.version;
    }

    public Instant loadedAt() {
        Models m = models;
        return m == null ? null : m.loadedAt;
    }

    /**
     * 端侧推理：按特征插入顺序取值，XGBoost 与异常模型按 0.7/0.3 融合（仅有其一则单独使用）。
     * 超时降级为 {@code fallback}，模型过旧降权并标记 {@code stale}。
     */
    public InferenceResult infer(FeatureVector fv) {
        Models m = models;
        if (m == null) return InferenceResult.fallback(fv, 0L);

        long start = System.nanoTime();
        Map<String, Double> src = fv == null ? Map.of() : fv.asMap();
        List<String> keys = new ArrayList<>(src.keySet());
        double[] raw = new double[keys.size()];
        for (int i = 0; i < keys.size(); i++) raw[i] = src.get(keys.get(i));

        double score;
        Map<String, Double> contrib = Map.of();
        if (m.xgb != null) {
            double[] x = adapt(raw, m.xgb.featureDim());
            double xgbScore = m.xgb.predict(x);
            contrib = m.xgb.contributions(x, keys);
            score = (m.ae != null || m.lstm != null)
                    ? W_XGB * xgbScore + W_ANOMALY * anomalyScore(m, raw)
                    : xgbScore;
        } else {
            score = anomalyScore(m, raw);
        }

        long elapsed = System.nanoTime() - start;
        long latencyMs = elapsed / 1_000_000L;
        if (elapsed > TIMEOUT_NANOS) {
            // 推理超时 → 跳过 AI，仅用规则（文档 §2.1.4）
            return InferenceResult.fallback(fv, latencyMs);
        }
        Instant at = m.loadedAt;
        if (at != null && at.isBefore(Instant.now().minus(STALE_DAYS, ChronoUnit.DAYS))) {
            // 模型版本过旧 → 降低权重并标记需更新（文档 §2.1.4）
            return new InferenceResult(score * 0.5, contrib, InferenceResult.SOURCE_STALE, latencyMs);
        }
        return new InferenceResult(score, contrib, InferenceResult.SOURCE_MODEL, latencyMs);
    }

    /**
     * 端侧初筛分（0-100，向后兼容旧接口）。
     * <p>基础分取端侧规则分（钳制到 0-100）；加载模型且可从 {@code detailJson} 还原特征时，
     * 用 AI 分仅做抬升（避免低分被 AI 压低导致漏报）；未加载模型 / 特征缺失 / 推理回退时，
     * 回退到旧的 critical 加分行为。</p>
     */
    public int preScore(DetectionEvent e) {
        int base = Math.max(0, Math.min(100, e.clientRiskScore()));
        Models m = models;
        if (m == null) return legacy(e, base);
        FeatureVector fv = parseFeatures(e.detailJson());
        if (fv == null) return legacy(e, base);
        InferenceResult r = infer(fv);
        if (InferenceResult.SOURCE_FALLBACK.equals(r.source())) return legacy(e, base);
        int ai = Math.max(0, Math.min(100, (int) Math.round(r.score() * 100)));
        return Math.max(base, ai);
    }

    /** 旧行为：无模型时 critical 抬升 15。 */
    private static int legacy(DetectionEvent e, int base) {
        if ("critical".equalsIgnoreCase(e.severity())) return Math.min(100, base + 15);
        return base;
    }

    /** 异常分：优先 MLP 自编码器，其次 LSTM 时序自编码器，统一经 sigmoid 归一化到 0-1。 */
    private static double anomalyScore(Models m, double[] raw) {
        if (m.ae != null) {
            return sigmoid(m.ae.reconstructionError(adapt(raw, m.ae.featureDim())));
        }
        if (m.lstm != null) {
            int need = m.lstm.timesteps() * m.lstm.featuresPerStep();
            return sigmoid(m.lstm.anomalyScore(adapt(raw, need)));
        }
        return 0.0;
    }

    /** 将特征值补齐/截断到模型期望维度。 */
    private static double[] adapt(double[] raw, int dim) {
        if (dim <= 0) return raw;
        double[] x = new double[dim];
        System.arraycopy(raw, 0, x, 0, Math.min(raw.length, dim));
        return x;
    }

    /** 从 {@code detailJson} 还原数值特征向量；无内容或非法时返回 null（视为无特征）。 */
    private static FeatureVector parseFeatures(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) return null;
        String t = detailJson.trim();
        if (!t.startsWith("{")) return null;
        try {
            Map<String, Object> m = Json.decodeObject(t);
            FeatureVector fv = new FeatureVector();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getValue() instanceof Number n) fv.put(e.getKey(), n.doubleValue());
            }
            return fv.size() == 0 ? null : fv;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static double sigmoid(double z) {
        double c = Math.max(-60, Math.min(60, z));
        return 1.0 / (1.0 + Math.exp(-c));
    }
}