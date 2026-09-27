package com.potatotv.paccclient.detection.federated;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * DF §4.1.2 端侧联邦参数布局：把「一维全局参数向量」与既有端侧模型容器
 * {@code ai.AutoencoderModel} 的权重布局互相转换。
 *
 * <p>端侧本地模型是「{@code FeatureSchema} 维度 → 隐藏层 → 还原」的 MLP 自编码器（异常检测）。
 * 其参数展平顺序为 {@code W1（h×d 行优先）→ b1（h）→ W2（d×h 行优先）→ b2（d）}，与
 * {@code AutoencoderModel.serialize()} 的载荷顺序完全一致；本类只负责补上容器头部
 * （魔数 {@code AE01} + 维度 + 隐藏层大小），使联邦下发的全局参数可以直接被既有推理层加载。</p>
 *
 * <p>因此端云约定：<b>联邦向量维度 = {@link #parameterCount(int, int)}</b>，云端 FedAvg 在这个
 * 维度上聚合，端侧下载后原样还原为同一形状的自编码器。</p>
 */
public final class FederatedParameterLayout {

    /** 与 {@code AutoencoderModel} 一致的魔数。 */
    private static final byte[] AE_MAGIC = {'A', 'E', '0', '1'};

    private FederatedParameterLayout() {
    }

    /** 自编码器参数总数：{@code h×d（W1） + h（b1） + d×h（W2） + d（b2）}。 */
    public static int parameterCount(int featureDim, int hidden) {
        return 2 * featureDim * hidden + hidden + featureDim;
    }

    /**
     * 全局参数向量 → 自编码器权重字节（含 {@code AE01} 头部，可交给
     * {@code AutoencoderModel.deserialize(raw, featureDim)}）。
     *
     * @throws IllegalArgumentException 维度非法或向量长度与布局不符
     */
    public static byte[] toAutoencoderBytes(double[] vector, int featureDim, int hidden) {
        if (featureDim <= 0 || hidden <= 0) {
            throw new IllegalArgumentException("自编码器维度必须为正: featureDim=" + featureDim + " hidden=" + hidden);
        }
        int expected = parameterCount(featureDim, hidden);
        if (vector == null || vector.length != expected) {
            throw new IllegalArgumentException("参数向量长度 " + (vector == null ? 0 : vector.length)
                    + " 与布局 " + expected + "（dim=" + featureDim + ", hidden=" + hidden + "）不匹配");
        }
        ByteBuffer bb = ByteBuffer.allocate(12 + expected * 4).order(ByteOrder.BIG_ENDIAN);
        bb.put(AE_MAGIC);
        bb.putInt(featureDim);
        bb.putInt(hidden);
        for (double v : vector) {
            bb.putFloat((float) v);
        }
        return bb.array();
    }
}