package com.potatotv.pacc.util.ml;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * v5.2 §6.1 {@code .paccm} 模型容器（后端训练侧实现，纯 JDK）。
 *
 * <p>容器布局（全部大端）：</p>
 * <pre>
 *   [0..4)    4B  magic "PAM1"
 *   [4..6)    2B  formatVersion（当前 1）
 *   [6..7)    1B  modelType（0=XGBoost / 1=Autoencoder / 2=LSTM-AE）
 *   [7..11)   4B  featureDim
 *   [11..15)  4B  weightLength（float32 权重个数 N）
 *   [15..15+4N)   N 个 float32 权重
 *   [末 32B]   32B 前序全部字节的 SHA-256
 * </pre>
 *
 * <p>该二进制与客户端 §2.1 推理侧加载的格式逐字节一致：后端负责训练并产出，
 * 客户端加载前用尾部 SHA-256 校验完整性（模型文件被替换 / 传输损坏都会被拒绝）。</p>
 */
public final class PaccModelFormat {

    /** 魔数：PACC Model 第 1 版。 */
    public static final byte[] MAGIC = {'P', 'A', 'M', '1'};

    /** 容器格式版本。 */
    public static final int FORMAT_VERSION = 1;

    /** 模型类型：XGBoost 判别模型。 */
    public static final byte TYPE_XGBOOST = 0;
    /** 模型类型：自编码器异常检测模型。 */
    public static final byte TYPE_AUTOENCODER = 1;
    /** 模型类型：LSTM 自编码器（端侧序列模型，后端暂不产出）。 */
    public static final byte TYPE_LSTM_AE = 2;

    /** 尾部校验和长度（SHA-256）。 */
    public static final int CHECKSUM_LENGTH = 32;

    /** 单个 float32 的字节数。 */
    public static final int FLOAT_BYTES = 4;

    /** 头部长度：4B magic + 2B 版本 + 1B 类型 + 4B 特征维 + 4B 权重个数。 */
    private static final int HEADER_LENGTH = 4 + 2 + 1 + 4 + 4;

    private PaccModelFormat() {
    }

    /** 解析后的容器视图。 */
    public record PaccModel(int formatVersion, byte modelType, int featureDim, float[] weights, byte[] checksum) {
    }

    /**
     * 编码为 {@code .paccm} 字节流（含尾部 SHA-256）。
     *
     * @param modelType  模型类型（{@link #TYPE_XGBOOST} / {@link #TYPE_AUTOENCODER} / {@link #TYPE_LSTM_AE}）
     * @param featureDim 特征维度（必须为正）
     * @param weights    权重区（float32，按模型自身的线性化约定排列，不得为空）
     */
    public static byte[] encode(byte modelType, int featureDim, float[] weights) {
        if (weights == null || weights.length == 0) {
            throw new IllegalArgumentException("权重区不能为空");
        }
        if (featureDim <= 0) {
            throw new IllegalArgumentException("特征维度必须为正：" + featureDim);
        }
        requireKnownType(modelType);

        ByteBuffer buf = ByteBuffer.allocate(HEADER_LENGTH + weights.length * FLOAT_BYTES + CHECKSUM_LENGTH);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put(MAGIC);
        buf.putShort((short) FORMAT_VERSION);
        buf.put(modelType);
        buf.putInt(featureDim);
        buf.putInt(weights.length);
        for (float w : weights) {
            buf.putFloat(w);
        }
        byte[] body = buf.array();
        int bodyLength = HEADER_LENGTH + weights.length * FLOAT_BYTES;
        byte[] checksum = sha256(body, 0, bodyLength);
        System.arraycopy(checksum, 0, body, bodyLength, CHECKSUM_LENGTH);
        return body;
    }

    /**
     * 解析并校验 {@code .paccm} 字节流。
     *
     * @throws IllegalArgumentException 结构非法（长度/魔数/版本/类型/权重长度不匹配）
     * @throws IllegalStateException    尾部 SHA-256 与内容不符（文件被替换或损坏）
     */
    public static PaccModel read(byte[] bytes) {
        if (bytes == null || bytes.length < HEADER_LENGTH + CHECKSUM_LENGTH) {
            throw new IllegalArgumentException("模型字节过短，" + (bytes == null ? 0 : bytes.length) + " 字节");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[4];
        buf.get(magic);
        for (int i = 0; i < 4; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IllegalArgumentException("模型魔数非法，非 .paccm 容器");
            }
        }
        int formatVersion = buf.getShort() & 0xFFFF;
        if (formatVersion != FORMAT_VERSION) {
            throw new IllegalArgumentException("不支持的容器格式版本：" + formatVersion);
        }
        byte modelType = buf.get();
        requireKnownType(modelType);
        int featureDim = buf.getInt();
        if (featureDim <= 0) {
            throw new IllegalArgumentException("特征维度必须为正：" + featureDim);
        }
        int weightLength = buf.getInt();
        if (weightLength <= 0) {
            throw new IllegalArgumentException("权重区长度必须为正：" + weightLength);
        }
        int bodyLength = HEADER_LENGTH + weightLength * FLOAT_BYTES;
        if (bytes.length != bodyLength + CHECKSUM_LENGTH) {
            throw new IllegalArgumentException("容器长度非法：声明权重 " + weightLength + " 个，实际 " + bytes.length + " 字节");
        }
        byte[] expected = sha256(bytes, 0, bodyLength);
        for (int i = 0; i < CHECKSUM_LENGTH; i++) {
            if (expected[i] != bytes[bodyLength + i]) {
                throw new IllegalStateException("模型校验和不匹配，文件可能被替换或损坏");
            }
        }
        float[] weights = new float[weightLength];
        for (int i = 0; i < weightLength; i++) {
            weights[i] = buf.getFloat();
        }
        byte[] checksum = new byte[CHECKSUM_LENGTH];
        System.arraycopy(bytes, bodyLength, checksum, 0, CHECKSUM_LENGTH);
        return new PaccModel(formatVersion, modelType, featureDim, weights, checksum);
    }

    /** 字节流的 SHA-256 十六进制小写摘要。 */
    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256(bytes, 0, bytes.length));
    }

    /** 模型类型名（用于日志与库表登记）。 */
    public static String typeName(byte modelType) {
        return switch (modelType) {
            case TYPE_XGBOOST -> "XGBOOST";
            case TYPE_AUTOENCODER -> "AUTOENCODER";
            case TYPE_LSTM_AE -> "LSTM_AE";
            default -> "UNKNOWN";
        };
    }

    private static void requireKnownType(byte modelType) {
        if (modelType != TYPE_XGBOOST && modelType != TYPE_AUTOENCODER && modelType != TYPE_LSTM_AE) {
            throw new IllegalArgumentException("未知模型类型：" + modelType);
        }
    }

    private static byte[] sha256(byte[] data, int offset, int length) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(data, offset, length);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然内置 SHA-256，走到这里说明运行环境被裁剪
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", e);
        }
    }
}